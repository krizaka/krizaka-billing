package com.orazaka.billing.domain.model;

/**
 * Lifecycle of a credit reservation.
 *
 * <p>A hold leaves {@link #ACTIVE} exactly once: settled against measured consumption, released
 * because the work failed, or expired by the sweeper when the executor never reported back.
 */
public enum HoldStatus {

  /** Reserved against the wallet; counts towards {@code credit_wallet.held}. */
  ACTIVE,

  /** Closed against measured consumption — a debit was written to the ledger. */
  SETTLED,

  /** Closed with no debit: the work failed, and a failed generation is never billed. */
  RELEASED,

  /**
   * Closed by the sweeper past its TTL — without this, a crashed worker would freeze a balance
   * forever.
   */
  EXPIRED
}
