package com.orazaka.billing.domain.model;

import java.util.Objects;

/**
 * Request to reserve an estimated cost before execution starts — the first phase of the hold/settle
 * protocol (ADR-033).
 *
 * <p>Authorisation must precede execution: once an MLX video job starts the cost is already sunk,
 * so no compute begins without a hold.
 *
 * @param actorId the opaque billable subject (a user id today, possibly an organisation id later)
 * @param capability the capability about to be executed
 * @param modelName the resolved model, or {@code null} to price against the capability default
 * @param correlationId the originating {@code Intention.id}, tying every hold of one agent run
 *     together
 * @param jobId the async job this hold belongs to, or {@code null} on the synchronous chat path
 * @param estimatedCredits the credits to reserve, as priced by the current pricebook
 */
public record CreditHoldCommand(
    String actorId,
    BillableCapability capability,
    String modelName,
    String correlationId,
    String jobId,
    long estimatedCredits) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public CreditHoldCommand {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    Objects.requireNonNull(capability, "capability must not be null");
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0");
    }
  }
}
