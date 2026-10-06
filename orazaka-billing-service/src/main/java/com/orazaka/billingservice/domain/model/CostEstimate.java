package com.orazaka.billingservice.domain.model;

import com.orazaka.billing.domain.model.BillableCapability;
import java.util.Objects;

/**
 * What an action will cost, answered before the user commits to it.
 *
 * <p>The design is explicit that showing the price before the click is not a UI nicety (§12): an
 * unexpected debit on a job the user did not know was expensive is the number-one support ticket in
 * credit products. The number here is the pricebook's own estimate — the same one the hold will
 * reserve — so what the user is shown and what they are charged cannot disagree.
 *
 * @param capability the capability priced
 * @param modelName the model the rate resolved to, or {@code null} for the capability default
 * @param estimateCredits what a hold would reserve
 * @param availableCredits what the actor can currently cover
 * @param affordable whether they can cover it — computed here rather than client-side, so the
 *     paywall and the ledger cannot disagree about what "enough" means
 */
public record CostEstimate(
    BillableCapability capability,
    String modelName,
    long estimateCredits,
    long availableCredits,
    boolean affordable) {

  /** Compact canonical constructor enforcing the estimate's invariants (ERR-106). */
  public CostEstimate {
    Objects.requireNonNull(capability, "capability must not be null");
    if (estimateCredits < 0) {
      throw new IllegalArgumentException("estimateCredits must be >= 0");
    }
  }
}
