package com.krizaka.billing.service.infrastructure.adapter.amqp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.krizaka.billing.domain.model.ConsumptionReport;

/**
 * The terminal outcome of an async job, as billing reads it off {@code job.{id}.done|error}.
 *
 * <p>Anti-corruption layer (ERR-127): the broker payload is converted into a validated record at
 * the boundary so no {@code Map<String, Object>} reaches the ledger. Fields the producer does not
 * send are nullable by design — {@code holdId} is absent for every job submitted before billing
 * existed, and such a message is simply not billable rather than an error.
 *
 * <p>The event carries raw <b>measurements</b>, not a billable unit: which unit a job bills in
 * belongs to the pricebook row its hold was pinned to, and is resolved at settlement (see {@link
 * ConsumptionReport}). Two independent executors publish this event — the job service and the MLX
 * media worker — and neither should carry a copy of a pricing decision.
 *
 * <p>Tolerant reader, deliberately: the same event feeds the SSE relay, which reads keys billing
 * ignores ({@code result}, {@code status}). Declared here rather than left to a Jackson default so
 * a producer adding a field for another consumer can never dead-letter a settlement.
 *
 * @param jobId the job this outcome belongs to
 * @param holdId the reservation to settle or release, or {@code null} when the job was submitted
 *     without one
 * @param consumption what the executor measured, or {@code null} when it reported nothing
 * @param error the failure message when the job failed; its presence is what distinguishes a
 *     release from a settlement
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobOutcomeEvent(
    String jobId, String holdId, ConsumptionReport consumption, String error) {

  /**
   * Whether this outcome can move money at all.
   *
   * @return {@code true} only when a hold is attached — a job with no hold was never authorised
   *     through billing and must not be invented one at settlement time
   */
  public boolean isBillable() {
    return holdId != null && !holdId.isBlank();
  }

  /**
   * Whether the job failed.
   *
   * @return {@code true} when an error is present — a failed generation is released, never billed
   */
  public boolean failed() {
    return error != null && !error.isBlank();
  }
}
