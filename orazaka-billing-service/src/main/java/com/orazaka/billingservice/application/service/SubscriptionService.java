package com.orazaka.billingservice.application.service;

import com.orazaka.billingservice.domain.model.SubscriptionChangedEvent;
import com.orazaka.billingservice.domain.model.SubscriptionStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns an actor's commercial standing: which plan they are on, and until when.
 *
 * <p>Every change emits {@code evt.subscription.*} through the outbox, in the same transaction as
 * the write. The design states this as "never a direct DB write" (§12) and the reason is caching:
 * the entitlement gate holds snapshots so it does not pay a network hop per chat turn, and this
 * event is what bounds the staleness of a plan change to the broker's latency rather than to a TTL.
 * A downgrade that keeps granting video for a minute is a real cost; an upgrade that keeps refusing
 * it is a support ticket.
 *
 * <p>"One live subscription per actor" is the database's rule, not this class's: the partial unique
 * index on {@code (actor_id) WHERE status IN (live)} rejects a second one, so changing plan closes
 * the old row before opening the new one rather than reading first and hoping (ERR-109).
 */
@Service
public class SubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

  /** Default billing period. Calendar months differ in length; a fixed period keeps grants even. */
  private static final int PERIOD_DAYS = 30;

  private static final String EVENT_AGGREGATE = "SUBSCRIPTION";

  private final JdbcTemplate jdbcTemplate;
  private final OutboxService outboxService;

  public SubscriptionService(JdbcTemplate jdbcTemplate, OutboxService outboxService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
  }

  /**
   * The subscription in force for an actor.
   *
   * @param actorId the opaque billable subject
   * @return the live subscription, empty when the actor is on the implicit entry plan
   */
  public Optional<SubscriptionView> forActor(String actorId) {
    return jdbcTemplate
        .query(
            "SELECT actor_id, plan_key, status, period_start, period_end, cancel_at_period_end"
                + " FROM billing_subscription WHERE actor_id = ?"
                + "   AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
            viewMapper(),
            actorId)
        .stream()
        .findFirst();
  }

  /**
   * Moves an actor onto a plan, closing whatever they were on.
   *
   * <p>Used for an upgrade, a downgrade and a trial alike — they differ only in the status the
   * caller asks for, so they share one code path and therefore one audit trail.
   *
   * @param actorId the opaque billable subject
   * @param planKey the plan to move them onto
   * @param status the status to open the new subscription in
   * @return the subscription now in force
   */
  @Transactional
  public SubscriptionView changePlan(String actorId, String planKey, SubscriptionStatus status) {
    requireActor(actorId);
    Objects.requireNonNull(planKey, "planKey must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (!status.isLive()) {
      // Opening a subscription already dead would leave the actor with no live row and no event
      // explaining why — cancel() is the operation for ending one.
      throw new IllegalArgumentException("a new subscription must open in a live status");
    }

    closeLive(actorId, SubscriptionStatus.CANCELED);

    Instant periodStart = Instant.now();
    Instant periodEnd = periodStart.plus(PERIOD_DAYS, ChronoUnit.DAYS);
    jdbcTemplate.update(
        "INSERT INTO billing_subscription (actor_id, plan_key, status, period_start, period_end)"
            + " VALUES (?, ?, ?, ?, ?)",
        actorId,
        planKey,
        status.name(),
        java.sql.Timestamp.from(periodStart),
        java.sql.Timestamp.from(periodEnd));

    SubscriptionView view =
        new SubscriptionView(actorId, planKey, status, periodStart, periodEnd, false);
    announce("evt.subscription.changed", view);
    log.info("Actor {} moved to plan {} ({})", actorId, planKey, status);
    return view;
  }

  /**
   * Ends an actor's subscription.
   *
   * @param actorId the opaque billable subject
   * @param immediately {@code true} to cut access now, {@code false} to let the paid period run out
   * @return the subscription's final state, empty when the actor had none
   */
  @Transactional
  public Optional<SubscriptionView> cancel(String actorId, boolean immediately) {
    requireActor(actorId);
    Optional<SubscriptionView> live = forActor(actorId);
    if (live.isEmpty()) {
      return Optional.empty();
    }

    if (immediately) {
      closeLive(actorId, SubscriptionStatus.CANCELED);
      SubscriptionView canceled = live.get().asCanceled();
      announce("evt.subscription.canceled", canceled);
      return Optional.of(canceled);
    }

    // Keeps the plan's entitlements until period_end — the user paid for them.
    jdbcTemplate.update(
        "UPDATE billing_subscription SET cancel_at_period_end = TRUE, updated_at = now()"
            + " WHERE actor_id = ? AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
        actorId);
    SubscriptionView pending = live.get().asPendingCancellation();
    announce("evt.subscription.changed", pending);
    return Optional.of(pending);
  }

  /**
   * Rolls a subscription into its next period.
   *
   * <p>Honours a pending cancellation rather than silently renewing it, because the period boundary
   * is exactly where "cancel at period end" has to mean something.
   *
   * @param actorId the opaque billable subject
   * @return the subscription after rollover, empty when the actor had none
   */
  @Transactional
  public Optional<SubscriptionView> rollOver(String actorId) {
    requireActor(actorId);
    Optional<SubscriptionView> live = forActor(actorId);
    if (live.isEmpty()) {
      return Optional.empty();
    }
    SubscriptionView current = live.get();

    if (current.cancelAtPeriodEnd()) {
      closeLive(actorId, SubscriptionStatus.EXPIRED);
      SubscriptionView expired = current.asExpired();
      announce("evt.subscription.canceled", expired);
      return Optional.of(expired);
    }

    Instant periodStart = Instant.now();
    Instant periodEnd = periodStart.plus(PERIOD_DAYS, ChronoUnit.DAYS);
    jdbcTemplate.update(
        "UPDATE billing_subscription SET period_start = ?, period_end = ?, status = ?,"
            + " updated_at = now() WHERE actor_id = ?"
            + "   AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
        java.sql.Timestamp.from(periodStart),
        java.sql.Timestamp.from(periodEnd),
        SubscriptionStatus.ACTIVE.name(),
        actorId);

    SubscriptionView renewed = current.renewed(periodStart, periodEnd);
    announce("evt.subscription.renewed", renewed);
    return Optional.of(renewed);
  }

  /** Closes whatever live row the actor has, if any. Terminal statuses leave the index free. */
  private void closeLive(String actorId, SubscriptionStatus terminal) {
    jdbcTemplate.update(
        "UPDATE billing_subscription SET status = ?, updated_at = now() WHERE actor_id = ?"
            + "   AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
        terminal.name(),
        actorId);
  }

  /**
   * Same transaction as the write: the change and its announcement commit or roll back together.
   */
  private void announce(String routingKey, SubscriptionView view) {
    outboxService.append(
        EVENT_AGGREGATE,
        view.actorId(),
        routingKey,
        new SubscriptionChangedEvent(
            view.actorId(), view.planKey(), view.status(), view.periodEnd(), Instant.now()));
  }

  private static void requireActor(String actorId) {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
  }

  private static org.springframework.jdbc.core.RowMapper<SubscriptionView> viewMapper() {
    return (rs, rowNum) ->
        new SubscriptionView(
            rs.getString("actor_id"),
            rs.getString("plan_key"),
            SubscriptionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            rs.getBoolean("cancel_at_period_end"));
  }

  /**
   * An actor's commercial standing, as the admin console and the entitlement reader see it.
   *
   * @param actorId the opaque billable subject
   * @param planKey the plan in force
   * @param status the subscription's status
   * @param periodStart when the current period began
   * @param periodEnd when it ends — granted credits expire with it
   * @param cancelAtPeriodEnd whether it stops renewing at {@code periodEnd}
   */
  public record SubscriptionView(
      String actorId,
      String planKey,
      SubscriptionStatus status,
      Instant periodStart,
      Instant periodEnd,
      boolean cancelAtPeriodEnd) {

    /**
     * @return this subscription as it looks once cancelled outright
     */
    SubscriptionView asCanceled() {
      return new SubscriptionView(
          actorId, planKey, SubscriptionStatus.CANCELED, periodStart, periodEnd, true);
    }

    /**
     * @return this subscription as it looks once expired at a period boundary
     */
    SubscriptionView asExpired() {
      return new SubscriptionView(
          actorId, planKey, SubscriptionStatus.EXPIRED, periodStart, periodEnd, true);
    }

    /**
     * @return this subscription still live, but not renewing
     */
    SubscriptionView asPendingCancellation() {
      return new SubscriptionView(actorId, planKey, status, periodStart, periodEnd, true);
    }

    /**
     * @return this subscription rolled into a fresh period
     */
    SubscriptionView renewed(Instant newStart, Instant newEnd) {
      return new SubscriptionView(
          actorId, planKey, SubscriptionStatus.ACTIVE, newStart, newEnd, false);
    }
  }

  /**
   * Every subscription on a plan — the backing query for the admin's plan-impact view.
   *
   * @param planKey the plan
   * @return its live subscribers
   */
  public List<SubscriptionView> subscribersOf(String planKey) {
    return jdbcTemplate.query(
        "SELECT actor_id, plan_key, status, period_start, period_end, cancel_at_period_end"
            + " FROM billing_subscription WHERE plan_key = ?"
            + "   AND status IN ('TRIALING','ACTIVE','PAST_DUE') ORDER BY period_end",
        viewMapper(),
        planKey);
  }
}
