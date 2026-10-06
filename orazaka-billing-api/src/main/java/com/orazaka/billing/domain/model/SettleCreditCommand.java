package com.orazaka.billing.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Closes a hold against measured reality — the second phase of the hold/settle protocol (ADR-033).
 *
 * <p>The estimate is never the truth: a chat turn's token count is unknown before generation and a
 * video's real cost depends on the frames actually rendered, so settlement carries the quantity
 * that was actually consumed.
 *
 * @param holdId the reservation being closed
 * @param unit the unit {@code quantity} is expressed in
 * @param quantity the measured consumption, never negative
 * @param idempotencyKey the caller's replay guard (typically the AMQP {@code messageId}) — a
 *     redelivered settlement collides on the ledger's unique key rather than double-debiting
 */
public record SettleCreditCommand(
    String holdId, BillableUnit unit, BigDecimal quantity, String idempotencyKey) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public SettleCreditCommand {
    if (holdId == null || holdId.isBlank()) {
      throw new IllegalArgumentException("holdId must not be blank");
    }
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(quantity, "quantity must not be null");
    if (quantity.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("quantity must be >= 0");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
  }
}
