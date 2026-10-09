package com.krizaka.billing.service.domain.model;

/**
 * Which balance a credit movement touches.
 *
 * <p>The two buckets exist because they have different expiry semantics and, in several
 * jurisdictions, different legal standing: granted credits are promotional and expire at period
 * end, purchased credits were paid for and are the user's property. Collapsing them into one number
 * makes that distinction unrepresentable, so debits always name a bucket.
 */
public enum CreditBucket {

  /** From the subscription grant; expires at {@code period_end} (use-it-or-lose-it). */
  GRANTED,

  /** Bought à la carte; long or no expiry. Debited only after {@link #GRANTED} is exhausted. */
  PURCHASED
}
