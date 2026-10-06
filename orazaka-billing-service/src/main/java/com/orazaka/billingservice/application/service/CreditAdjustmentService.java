package com.orazaka.billingservice.application.service;

import com.orazaka.billingservice.domain.exception.AdjustmentCeilingExceededException;
import com.orazaka.billingservice.domain.model.CreditBucket;
import com.orazaka.billingservice.domain.model.CreditGrantedEvent;
import com.orazaka.billingservice.domain.model.LedgerEntryType;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write that moves credits without a hold (design §12.1).
 *
 * <p>Support has to be able to hand credits back for a bad generation and to claw them back after
 * an abuse or a duplicate top-up. That is a legitimate need and a standing fraud surface, so this
 * path's guardrails are <b>stricter</b> than the metering protocol's rather than looser:
 *
 * <ul>
 *   <li><b>The reason is mandatory.</b> An unexplained balance change is indistinguishable from
 *       theft six months later, when the person who made it has left.
 *   <li><b>The admin is named</b>, never {@code system}. Attribution is the whole point of a manual
 *       path existing at all.
 *   <li><b>The amount is signed</b>, so a claw-back is the same operation as a grant — one code
 *       path, one audit trail, no "revoke" variant to forget to guard.
 *   <li><b>The bucket is explicit.</b> Refunding into {@code PURCHASED} hands out credits that
 *       never expire; defaulting it silently is how that happens by accident.
 *   <li><b>A per-admin daily ceiling</b> bounds what a compromised account can mint.
 * </ul>
 *
 * <p>The ledger stays append-only: an adjustment is a new entry, never a correction of an old one.
 * Undoing one means adding its opposite, which leaves both visible — the record of what happened
 * includes the mistake.
 */
@Service
public class CreditAdjustmentService {

  private static final Logger log = LoggerFactory.getLogger(CreditAdjustmentService.class);

  private static final String REFERENCE_TYPE = "ADMIN";

  private final JdbcTemplate jdbcTemplate;
  private final BillingConfigurationService configurationService;
  private final OutboxService outboxService;

  public CreditAdjustmentService(
      JdbcTemplate jdbcTemplate,
      BillingConfigurationService configurationService,
      OutboxService outboxService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.configurationService =
        Objects.requireNonNull(configurationService, "BillingConfigurationService cannot be null");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
  }

  /**
   * Applies a manual credit adjustment.
   *
   * @param actorId the opaque billable subject whose balance moves
   * @param bucket which balance moves — chosen explicitly, never defaulted
   * @param amount signed credits: positive grants, negative claws back
   * @param reason why, mandatory and stored on the ledger entry
   * @param adminId the admin performing it, recorded as {@code created_by}
   * @return the wallet after the adjustment
   * @throws AdjustmentCeilingExceededException when it would pass the admin's daily ceiling
   * @throws IllegalArgumentException when the reason is missing, the amount is zero, or a claw-back
   *     exceeds the balance it would take from
   */
  @Transactional
  public WalletSnapshot adjust(
      String actorId, CreditBucket bucket, long amount, String reason, String adminId) {
    requireText(actorId, "actorId");
    requireText(reason, "reason");
    requireText(adminId, "adminId");
    Objects.requireNonNull(bucket, "bucket must be chosen explicitly");
    if (amount == 0) {
      // A zero adjustment is a ledger entry that says nothing while looking like diligence.
      throw new IllegalArgumentException("amount must not be zero");
    }

    enforceDailyCeiling(adminId, Math.abs(amount));
    ensureWallet(actorId);

    // The affordability test is the WHERE clause, so a claw-back cannot race a concurrent debit
    // into a negative balance — the same discipline the hold uses (ERR-109).
    String column = bucket == CreditBucket.GRANTED ? "balance_granted" : "balance_purchased";
    int moved =
        jdbcTemplate.update(
            "UPDATE credit_wallet SET "
                + column
                + " = "
                + column
                + " + ?, version = version + 1, updated_at = now()"
                + " WHERE actor_id = ? AND "
                + column
                + " + ? >= 0",
            amount,
            actorId,
            amount);
    if (moved != 1) {
      throw new IllegalArgumentException(
          "Adjustment of %d would take %s's %s balance negative"
              .formatted(amount, actorId, bucket));
    }

    WalletSnapshot after = requireWallet(actorId);
    long balanceAfter =
        bucket == CreditBucket.GRANTED ? after.balanceGranted() : after.balancePurchased();
    appendEntry(actorId, bucket, amount, balanceAfter, reason, adminId);

    outboxService.append(
        "WALLET",
        actorId,
        "evt.credit.granted",
        new CreditGrantedEvent(
            actorId, bucket.name(), amount, balanceAfter, reason, adminId, Instant.now()));

    log.info(
        "Admin {} adjusted {} by {} credits in {} — {}", adminId, actorId, amount, bucket, reason);
    return after;
  }

  /**
   * How much an admin has already moved today, in absolute credits.
   *
   * @param adminId the admin
   * @return the sum of the absolute amounts of today's adjustments
   */
  public long adjustedTodayBy(String adminId) {
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(ABS(amount)), 0) FROM credit_ledger_entry"
                + " WHERE created_by = ? AND entry_type = ?"
                + "   AND created_at >= date_trunc('day', now())",
            Long.class,
            adminId,
            LedgerEntryType.ADJUSTMENT.name());
    return total == null ? 0 : total;
  }

  /**
   * Refuses an adjustment that would pass the ceiling.
   *
   * <p>Counts the absolute amount, so an admin cannot alternate grants and claw-backs to keep a
   * running total near zero while moving unbounded credit in both directions.
   */
  private void enforceDailyCeiling(String adminId, long absoluteAmount) {
    long dailyMax = configurationService.adjustmentDailyMaxCredits();
    long already = adjustedTodayBy(adminId);
    if (already + absoluteAmount > dailyMax) {
      throw new AdjustmentCeilingExceededException(adminId, absoluteAmount, already, dailyMax);
    }
  }

  private void appendEntry(
      String actorId,
      CreditBucket bucket,
      long amount,
      long balanceAfter,
      String reason,
      String adminId) {
    try {
      jdbcTemplate.update(
          "INSERT INTO credit_ledger_entry (actor_id, entry_type, bucket, amount, balance_after,"
              + " reference_type, reference_id, idempotency_key, reason, created_by)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          actorId,
          LedgerEntryType.ADJUSTMENT.name(),
          bucket.name(),
          amount,
          balanceAfter,
          REFERENCE_TYPE,
          adminId,
          "adjustment:" + UUID.randomUUID(),
          reason,
          adminId);
    } catch (DataIntegrityViolationException e) {
      // The unique idempotency key is generated here, so a collision is not a replay — it is a
      // broken assumption, and swallowing it would leave money moved with no ledger entry.
      throw new IllegalStateException("Adjustment ledger entry could not be recorded", e);
    }
  }

  private void ensureWallet(String actorId) {
    jdbcTemplate.update(
        "INSERT INTO credit_wallet (actor_id) VALUES (?) ON CONFLICT (actor_id) DO NOTHING",
        actorId);
  }

  private WalletSnapshot requireWallet(String actorId) {
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
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("wallet missing for actor " + actorId));
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
