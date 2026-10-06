package com.orazaka.billingservice.application.service;

import com.orazaka.billing.domain.exception.InsufficientCreditsException;
import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.ConsumptionReport;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.EnforcementMode;
import com.orazaka.billing.domain.model.HoldStatus;
import com.orazaka.billing.domain.model.MeteredStep;
import com.orazaka.billing.domain.model.SettleCreditCommand;
import com.orazaka.billingservice.domain.model.CreditBucket;
import com.orazaka.billingservice.domain.model.CreditGrantedEvent;
import com.orazaka.billingservice.domain.model.CreditHold;
import com.orazaka.billingservice.domain.model.LedgerEntryType;
import com.orazaka.billingservice.domain.model.LowBalanceEvent;
import com.orazaka.billingservice.domain.model.PricebookRate;
import com.orazaka.billingservice.domain.model.UsageRecordedEvent;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit ledger — the single owner of the hold/settle/release protocol (ADR-033).
 *
 * <p>Three properties are enforced here and nowhere else:
 *
 * <ul>
 *   <li><b>A balance can never go negative.</b> Authorisation is a single conditional {@code
 *       UPDATE} whose {@code WHERE} clause carries the affordability test, so two concurrent
 *       submissions cannot both consume the same last credits. No read-before-write, no
 *       application-level lock (ERR-109).
 *   <li><b>A settlement is applied at most once.</b> The ledger's {@code idempotency_key} is UNIQUE
 *       and the insert uses {@code ON CONFLICT DO NOTHING}: a redelivered message affects zero rows
 *       and the settle returns without touching the wallet. A double debit is unrecoverable trust
 *       damage, so this guard sits underneath the consumer's own dedup rather than replacing it.
 *   <li><b>A failed job is never billed.</b> Release closes the hold with no ledger entry at all.
 * </ul>
 */
@Service
public class CreditLedgerService {

  private static final Logger log = LoggerFactory.getLogger(CreditLedgerService.class);

  private static final String SYSTEM_ACTOR = "system";

  private final JdbcTemplate jdbcTemplate;
  private final PricingService pricingService;
  private final BillingConfigurationService configurationService;
  private final OutboxService outboxService;
  private final CreditRefusalService creditRefusalService;
  private final MeterRegistry meterRegistry;

