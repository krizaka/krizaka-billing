package com.orazaka.billingservice.application.service;

import com.orazaka.billingservice.domain.model.CatalogPack;
import com.orazaka.billingservice.domain.model.PackSubscriptionChangedEvent;
import com.orazaka.billingservice.domain.model.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns which packs an actor holds — the actor↔pack link the design's "plan ∪ pack" rule needs
 * (§14).
 *
 * <p>Separate from {@link SubscriptionService} rather than folded into it, because the cardinality
 * differs and the database enforces that difference: an actor has exactly one plan and any number
 * of packs, so the two partial unique indexes are {@code (actor_id)} and {@code (actor_id,
 * pack_key)}. Sharing one table would mean giving up the plan's index, which is the only thing
 * stopping an actor from holding two plans at once.
 *
 * <p><b>No payment is captured here</b>, exactly as {@link SubscriptionService#changePlan} captures
 * none: the PSP and Lago are deferred (design §14, local phase). Subscribing records the
 * entitlement grant and credits the bundled credits; wiring it behind a real checkout replaces the
 * caller, not this service.
 *
 * <p>Every mutation announces itself through the outbox in the same transaction as the write. The
 * entitlement gate caches snapshots, so without the event a purchase takes up to a TTL to unlock
 * what the user just bought.
 */
@Service
public class PackSubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(PackSubscriptionService.class);

  private static final String EVENT_AGGREGATE = "PACK_SUBSCRIPTION";

  private static final String LIVE_STATUSES = "('TRIALING','ACTIVE','PAST_DUE')";

  private static final String SELECT_COLUMNS =
      "SELECT id, actor_id, pack_key, status, subscribed_at, period_end";

  private final JdbcTemplate jdbcTemplate;
  private final PackPricingService packPricingService;
  private final CreditLedgerService creditLedgerService;
  private final OutboxService outboxService;

  public PackSubscriptionService(
      JdbcTemplate jdbcTemplate,
      PackPricingService packPricingService,
      CreditLedgerService creditLedgerService,
      OutboxService outboxService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.packPricingService =
        Objects.requireNonNull(packPricingService, "PackPricingService cannot be null");
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
  }

  /**
   * Every pack an actor currently holds.
   *
   * @param actorId the opaque billable subject
   * @return their live pack subscriptions, most recent first
   */
  public List<PackSubscriptionView> forActor(String actorId) {
    requireText(actorId, "actorId");
    return jdbcTemplate.query(
        SELECT_COLUMNS
            + " FROM billing_pack_subscription WHERE actor_id = ? AND status IN "
            + LIVE_STATUSES
            + " ORDER BY subscribed_at DESC",
        viewMapper(),
        actorId);
  }

  /**
   * Every live holder of a pack — what an admin checks before withdrawing one.
   *
   * @param packKey the pack
   * @return its live subscribers, longest-standing first
   */
  public List<PackSubscriptionView> subscribersOf(String packKey) {
    requireText(packKey, "packKey");
    return jdbcTemplate.query(
        SELECT_COLUMNS
            + " FROM billing_pack_subscription WHERE pack_key = ? AND status IN "
            + LIVE_STATUSES
            + " ORDER BY subscribed_at",
        viewMapper(),
        packKey);
  }

  /**
   * Adds a pack to an actor.
   *
   * <p>Idempotent by the database rather than by a read-before-write (ERR-109): a second subscribe
   * hits the partial unique index and returns the subscription already in force, so a
   * double-submitted button cannot charge twice or duplicate the credit grant.
   *
   * <p>A withdrawn pack is refused. Withdrawing is how an admin takes an offer off the shelf, and a
   * catalogue that still sells what it stopped offering makes that action meaningless — existing
   * holders keep theirs, which is a different question and is handled by not revoking on withdraw.
   *
   * @param actorId the opaque billable subject
   * @param packKey the pack to add
   * @return the subscription now in force
   * @throws NoSuchElementException when no such pack exists
   * @throws IllegalStateException when the pack is withdrawn from sale
   */
  @Transactional
  public PackSubscriptionView subscribe(String actorId, String packKey) {
    requireText(actorId, "actorId");
    requireText(packKey, "packKey");

    CatalogPack pack =
        packPricingService
            .find(packKey)
            .orElseThrow(() -> new NoSuchElementException("no such pack: " + packKey));
    if (!pack.isActive()) {
      throw new IllegalStateException("pack %s is withdrawn from sale".formatted(packKey));
    }

    Optional<PackSubscriptionView> existing = findLive(actorId, packKey);
    if (existing.isPresent()) {
      return existing.get();
    }

    UUID id = UUID.randomUUID();
    Instant subscribedAt = Instant.now();
    try {
      jdbcTemplate.update(
          "INSERT INTO billing_pack_subscription (id, actor_id, pack_key, status, subscribed_at)"
              + " VALUES (?, ?, ?, ?, ?)",
          id,
          actorId,
          packKey,
          SubscriptionStatus.ACTIVE.name(),
          java.sql.Timestamp.from(subscribedAt));
    } catch (DuplicateKeyException raced) {
      // Two concurrent subscribes: the index picked a winner, so return what it wrote rather
      // than failing a user whose pack is now correctly in force.
      log.info("Concurrent subscribe of {} to pack {} — returning the live row", actorId, packKey);
      return findLive(actorId, packKey).orElseThrow(() -> raced);
    }

    if (pack.includedCredits() > 0) {
      // Keyed on the subscription id: a replay of this purchase can only come from this row,
      // so the ledger's unique index makes the grant exactly-once.
      creditLedgerService.purchase(
          actorId,
          pack.includedCredits(),
          EVENT_AGGREGATE,
          id.toString(),
          "pack:" + id,
          "credits included with pack " + packKey);
    }

    PackSubscriptionView view =
        new PackSubscriptionView(
            id, actorId, packKey, SubscriptionStatus.ACTIVE, subscribedAt, null);
    announce("evt.pack.subscribed", view);
    log.info("Actor {} subscribed to pack {}", actorId, packKey);
    return view;
  }

  /**
   * Removes a pack from an actor.
   *
   * <p>Cancels rather than deletes: the row is the record of what the actor held and when, and the
   * ledger entry for its bundled credits references it. Included credits already granted are not
   * clawed back — they were bought.
   *
   * @param actorId the opaque billable subject
   * @param packKey the pack to remove
   * @return the subscription's final state, empty when the actor did not hold it
   */
  @Transactional
  public Optional<PackSubscriptionView> cancel(String actorId, String packKey) {
    requireText(actorId, "actorId");
    requireText(packKey, "packKey");

    Optional<PackSubscriptionView> live = findLive(actorId, packKey);
    if (live.isEmpty()) {
      return Optional.empty();
    }

    jdbcTemplate.update(
        "UPDATE billing_pack_subscription SET status = ?, updated_at = now()"
            + " WHERE id = ? AND status IN "
            + LIVE_STATUSES,
        SubscriptionStatus.CANCELED.name(),
        live.get().id());

    PackSubscriptionView canceled = live.get().asCanceled();
    announce("evt.pack.canceled", canceled);
    log.info("Actor {} gave up pack {}", actorId, packKey);
    return Optional.of(canceled);
  }

  private Optional<PackSubscriptionView> findLive(String actorId, String packKey) {
    return jdbcTemplate
        .query(
            SELECT_COLUMNS
                + " FROM billing_pack_subscription WHERE actor_id = ? AND pack_key = ?"
                + "   AND status IN "
                + LIVE_STATUSES,
            viewMapper(),
            actorId,
            packKey)
        .stream()
        .findFirst();
  }

  /**
   * Same transaction as the write: the change and its announcement commit or roll back together.
   */
  private void announce(String routingKey, PackSubscriptionView view) {
    outboxService.append(
        EVENT_AGGREGATE,
        view.actorId(),
        routingKey,
        new PackSubscriptionChangedEvent(
            view.actorId(), view.packKey(), view.status(), view.periodEnd(), Instant.now()));
  }

  private static RowMapper<PackSubscriptionView> viewMapper() {
    return (rs, rowNum) ->
        new PackSubscriptionView(
            rs.getObject("id", UUID.class),
            rs.getString("actor_id"),
            rs.getString("pack_key"),
            SubscriptionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("subscribed_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("period_end"))
                .map(java.sql.Timestamp::toInstant)
                .orElse(null));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  /**
   * One pack an actor holds, as the console and the marketplace see it.
   *
   * @param id the subscription's identifier — the idempotency anchor of its credit grant
   * @param actorId the opaque billable subject
   * @param packKey the pack held
   * @param status the subscription's status
   * @param subscribedAt when it was taken
   * @param periodEnd when it lapses, or {@code null} for a perpetual purchase
   */
  public record PackSubscriptionView(
      UUID id,
      String actorId,
      String packKey,
      SubscriptionStatus status,
      Instant subscribedAt,
      Instant periodEnd) {

    /**
     * @return this subscription as it looks once given up
     */
    PackSubscriptionView asCanceled() {
      return new PackSubscriptionView(
          id, actorId, packKey, SubscriptionStatus.CANCELED, subscribedAt, periodEnd);
    }
  }
}
