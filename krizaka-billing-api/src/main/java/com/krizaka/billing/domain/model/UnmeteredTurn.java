package com.krizaka.billing.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A unit of work served without a credit hold because billing could not be reached (ADR-064).
 *
 * <p><b>Why it exists.</b> The credit gate fails open: a billing outage must not become a service
 * outage. Free inference that nobody can find afterwards is a different product from free inference
 * that can be reconciled, and only the second was chosen. This record is what makes it the second —
 * who was served, under which correlation, for which capability, when, and why no hold was taken.
 *
 * <p>No quantity: the gate records before the work runs, and what the work consumed is settled
 * wherever it is measured. The correlation is what joins the two.
 *
 * @param actorId the opaque billable subject that was served
 * @param capability the capability served
 * @param correlationId the conversation or job that ties this turn to what it produced
 * @param reason why no hold was taken — a failure's type, never its message, which may carry
 *     content
 * @param occurredAt when the hold was attempted
 */
public record UnmeteredTurn(
    String actorId,
    BillableCapability capability,
    String correlationId,
    String reason,
    Instant occurredAt) {

  /**
   * Routing key on {@code orazaka.events} — {@code evt.{aggregate}.{type}} (AGENTS.md §6). A wire
   * key two contexts must agree on, which is why it lives in the contract.
   */
  public static final String ROUTING_KEY = "evt.turn.unmetered";

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public UnmeteredTurn {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    Objects.requireNonNull(capability, "capability must not be null");
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }
}