  public CreditLedgerService(
      JdbcTemplate jdbcTemplate,
      PricingService pricingService,
      BillingConfigurationService configurationService,
      OutboxService outboxService,
      CreditRefusalService creditRefusalService,
      MeterRegistry meterRegistry) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.pricingService = Objects.requireNonNull(pricingService, "PricingService cannot be null");
    this.configurationService =
        Objects.requireNonNull(configurationService, "BillingConfigurationService cannot be null");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
    this.creditRefusalService =
        Objects.requireNonNull(creditRefusalService, "CreditRefusalService cannot be null");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry cannot be null");
  }

  /**
   * Reserves the estimated cost of a request before it executes.
   *
   * <p>The estimate comes from this service's own pricebook. The caller's {@code estimatedCredits}
   * is honoured only when positive — that is the exact-hold path for capabilities whose quantity is
   * known up front (TTS characters, STT duration), not a way for a producer to price itself.
   *
   * @param command the reservation request
   * @return the outcome, carrying the pinned pricebook version
   * @throws InsufficientCreditsException under {@code ENFORCING} when the actor cannot cover it
   */
  @Transactional
  public CreditHoldResponse hold(CreditHoldCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    EnforcementMode mode = configurationService.enforcementMode();
    if (mode == EnforcementMode.OFF) {
      return CreditHoldResponse.notMetered();
    }

    PricebookRate rate = pricingService.currentRate(command.capability(), command.modelName());
    long estimate =
        command.estimatedCredits() > 0 ? command.estimatedCredits() : rate.estimateCredits();
    ensureWallet(command.actorId());

    boolean reserved = reserve(command.actorId(), estimate);
    if (!reserved) {
      WalletSnapshot wallet = requireWallet(command.actorId());
      boolean enforcing = mode == EnforcementMode.ENFORCING;
      // Recorded either way, and in its own transaction: under ENFORCING the throw below rolls
      // this one back, so an audit row written here would vanish with it and the 402 rate would
      // read zero however many users were turned away. Under DRY_RUN the same row is the
      // shadow-metering evidence phase 0 exists to collect.
      creditRefusalService.record(command, estimate, wallet.available(), enforcing);
      if (enforcing) {
        throw new InsufficientCreditsException(
            command.actorId(), command.capability(), estimate, wallet.available());
      }
      log.warn(
          "[DRY_RUN] would have refused actor={} capability={} required={} available={}",
          command.actorId(),
          command.capability(),
          estimate,
          wallet.available());
      reserveUnconditionally(command.actorId(), estimate);
    }

    UUID holdId = insertHold(command, estimate, rate.version());
    WalletSnapshot after = requireWallet(command.actorId());
    return new CreditHoldResponse(
        holdId.toString(),
        true,
        mode == EnforcementMode.DRY_RUN,
        estimate,
        after.available(),
        rate.version());
  }

  /**
   * Closes a hold against an executor's raw measurements, converting them into the unit the hold's
   * pinned rate is priced in.
   *
   * <p>This is the settlement path for the asynchronous outcomes on {@code job.{id}.done}.
   * Executors report what they measured — frames, steps, pixels, GPU seconds — and the unit is
   * resolved here from the pricebook, so no producer carries a copy of a pricing decision and a
   * producer/pricebook unit mismatch cannot arise.
   *
   * @param holdId the reservation to close
   * @param report what the executor measured
   * @param idempotencyKey the replay guard
   * @return {@code true} when the report priced to a quantity and the hold was settled; {@code
   *     false} when nothing billable could be derived and the caller should release instead
   */
  @Transactional
  public boolean settleMeasured(String holdId, ConsumptionReport report, String idempotencyKey) {
    Objects.requireNonNull(report, "report must not be null");
    Optional<CreditHold> maybeHold = findHold(holdId);
    if (maybeHold.isEmpty() || !maybeHold.get().isOpen()) {
      return false;
    }
    CreditHold hold = maybeHold.get();
    PricebookRate rate =
        pricingService.pinnedRate(hold.pricebookVersion(), hold.capability(), hold.modelName());
    Optional<BigDecimal> quantity = report.quantityFor(rate.unit());
    if (quantity.isEmpty()) {
      log.warn(
          "Hold {} reported no {} to bill for capability={} model={}",
          holdId,
          rate.unit(),
          hold.capability(),
          hold.modelName());
      return false;
    }
    settle(
        new SettleCreditCommand(holdId, rate.unit(), quantity.get(), idempotencyKey),
        report.gpuSeconds());
    return true;
  }

  /**
   * [ADR-041] Closes one hold against the measurements of the several steps it authorised.
   *
   * <p>Prices each step against its OWN {@code (capability, model)} row at the version the hold
   * pinned, sums the credits, and applies a single debit. {@link #settleMeasured} cannot do this:
   * it derives its quantity from the HOLD's rate, so a Studio run holding against AGENT/CALL
   * settled at {@code quantityFor(CALL)} — a hardcoded 1, five credits — for whichever step
   * finished first, and every later step found the hold closed. 480 reserved, 5 debited.
   *
   * <p>An empty list releases. A run whose steps measured nothing must not be billed at its
   * estimate: that is the same choice {@code JobSettlementListener} makes for a single job, and the
   * direction the error should run in.
   *
   * <p>A step whose report yields no quantity for its rate's unit is skipped with a warning rather
   * than failing the settlement. The alternative is to abandon the whole debit because one executor
   * under-reported, which bills the user nothing for four steps that did run.
   *
   * @param holdId the reservation to close
   * @param steps what each step measured and what prices it
   * @param idempotencyKey the replay guard
   * @return {@code true} when a debit was applied, {@code false} when there was nothing to bill and
   *     the caller should release
   */
  @Transactional
  public boolean settleAggregate(String holdId, List<MeteredStep> steps, String idempotencyKey) {
    Optional<CreditHold> maybeHold = findHold(holdId);
    if (maybeHold.isEmpty() || !maybeHold.get().isOpen()) {
      return false;
    }
    if (steps == null || steps.isEmpty()) {
      return false;
    }
    CreditHold hold = maybeHold.get();

    List<PricedStep> priced = new ArrayList<>();
    long measured = 0L;
    for (MeteredStep step : steps) {
      PricebookRate rate =
          pricingService.pinnedRate(hold.pricebookVersion(), step.capability(), step.modelName());
      Optional<BigDecimal> quantity = step.consumption().quantityFor(rate.unit());
      if (quantity.isEmpty()) {
        log.warn(
            "Hold {}: a {} step reported no {} to bill; it contributes nothing to the run's cost",
            holdId,
            step.capability(),
            rate.unit());
        continue;
      }
      long credits = rate.creditsFor(quantity.get());
      priced.add(new PricedStep(step, rate, quantity.get(), credits));
      measured += credits;
    }
    if (priced.isEmpty()) {
      return false;
    }

    long credits = capToOvershoot(measured, hold);
    WalletSnapshot wallet = lockWallet(hold.actorId());
    long fromGranted = Math.min(credits, wallet.balanceGranted());
    long fromPurchased = Math.min(credits - fromGranted, wallet.balancePurchased());
    if (fromGranted + fromPurchased < credits) {
      log.warn(
          "Aggregate settlement clamped to available balance: hold={} requested={} applied={}",
          hold.id(),
          credits,
          fromGranted + fromPurchased);
    }

    boolean applied =
        appendDebit(hold, CreditBucket.GRANTED, fromGranted, wallet, idempotencyKey)
            | appendDebit(hold, CreditBucket.PURCHASED, fromPurchased, wallet, idempotencyKey);
    if (!applied) {
      log.debug("Aggregate settlement already applied for idempotencyKey={}", idempotencyKey);
      return true;
    }

    jdbcTemplate.update(
        "UPDATE credit_wallet SET balance_granted = balance_granted - ?,"
            + " balance_purchased = balance_purchased - ?, held = GREATEST(held - ?, 0),"
            + " version = version + 1, updated_at = now() WHERE actor_id = ?",
        fromGranted,
        fromPurchased,
        hold.estimatedCredits(),
        hold.actorId());
    jdbcTemplate.update(
        "UPDATE credit_hold SET status = ?, settled_credits = ?, settled_at = now() WHERE id = ?",
        HoldStatus.SETTLED.name(),
        fromGranted + fromPurchased,
        hold.id());
    // One usage_event per step, not one for the run: the total is only auditable if the
    // measurements it was built from are stored beside it (design §7).
    for (PricedStep step : priced) {
      recordStepUsage(hold, step);
    }
    warnIfBalanceFellLow(hold.actorId(), wallet, fromGranted + fromPurchased);
    log.info(
        "Hold {} settled at {} credits across {} step(s) (measured {}, estimate {})",
        hold.id(),
        fromGranted + fromPurchased,
        priced.size(),
        measured,
        hold.estimatedCredits());
    return true;
  }

  /**
   * One step, its rate, and what it cost — carried so usage rows can be written after the debit.
   */
  private record PricedStep(
      MeteredStep step, PricebookRate rate, BigDecimal quantity, long credits) {}

  /** One step's usage row, priced at its own rate rather than the run hold's. */
  private void recordStepUsage(CreditHold hold, PricedStep priced) {
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, hold_id, correlation_id, occurred_at, gpu_seconds)"
            + " SELECT ?, ?, ?, ?, ?, ?, ?, correlation_id, now(), ? FROM credit_hold WHERE id = ?",
        hold.actorId(),
        priced.step().capability().name(),
        priced.step().modelName(),
        priced.rate().unit().name(),
        priced.quantity(),
        priced.credits(),
        hold.id(),
        priced.step().consumption().gpuSeconds(),
        hold.id());

    String correlationId =
        jdbcTemplate.queryForObject(
            "SELECT correlation_id FROM credit_hold WHERE id = ?", String.class, hold.id());
    // Same transaction as the debit: the money moving and the world being told must be atomic.
    // Announced per step, like the usage row, so a consumer sees the same breakdown the ledger has.
    outboxService.append(
        "WALLET",
        hold.actorId(),
        "evt.usage.recorded",
        new UsageRecordedEvent(
            hold.actorId(),
            priced.step().capability().name(),
            priced.step().modelName(),
            priced.rate().unit().name(),
            priced.quantity(),
            priced.credits(),
            hold.id().toString(),
            correlationId));
  }

  /**
   * Closes a hold against measured consumption, writing the debit to the append-only ledger.
   *
   * <p>Debits granted credits before purchased ones: granted credits expire at period end, so
   * spending them first is what stops a user losing balance they paid for.
   *
   * @param command the measured consumption
   */
  @Transactional
  public void settle(SettleCreditCommand command) {
    settle(command, null);
  }

  /**
   * Settles, additionally recording what the execution cost to produce.
   *
   * <p>{@code gpuSeconds} is the cost side of the margin: credits charged answer what a user paid,
   * and only occupancy answers what it cost us. Stored beside the charge because a margin computed
   * from two tables that were never joined at write time is a margin nobody can reconstruct (design
   * §7).
   *
   * @param command the measured consumption
   * @param gpuSeconds accelerator occupancy, or {@code null} when the executor reported none
   */
  @Transactional
  public void settle(SettleCreditCommand command, BigDecimal gpuSeconds) {
    Objects.requireNonNull(command, "command must not be null");
    Optional<CreditHold> maybeHold = findHold(command.holdId());
    if (maybeHold.isEmpty() || !maybeHold.get().isOpen()) {
      // Already settled, already released, or a hold from a disabled run. Nothing to do.
      return;
    }
    CreditHold hold = maybeHold.get();
    PricebookRate rate =
        pricingService.pinnedRate(hold.pricebookVersion(), hold.capability(), hold.modelName());
    if (rate.unit() != command.unit()) {
      // Multiplying a quantity by a rate expressed in another unit produces a plausible-looking
      // number that is simply wrong, and a wrong debit is unrecoverable trust damage. Refuse.
      throw new IllegalArgumentException(
          "Settlement unit %s does not match the pinned rate's %s for hold %s"
              .formatted(command.unit(), rate.unit(), command.holdId()));
    }

    long credits = capToOvershoot(rate.creditsFor(command.quantity()), hold);

    // Row lock: serialises concurrent settlements for one actor so the bucket split below is
    // computed against a balance nobody else is moving. The affordability test itself is still
    // the database's, not ours.
    WalletSnapshot wallet = lockWallet(hold.actorId());
    long fromGranted = Math.min(credits, wallet.balanceGranted());
    long fromPurchased = Math.min(credits - fromGranted, wallet.balancePurchased());
    if (fromGranted + fromPurchased < credits) {
      log.warn(
          "Settlement clamped to available balance: hold={} requested={} applied={}",
          hold.id(),
          credits,
          fromGranted + fromPurchased);
    }

    boolean applied =
        appendDebit(hold, CreditBucket.GRANTED, fromGranted, wallet, command.idempotencyKey())
            | appendDebit(
                hold, CreditBucket.PURCHASED, fromPurchased, wallet, command.idempotencyKey());
    if (!applied) {
      log.debug("Settlement already applied for idempotencyKey={}", command.idempotencyKey());
      return;
    }

    jdbcTemplate.update(
        "UPDATE credit_wallet SET balance_granted = balance_granted - ?,"
            + " balance_purchased = balance_purchased - ?, held = GREATEST(held - ?, 0),"
            + " version = version + 1, updated_at = now() WHERE actor_id = ?",
        fromGranted,
        fromPurchased,
        hold.estimatedCredits(),
        hold.actorId());
    jdbcTemplate.update(
        "UPDATE credit_hold SET status = ?, settled_credits = ?, settled_at = now() WHERE id = ?",
        HoldStatus.SETTLED.name(),
        fromGranted + fromPurchased,
        hold.id());
    recordUsage(hold, command, fromGranted + fromPurchased, gpuSeconds);
    warnIfBalanceFellLow(hold.actorId(), wallet, fromGranted + fromPurchased);
  }

  /**
   * Closes a hold with no debit — a failed generation is never billed.
   *
   * @param holdId the reservation to release
   * @param reason why it was released, retained for audit
   */
  @Transactional
  public void release(String holdId, String reason) {
    releaseInternal(holdId, reason, HoldStatus.RELEASED);
  }

  /**
   * Releases every {@code ACTIVE} hold past its TTL.
   *
   * <p>Without this a crashed worker freezes a user's balance permanently — the most commonly
   * omitted component of credit systems, which is why it is scheduled rather than manual.
   *
   * @return how many holds were swept
   */
  @Transactional
  public int sweepExpiredHolds() {
    List<String> expired =
        jdbcTemplate.queryForList(
            "SELECT id FROM credit_hold WHERE status = ? AND expires_at < now()",
            String.class,
            HoldStatus.ACTIVE.name());
    expired.forEach(id -> releaseInternal(id, "hold TTL expired", HoldStatus.EXPIRED));
    if (!expired.isEmpty()) {
      log.info("Hold sweeper released {} expired hold(s)", expired.size());
    }
    return expired.size();
  }

  /**
   * Reads a wallet's current balances.
   *
   * @param actorId the opaque billable subject
   * @return the snapshot, empty when the actor has no wallet yet
   */
  public Optional<WalletSnapshot> wallet(String actorId) {
    return jdbcTemplate
        .query(
            "SELECT actor_id, balance_granted, balance_purchased, held"
                + " FROM credit_wallet WHERE actor_id = ?",
            (rs, rowNum) ->
                new WalletSnapshot(
                    rs.getString("actor_id"),
                    rs.getLong("balance_granted"),
                    rs.getLong("balance_purchased"),
                    rs.getLong("held")),
            actorId)
        .stream()
        .findFirst();
  }

  /**
   * Credits a wallet with credits that were bought rather than granted by a period.
   *
   * <p>Lives here because the ledger is the single owner of every write that moves a balance: a
   * pack subscription that inserted its own ledger row would be a second place where {@code
   * balance_after} is computed, and two answers to "why is this balance what it is" is precisely
   * what an append-only ledger exists to prevent.
   *
   * <p>The {@code PURCHASED} bucket, never {@code GRANTED}: bought credits do not expire at the
   * subscription period boundary, and putting them in the wrong bucket would silently confiscate
   * them at the next rollover.
   *
   * <p><b>Idempotent by construction.</b> The caller supplies a key derived from the purchase
   * itself, so a redelivered message or a double-submitted form inserts nothing and moves nothing —
   * the same guard the settle path relies on. A second call with the same key returns {@code false}
   * rather than throwing: a replay is a non-event, not an error.
   *
   * @param actorId the opaque billable subject
   * @param credits how many credits to add — must be positive
   * @param referenceType what bought them, e.g. {@code PACK}
   * @param referenceId that thing's identifier
   * @param idempotencyKey a key stable across retries of this same purchase
   * @param reason why, stored on the ledger entry
   * @return {@code true} when the credits were applied, {@code false} when this key was already
   *     recorded
   */
  @Transactional
  public boolean purchase(
      String actorId,
      long credits,
      String referenceType,
      String referenceId,
      String idempotencyKey,
      String reason) {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    if (credits <= 0) {
      throw new IllegalArgumentException("a purchase must add credits");
    }
    ensureWallet(actorId);

    // Claim the key BEFORE moving the balance: the unique index is what makes the replay a
    // no-op, so the insert has to be the operation that can fail (ERR-109).
    int claimed =
        jdbcTemplate.update(
            "INSERT INTO credit_ledger_entry (actor_id, entry_type, bucket, amount, balance_after,"
                + " reference_type, reference_id, idempotency_key, reason, created_by)"
                + " SELECT ?, ?, ?, ?, balance_purchased + ?, ?, ?, ?, ?, ?"
                + " FROM credit_wallet WHERE actor_id = ?"
                + " ON CONFLICT (idempotency_key) DO NOTHING",
            actorId,
            LedgerEntryType.PURCHASE.name(),
            CreditBucket.PURCHASED.name(),
            credits,
            credits,
            referenceType,
            referenceId,
            idempotencyKey,
            reason,
            SYSTEM_ACTOR,
            actorId);
    if (claimed != 1) {
      log.info(
          "Purchase {} already applied for actor {} — replay ignored", idempotencyKey, actorId);
      return false;
    }

    jdbcTemplate.update(
        "UPDATE credit_wallet SET balance_purchased = balance_purchased + ?, version = version + 1,"
            + " updated_at = now() WHERE actor_id = ?",
        credits,
        actorId);

    outboxService.append(
        "WALLET",
        actorId,
        "evt.credit.granted",
        new CreditGrantedEvent(
            actorId,
            CreditBucket.PURCHASED.name(),
            credits,
            requireWallet(actorId).balancePurchased(),
            reason,
            SYSTEM_ACTOR,
            java.time.Instant.now()));

    log.info("Actor {} purchased {} credits — {}", actorId, credits, reason);
    return true;
  }

  private void releaseInternal(String holdId, String reason, HoldStatus terminal) {
    Optional<CreditHold> maybeHold = findHold(holdId);
    if (maybeHold.isEmpty() || !maybeHold.get().isOpen()) {
      return;
    }
    CreditHold hold = maybeHold.get();
    jdbcTemplate.update(
        "UPDATE credit_wallet SET held = GREATEST(held - ?, 0), version = version + 1,"
            + " updated_at = now() WHERE actor_id = ?",
        hold.estimatedCredits(),
        hold.actorId());
    jdbcTemplate.update(
        "UPDATE credit_hold SET status = ?, settled_at = now() WHERE id = ?",
        terminal.name(),
        hold.id());
    // Counted, not contracted (ADR-046 §2). The policy of settling a failed run's measured cost
    // was rejected as undecidable, and this is what replaces it: the amount the platform hands
    // back is now a number somebody can look at instead of an argument nobody can settle.
    meterRegistry
        .counter("orazaka.billing.hold.released.credits", "terminal", terminal.name())
        .increment(hold.estimatedCredits());
    log.info("Released hold={} status={} reason={}", hold.id(), terminal, reason);
  }

  /** The affordability test is the {@code WHERE} clause — concurrency-safe by construction. */
  private boolean reserve(String actorId, long estimate) {
    return jdbcTemplate.update(
            "UPDATE credit_wallet SET held = held + ?, version = version + 1, updated_at = now()"
                + " WHERE actor_id = ?"
                + "   AND balance_granted + balance_purchased - held >= ?",
            estimate,
            actorId,
            estimate)
        == 1;
  }

  /** {@code DRY_RUN} only: records the reservation even though the actor cannot afford it. */
  private void reserveUnconditionally(String actorId, long estimate) {
    jdbcTemplate.update(
        "UPDATE credit_wallet SET held = held + ?, version = version + 1, updated_at = now()"
            + " WHERE actor_id = ?",
        estimate,
        actorId);
  }

  private long capToOvershoot(long credits, CreditHold hold) {
    long ceiling = hold.estimatedCredits() + configurationService.overshootMaxCredits();
    if (credits > ceiling) {
      log.warn(
          "Settlement overshoot capped: hold={} measured={} ceiling={}",
          hold.id(),
          credits,
          ceiling);
      return ceiling;
    }
    return credits;
  }

  /**
   * Appends one bucket's debit. Returns {@code false} when the unique {@code idempotency_key}
   * rejected the row — the replay guard, expressed as an affected-row count rather than an
   * exception so the surrounding transaction stays usable.
   */
  private boolean appendDebit(
      CreditHold hold,
      CreditBucket bucket,
      long amount,
      WalletSnapshot before,
      String idempotencyKey) {
    if (amount <= 0) {
      return false;
    }
    long balanceAfter =
        bucket == CreditBucket.GRANTED
            ? before.balanceGranted() - amount
            : before.balancePurchased() - amount;
    return jdbcTemplate.update(
            "INSERT INTO credit_ledger_entry (actor_id, entry_type, bucket, amount, balance_after,"
                + " reference_type, reference_id, idempotency_key, reason, created_by)"
                + " VALUES (?, ?, ?, ?, ?, 'HOLD', ?, ?, ?, ?)"
                + " ON CONFLICT (idempotency_key) DO NOTHING",
            hold.actorId(),
            LedgerEntryType.DEBIT.name(),
            bucket.name(),
            -amount,
            balanceAfter,
            hold.id().toString(),
            idempotencyKey + ":" + bucket.name(),
            "Settled " + hold.capability() + " hold",
            SYSTEM_ACTOR)
        > 0;
  }

  private void recordUsage(
      CreditHold hold, SettleCreditCommand command, long credits, BigDecimal gpuSeconds) {
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, hold_id, correlation_id, occurred_at, gpu_seconds)"
            + " SELECT ?, ?, ?, ?, ?, ?, ?, correlation_id, now(), ? FROM credit_hold WHERE id = ?",
        hold.actorId(),
        hold.capability().name(),
        hold.modelName(),
        command.unit().name(),
        command.quantity(),
        credits,
        hold.id(),
        gpuSeconds,
        hold.id());

    String correlationId =
        jdbcTemplate.queryForObject(
            "SELECT correlation_id FROM credit_hold WHERE id = ?", String.class, hold.id());
    // Same transaction as the debit: the money moving and the world being told must be atomic.
    outboxService.append(
        "WALLET",
        hold.actorId(),
        "evt.usage.recorded",
        new UsageRecordedEvent(
            hold.actorId(),
            hold.capability().name(),
            hold.modelName(),
            command.unit().name(),
            command.quantity(),
            credits,
            hold.id().toString(),
            correlationId));
  }

  /**
   * Emits {@code evt.wallet.low-balance} when this debit is the one that crossed the threshold.
   *
   * <p>On the way down only. A wallet that merely <em>is</em> low would emit on every settlement,
   * and a banner that never stops showing is a banner nobody reads — the signal has to be the
   * crossing, not the state.
   *
   * <p>The threshold is a percentage of the actor's own monthly grant rather than an absolute
   * figure, because "low" for a free plan and for an ultimate one are different numbers, and one
   * constant would either spam the first or never fire for the second.
   */
  private void warnIfBalanceFellLow(String actorId, WalletSnapshot before, long debited) {
    int percent = configurationService.lowBalancePercent();
    Long grant =
        jdbcTemplate
            .query(
                "SELECT p.monthly_credit_grant FROM billing_subscription s"
                    + " JOIN billing_plan p ON p.plan_key = s.plan_key"
                    + " WHERE s.actor_id = ? AND s.status IN ('TRIALING','ACTIVE','PAST_DUE')",
                (rs, rowNum) -> rs.getLong(1),
                actorId)
            .stream()
            .findFirst()
            .orElse(null);
    if (grant == null || grant <= 0) {
      // No plan, no grant, no meaningful percentage to be a fraction of.
      return;
    }

    long threshold = grant * percent / 100;
    long availableBefore = before.available();
    long availableAfter = availableBefore - debited;
    if (availableBefore > threshold && availableAfter <= threshold) {
      outboxService.append(
          "WALLET",
          actorId,
          "evt.wallet.low-balance",
          new LowBalanceEvent(actorId, availableAfter, percent, java.time.Instant.now()));
      log.info(
          "Actor {} fell below {}% of their grant ({} left)", actorId, percent, availableAfter);
    }
  }

  private void ensureWallet(String actorId) {
    jdbcTemplate.update(
        "INSERT INTO credit_wallet (actor_id) VALUES (?) ON CONFLICT (actor_id) DO NOTHING",
        actorId);
  }

  private WalletSnapshot requireWallet(String actorId) {
    return wallet(actorId)
        .orElseThrow(() -> new IllegalStateException("wallet missing for actor " + actorId));
  }

  private WalletSnapshot lockWallet(String actorId) {
    return jdbcTemplate
        .query(
            "SELECT actor_id, balance_granted, balance_purchased, held"
                + " FROM credit_wallet WHERE actor_id = ? FOR UPDATE",
            (rs, rowNum) ->
                new WalletSnapshot(
                    rs.getString("actor_id"),
                    rs.getLong("balance_granted"),
                    rs.getLong("balance_purchased"),
                    rs.getLong("held")),
            actorId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("wallet missing for actor " + actorId));
  }

  private UUID insertHold(CreditHoldCommand command, long estimate, int pricebookVersion) {
    UUID holdId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO credit_hold (id, actor_id, estimated_credits, status, capability, model_name,"
            + " pricebook_version, correlation_id, job_id, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(secs => ?))",
        holdId,
        command.actorId(),
        estimate,
        HoldStatus.ACTIVE.name(),
        command.capability().name(),
        command.modelName(),
        pricebookVersion,
        command.correlationId(),
        command.jobId(),
        (double) configurationService.holdTtlSeconds());
    return holdId;
  }

  private Optional<CreditHold> findHold(String holdId) {
    UUID id;
    try {
      id = UUID.fromString(holdId);
    } catch (IllegalArgumentException ignored) {
      // A synthetic id (billing disabled) is not a hold — settling it is a no-op, not an error.
      return Optional.empty();
    }
    return jdbcTemplate
        .query(
            "SELECT id, actor_id, estimated_credits, status, capability, model_name,"
                + " pricebook_version FROM credit_hold WHERE id = ?",
            (rs, rowNum) ->
                new CreditHold(
                    rs.getObject("id", UUID.class),
                    rs.getString("actor_id"),
                    rs.getLong("estimated_credits"),
                    HoldStatus.valueOf(rs.getString("status")),
                    BillableCapability.valueOf(rs.getString("capability")),
                    rs.getString("model_name"),
                    rs.getInt("pricebook_version")),
            id)
        .stream()
        .findFirst();
  }
}
