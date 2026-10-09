package com.krizaka.billing.service.domain.model;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A resolved pricebook row: what this capability × model costs, in which unit, at which version.
 *
 * <p>A hold pins {@link #version()} so that a price published mid-flight cannot corrupt the
 * settlement of work that was authorised under the old rate.
 *
 * @param version the pricebook version this rate belongs to
 * @param capability the capability priced
 * @param modelName the model priced, or {@code null} for the capability default
 * @param unit the unit {@code creditsPerUnit} is expressed in
 * @param creditsPerUnit credits charged per unit of measured consumption
 * @param minimumCredits floor applied after conversion — no free ride on tiny requests
 * @param estimateCredits what a hold reserves before execution
 */
public record PricebookRate(
    int version,
    BillableCapability capability,
    String modelName,
    BillableUnit unit,
    BigDecimal creditsPerUnit,
    long minimumCredits,
    long estimateCredits) {

  /** Compact canonical constructor enforcing the row's invariants (ERR-106). */
  public PricebookRate {
    if (version < 1) {
      throw new IllegalArgumentException("pricebook version must be >= 1");
    }
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(creditsPerUnit, "creditsPerUnit must not be null");
    if (creditsPerUnit.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("creditsPerUnit must be >= 0");
    }
    if (minimumCredits < 0 || estimateCredits < 0) {
      throw new IllegalArgumentException("minimumCredits and estimateCredits must be >= 0");
    }
  }

  /**
   * Converts a measured quantity into credits.
   *
   * <p>Rounds <b>up</b>: a partial unit is a unit consumed, and rounding consumption down is a
   * systematic giveaway that only shows up as a margin hole months later. The floor is then
   * applied, so a tiny request still costs {@link #minimumCredits()}.
   *
   * @param quantity the measured consumption in {@link #unit()}
   * @return the credits to debit, never below the floor
   */
  public long creditsFor(BigDecimal quantity) {
    Objects.requireNonNull(quantity, "quantity must not be null");
    long converted =
        quantity.multiply(creditsPerUnit).setScale(0, RoundingMode.CEILING).longValue();
    return Math.max(converted, minimumCredits);
  }
}
