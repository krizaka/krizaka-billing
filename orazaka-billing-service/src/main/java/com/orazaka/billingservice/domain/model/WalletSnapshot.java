package com.orazaka.billingservice.domain.model;

/**
 * A wallet's balances at a point in time.
 *
 * @param actorId the opaque billable subject
 * @param balanceGranted credits from the subscription, expiring at period end
 * @param balancePurchased credits bought à la carte
 * @param held the sum of ACTIVE holds, denormalised so authorisation stays O(1)
 */
public record WalletSnapshot(
    String actorId, long balanceGranted, long balancePurchased, long held) {

  /** Compact canonical constructor enforcing the wallet's invariants (ERR-106). */
  public WalletSnapshot {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (balanceGranted < 0 || balancePurchased < 0 || held < 0) {
      throw new IllegalArgumentException("wallet balances must be >= 0");
    }
  }

  /**
   * The spendable balance — the number every authorisation decision is made against.
   *
   * @return {@code granted + purchased − held}, which may go negative under {@code DRY_RUN} where
   *     holds are recorded without being enforced
   */
  public long available() {
    return balanceGranted + balancePurchased - held;
  }
}
