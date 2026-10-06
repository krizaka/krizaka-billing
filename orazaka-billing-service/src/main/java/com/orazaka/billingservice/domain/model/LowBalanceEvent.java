package com.orazaka.billingservice.domain.model;

import java.time.Instant;

/**
 * Warns that an actor is running out of credits ({@code evt.wallet.low-balance}).
 *
 * <p>Emitted on the way down only — crossing the threshold, not sitting below it. A wallet that
 * stayed under 10% would otherwise emit on every settlement, and a banner that never stops showing
 * is a banner nobody reads.
 *
 * @param actorId the opaque billable subject
 * @param available what they have left
 * @param thresholdPercent the configured threshold they fell through
 * @param occurredAt when it happened
 */
public record LowBalanceEvent(
    String actorId, long available, int thresholdPercent, Instant occurredAt) {}
