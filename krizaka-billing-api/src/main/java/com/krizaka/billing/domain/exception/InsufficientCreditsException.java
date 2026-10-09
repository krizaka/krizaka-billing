package com.krizaka.billing.domain.exception;

import com.krizaka.billing.domain.model.BillableCapability;
import java.util.List;

/**
 * Thrown when an actor cannot cover the estimated cost of a request under active enforcement.
 *
 * <p>Carries the required amount, the available balance and the capability, so the REST layer can
 * build the structured 402 of the billing design (§6.1) — remaining balance, credits required,
 * capability, and the two remedies — without re-deriving anything.
 *
 * <p>The refusal crosses a service boundary: the ledger raises it, and a producer that received it
 * over HTTP re-raises it to its own caller. {@link #remedies()} therefore travels with it — a
 * producer cannot recompute which remedies an actor's plan permits without a second lookup into a
 * context it does not own, and offering "top up" to an actor whose plan forbids it is a dead end.
 */
public class InsufficientCreditsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String actorId;
  private final long required;
  private final long available;
  private final BillableCapability capability;
  private final transient List<String> remedies;

  /**
   * Constructs the refusal where the remedies are not yet known — the ledger's own throw site,
   * before the REST layer resolves the actor's entitlement.
   *
   * @param actorId the actor that was refused — the remedies offered depend on their plan, so a
   *     refusal that cannot name the subject cannot be rendered without a second lookup
   * @param capability the capability that was refused
   * @param required the credits the request would have cost
   * @param available the credits the actor actually has
   */
  public InsufficientCreditsException(
      String actorId, BillableCapability capability, long required, long available) {
    this(actorId, capability, required, available, List.of());
  }

  /**
   * Constructs the refusal with everything the paywall needs, remedies included.
   *
   * @param actorId the actor that was refused
   * @param capability the capability that was refused
   * @param required the credits the request would have cost
   * @param available the credits the actor actually has
   * @param remedies the ways out, in the order the UI should offer them
   */
  public InsufficientCreditsException(
      String actorId,
      BillableCapability capability,
      long required,
      long available,
      List<String> remedies) {
    super(
        "Insufficient credits for "
            + capability
            + ": required "
            + required
            + ", available "
            + available);
    this.actorId = actorId;
    this.capability = capability;
    this.required = required;
    this.available = available;
    this.remedies = remedies == null ? List.of() : List.copyOf(remedies);
  }

  /**
   * @return the actor that was refused
   */
  public String actorId() {
    return actorId;
  }

  /**
   * @return the credits the refused request would have cost
   */
  public long required() {
    return required;
  }

  /**
   * @return the credits the actor had available at refusal time
   */
  public long available() {
    return available;
  }

  /**
   * @return the capability that was refused
   */
  public BillableCapability capability() {
    return capability;
  }

  /**
   * @return the ways out this actor's plan actually permits, empty when the throw site could not
   *     resolve them
   */
  public List<String> remedies() {
    return remedies;
  }
}
