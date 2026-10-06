package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

import com.orazaka.billing.domain.model.MeteredStep;
import java.util.List;

/**
 * Request body of the aggregate-settlement endpoint.
 *
 * <p>The many-step twin of {@code MeasuredSettleRequest}, and it carries the pricebook KEY beside
 * each report where the single-step form does not have to. One job is one {@code (capability,
 * model)}, so the hold already names it; a run is several, and the hold names only the
 * orchestration itself (ADR-041).
 *
 * @param steps what each step measured and what prices it; empty means release rather than settle
 * @param idempotencyKey the replay guard, unique per settlement attempt
 */
public record AggregateSettleRequest(List<MeteredStep> steps, String idempotencyKey) {

  /** Compact canonical constructor enforcing the request's invariants (ERR-106). */
  public AggregateSettleRequest {
    steps = steps == null ? List.of() : List.copyOf(steps);
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
  }
}
