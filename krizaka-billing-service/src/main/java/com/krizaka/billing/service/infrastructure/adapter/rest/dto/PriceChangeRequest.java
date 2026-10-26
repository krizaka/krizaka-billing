package com.krizaka.billing.service.infrastructure.adapter.rest.dto;

import com.krizaka.billing.service.domain.model.PriceAvailability;
import com.krizaka.billing.service.domain.model.PriceChange;
import com.krizaka.billing.service.domain.model.PriceUnit;
import java.time.Instant;
import java.util.Map;

/**
 * A price change submitted by the admin console.
 *
 * @param unit month, year, once, document, order, pack or hour
 * @param amountCents cents, or {@code null} for on quote
 * @param availability public, internal, founder-limited or preparing
 * @param limits named integer limits
 * @param effectiveFrom when the change starts; never in the past
 */
public record PriceChangeRequest(
    String unit,
    Long amountCents,
    String availability,
    Map<String, Long> limits,
    Instant effectiveFrom) {

  /**
   * The change to schedule.
   *
   * @return the domain change
   */
  public PriceChange toChange() {
    return new PriceChange(
        PriceUnit.fromWire(unit), amountCents, PriceAvailability.fromWire(availability), limits);
  }
}
