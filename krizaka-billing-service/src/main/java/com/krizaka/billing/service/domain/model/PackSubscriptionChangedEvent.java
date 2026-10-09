package com.krizaka.billing.service.domain.model;

import java.time.Instant;

/**
 * Announces that an actor gained or lost a pack ({@code evt.pack.*}).
 *
 * <p>The same reason {@link SubscriptionChangedEvent} exists, for the other half of the entitlement
 * union: consumers cache entitlement snapshots to keep the authorisation gate off the network, and
 * a pack now contributes keys to that snapshot. Without this event the only bound on a purchase
 * taking effect is the snapshot's own TTL — a user who has just paid for a Studio watching it stay
 * locked for another minute, which is the single worst moment to be slow.
 *
 * @param actorId the opaque billable subject
 * @param packKey the pack that was gained or lost
 * @param status the subscription's new status
 * @param periodEnd when it lapses, or {@code null} for a perpetual purchase
 * @param occurredAt when the change was applied
 */
public record PackSubscriptionChangedEvent(
    String actorId,
    String packKey,
    SubscriptionStatus status,
    Instant periodEnd,
    Instant occurredAt) {}
