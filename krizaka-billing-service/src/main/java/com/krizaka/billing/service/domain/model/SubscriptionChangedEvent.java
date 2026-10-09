package com.krizaka.billing.service.domain.model;

import java.time.Instant;

/**
 * Announces that an actor's commercial standing changed ({@code evt.subscription.*}).
 *
 * <p>Consumers cache entitlement snapshots to keep a gate off the network on every chat turn; this
 * event is what bounds how long a plan change takes to bite. Without it the only bound is the
 * snapshot's own TTL, which means a downgrade keeps granting access it should not, and an upgrade
 * keeps refusing access the user has just paid for.
 *
 * @param actorId the opaque billable subject
 * @param planKey the plan now in force
 * @param status the subscription's new status
 * @param periodEnd when the current period ends — granted credits expire with it
 * @param occurredAt when the change was applied
 */
public record SubscriptionChangedEvent(
    String actorId,
    String planKey,
    SubscriptionStatus status,
    Instant periodEnd,
    Instant occurredAt) {}
