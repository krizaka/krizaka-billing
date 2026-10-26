package com.krizaka.billing.service.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * A change to schedule: the next version of a price, from a date that is not in the past.
 *
 * @param unit the unit
 * @param amountCents cents, {@code null} on quote
 * @param availability who may see it
 * @param limits named limits
 */
public record PriceChange(
    PriceUnit unit, Long amountCents, PriceAvailability availability, Map<String, Long> limits) {

  /**
   * Refuses a start before {@code now}: a price never changes retroactively.
   *
   * @param effectiveFrom the requested start
   * @param now the current instant
   */
  public static void requireNotInPast(Instant effectiveFrom, Instant now) {
    if (effectiveFrom == null || effectiveFrom.isBefore(now)) {
      throw new IllegalArgumentException("a price change cannot start in the past");
    }
  }
}
