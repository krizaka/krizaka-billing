package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

import com.orazaka.billing.domain.model.BillableUnit;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Body of {@code POST /api/v1/billing/credits/{holdId}/settle}. The hold is the path variable, so
 * it is deliberately absent here rather than duplicated and cross-checked.
 *
 * @param unit the unit {@code quantity} is expressed in
 * @param quantity the measured consumption
 * @param idempotencyKey the caller's replay guard, typically the AMQP {@code messageId}
 */
public record SettleRequest(BillableUnit unit, BigDecimal quantity, String idempotencyKey) {

  /** Compact canonical constructor — the boundary validates, the service trusts (ERR-106/116). */
  public SettleRequest {
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
