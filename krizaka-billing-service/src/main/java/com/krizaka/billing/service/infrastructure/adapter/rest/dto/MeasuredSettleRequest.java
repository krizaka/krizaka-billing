package com.krizaka.billing.service.infrastructure.adapter.rest.dto;

import com.krizaka.billing.domain.model.ConsumptionReport;
import java.util.Objects;

/**
 * Request body of the measured-settlement endpoint.
 *
 * <p>Carries raw measurements rather than a billable unit: the unit belongs to the pricebook row
 * the hold was pinned to and is resolved server-side, so an executor never encodes a pricing
 * decision it does not own.
 *
 * @param consumption what the executor measured
 * @param idempotencyKey the replay guard, unique per settlement attempt
 */
public record MeasuredSettleRequest(ConsumptionReport consumption, String idempotencyKey) {

  /** Compact canonical constructor enforcing the request's invariants (ERR-106). */
  public MeasuredSettleRequest {
    Objects.requireNonNull(consumption, "consumption must not be null");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
  }
}
