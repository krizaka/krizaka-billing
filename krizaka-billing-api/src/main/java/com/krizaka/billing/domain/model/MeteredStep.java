package com.krizaka.billing.domain.model;

import java.util.Objects;

/**
 * One step's consumption, with what produced it — the unit of an aggregate settlement.
 *
 * <p>A run crosses several capabilities whose work is priced in different units: a script step
 * bills KILOTOKEN, a still bills IMAGE_STEP, a clip bills OUTPUT_SECOND. {@link ConsumptionReport}
 * alone cannot be priced, because the unit is a property of the pricebook row {@code (capability,
 * model)} and not of the measurement — so an aggregate settlement has to carry the key beside each
 * report.
 *
 * <p><b>Why the pricing stays on this side of the boundary.</b> The alternative was for the caller
 * to price each report where it is measured and send one credit total. That would put a pricing
 * decision in a context that does not own the pricebook — the coupling {@code ConsumptionReport}'s
 * own contract forbids for the weaker case of merely NAMING a unit — and it could not be done
 * correctly anyway: the pricebook is versioned and the hold pins {@code pricebook_version}, so a
 * caller pricing at "current" would silently charge a rate the hold was never authorised against.
 *
 * @param capability which {@code credit_pricebook} row prices this step's work
 * @param modelName the model that actually ran, or {@code null} to price against the capability's
 *     default row — the executor's resolved model, never the {@code "default"} sentinel a producer
 *     sends when it has not chosen one yet
 * @param consumption what that step measured
 */
public record MeteredStep(
    BillableCapability capability, String modelName, ConsumptionReport consumption) {

  /** Compact canonical constructor enforcing the step's invariants (ERR-106). */
  public MeteredStep {
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(consumption, "consumption must not be null");
    // Blank is not a model, and it must not become one: pinnedRate prefers a model-specific row
    // and falls back to the capability default only on NULL, so a blank would find neither.
    modelName = modelName == null || modelName.isBlank() ? null : modelName;
  }
}
