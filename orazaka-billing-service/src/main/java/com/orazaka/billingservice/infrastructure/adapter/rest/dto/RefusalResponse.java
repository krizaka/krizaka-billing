package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

import java.util.List;

/**
 * The structured refusal behind a 402.
 *
 * <p>Never a bare status code: the UI needs the balance, the shortfall, the capability and the
 * remedies to render a useful paywall instead of an error, and re-deriving any of them client-side
 * is how the paywall drifts out of step with the ledger.
 *
 * @param status machine-readable refusal code
 * @param capability the capability that was refused
 * @param required the credits the request would have cost
 * @param balance the credits the actor actually has available
 * @param remedies the ways out, in the order the UI should offer them
 */
public record RefusalResponse(
    String status, String capability, long required, long balance, List<String> remedies) {

  /** Compact canonical constructor; remedies are defensively copied (ERR-106). */
  public RefusalResponse {
    remedies = remedies == null ? List.of() : List.copyOf(remedies);
  }

  /**
   * Builds the insufficient-credits refusal.
   *
   * @param capability the capability refused
   * @param required credits needed
   * @param balance credits available
   * @param canTopUp whether the actor's plan permits buying credits — a {@code free} actor is
   *     offered an upgrade only, because selling compute to an unverified account is a fraud
   *     surface
   * @return the populated refusal
   */
  public static RefusalResponse insufficientCredits(
      String capability, long required, long balance, boolean canTopUp) {
    List<String> remedies =
        canTopUp ? List.of("upgrade_plan", "top_up_credits") : List.of("upgrade_plan");
    return new RefusalResponse("insufficient_credits", capability, required, balance, remedies);
  }
}
