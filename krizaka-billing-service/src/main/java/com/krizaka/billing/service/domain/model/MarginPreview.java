package com.krizaka.billing.service.domain.model;

import com.krizaka.billing.domain.model.BillableCapability;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * What a proposed rate would have charged, replayed against real traffic.
 *
 * <p>The design's non-negotiable for the pricebook screen: never let a price be published without
 * showing what it would have cost the last 30 days (§12). A rate is otherwise a number typed into a
 * form, and the first evidence that it was wrong arrives as a month of mispriced invoices.
 *
 * @param capability the capability being repriced
 * @param modelName the model being repriced, or {@code null} for the capability default
 * @param sampleEvents how many usage events the replay covered — a preview over three events is not
 *     evidence, so the count travels with the verdict
 * @param currentCredits what the sampled traffic actually cost under the live rate
 * @param proposedCredits what the same traffic would have cost under the proposed rate
 */
public record MarginPreview(
    BillableCapability capability,
    String modelName,
    long sampleEvents,
    long currentCredits,
    long proposedCredits) {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Compact canonical constructor enforcing the preview's invariants (ERR-106). */
  public MarginPreview {
    if (sampleEvents < 0 || currentCredits < 0 || proposedCredits < 0) {
      throw new IllegalArgumentException("preview totals must be >= 0");
    }
  }

  /**
   * The change the publication would have made to revenue over the sample.
   *
   * @return credits gained (positive) or given up (negative)
   */
  public long deltaCredits() {
    return proposedCredits - currentCredits;
  }

  /**
   * The same change as a percentage, which is what makes it legible at a glance.
   *
   * @return the percentage change, or zero when the sample charged nothing to compare against
   */
  public BigDecimal deltaPercent() {
    if (currentCredits == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(deltaCredits())
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(currentCredits), MathContext.DECIMAL64)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Whether the replay rests on enough traffic to mean anything.
   *
   * @return {@code false} when no usage was sampled — the admin is publishing blind, which is
   *     allowed but must not look the same as a preview backed by data
   */
  public boolean hasEvidence() {
    return sampleEvents > 0;
  }
}
