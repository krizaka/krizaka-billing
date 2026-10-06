package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.BillableUnit;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A proposed credit rate, as the admin console submits it.
 *
 * <p>Both the preview and the publish endpoint read this shape, so what an admin previews is
 * literally what they publish — a preview computed from a different payload than the publication is
 * a preview of nothing.
 *
 * @param capability the capability priced
 * @param modelName the model priced, or {@code null} for the capability default
 * @param unit the unit the rate is expressed in
 * @param creditsPerUnit credits charged per unit of measured consumption
 * @param minimumCredits floor applied after conversion — no free ride on tiny requests
 * @param estimateCredits what a hold reserves before execution
 */
public record PricebookPublishRequest(
    BillableCapability capability,
    String modelName,
    BillableUnit unit,
    BigDecimal creditsPerUnit,
    long minimumCredits,
    long estimateCredits) {

  /** Compact canonical constructor enforcing the proposal's invariants (ERR-106). */
  public PricebookPublishRequest {
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(creditsPerUnit, "creditsPerUnit must not be null");
    if (creditsPerUnit.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("creditsPerUnit must be >= 0");
    }
    if (minimumCredits < 0 || estimateCredits < 0) {
      throw new IllegalArgumentException("minimumCredits and estimateCredits must be >= 0");
    }
    modelName = (modelName == null || modelName.isBlank()) ? null : modelName;
  }
}
