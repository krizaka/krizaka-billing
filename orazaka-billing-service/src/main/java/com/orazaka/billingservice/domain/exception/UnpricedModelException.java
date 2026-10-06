package com.orazaka.billingservice.domain.exception;

import com.orazaka.billing.domain.model.BillableCapability;

/**
 * Thrown when no current pricebook row covers a capability × model.
 *
 * <p>Refusing is deliberate: silently falling back to a default rate would bill unmeasured compute
 * at a guessed price, and a guess that is wrong in the operator's favour is a refund, while a guess
 * wrong the other way is an unnoticed margin hole. Under {@code DRY_RUN} this surfaces in the
 * shadow-metering log, which is exactly the phase-0 worklist of models still to price.
 */
public class UnpricedModelException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final BillableCapability capability;
  private final String modelName;

  /**
   * Constructs the refusal.
   *
   * @param capability the capability with no current rate
   * @param modelName the model with no current rate, or {@code null} for the capability default
   */
  public UnpricedModelException(BillableCapability capability, String modelName) {
    super("No current pricebook rate for capability " + capability + " and model " + modelName);
    this.capability = capability;
    this.modelName = modelName;
  }

  /**
   * @return the capability that could not be priced
   */
  public BillableCapability capability() {
    return capability;
  }

  /**
   * @return the model that could not be priced, possibly {@code null}
   */
  public String modelName() {
    return modelName;
  }
}
