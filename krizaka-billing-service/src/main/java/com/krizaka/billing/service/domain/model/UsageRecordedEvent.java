package com.krizaka.billing.service.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Domain event emitted when measured consumption has been debited — the payload of {@code
 * evt.usage.recorded}.
 *
 * <p>Published through the transactional outbox rather than directly: the external billing engine
 * must never sit on a user's critical path, and if it is down the events queue and drain on
 * recovery. That availability property is the whole reason the ledger is local.
 *
 * @param actorId the opaque billable subject
 * @param capability the capability consumed
 * @param modelName the model used, or {@code null} for the capability default
 * @param unit the unit {@code quantity} is expressed in
 * @param quantity the measured consumption
 * @param creditsCharged the credits actually debited
 * @param holdId the reservation this settled
 * @param correlationId the originating intention, for roll-up across an agent run
 */
public record UsageRecordedEvent(
    String actorId,
    String capability,
    String modelName,
    String unit,
    BigDecimal quantity,
    long creditsCharged,
    String holdId,
    String correlationId) {

  /** Compact canonical constructor enforcing the event's invariants (ERR-106). */
  public UsageRecordedEvent {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(quantity, "quantity must not be null");
    if (creditsCharged < 0) {
      throw new IllegalArgumentException("creditsCharged must be >= 0");
    }
  }
}
