package com.krizaka.billing.domain.model;

/**
 * Outcome of a hold request.
 *
 * <p>The protocol carries the <b>outcome</b>, never the policy: a consumer reads {@code granted}
 * and {@code dryRun} and proceeds, without ever learning the enforcement mode (ADR-033 §6).
 *
 * @param holdId the reservation's identifier, later passed to settle or release
 * @param granted whether the request may proceed
 * @param dryRun whether the grant is a shadow-metering grant that would have been refused under
 *     {@link EnforcementMode#ENFORCING}
 * @param estimatedCredits the credits actually reserved
 * @param balanceAfterHold the actor's remaining available balance once this hold is applied
 * @param pricebookVersion the pricebook version this hold was priced with — pinned so a mid-flight
 *     price change cannot corrupt settlement
 */
public record CreditHoldResponse(
    String holdId,
    boolean granted,
    boolean dryRun,
    long estimatedCredits,
    long balanceAfterHold,
    int pricebookVersion) {

  /**
   * The synthetic id returned when nothing was actually reserved — billing switched off, or wired
   * to the no-op adapter. Declared here rather than in each adapter so the producers that must
   * recognise it and the ledger that must ignore it read the same constant.
   */
  public static final String NOT_METERED_HOLD_ID = "billing-disabled";

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public CreditHoldResponse {
    if (holdId == null || holdId.isBlank()) {
      throw new IllegalArgumentException("holdId must not be blank");
    }
    if (pricebookVersion < 1) {
      throw new IllegalArgumentException("pricebookVersion must be >= 1");
    }
  }

  /**
   * The grant issued when billing is not metering this request.
   *
   * @return a granted response carrying {@link #NOT_METERED_HOLD_ID}
   */
  public static CreditHoldResponse notMetered() {
    return new CreditHoldResponse(NOT_METERED_HOLD_ID, true, false, 0L, 0L, 1);
  }

  /**
   * Whether this grant corresponds to a real reservation.
   *
   * @return {@code false} when nothing was reserved — a producer must not propagate such a hold id
   *     downstream, because there is no hold behind it to settle or release
   */
  public boolean metered() {
    return !NOT_METERED_HOLD_ID.equals(holdId);
  }
}
