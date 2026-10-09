package com.krizaka.billing.service.domain.model;

/**
 * Lifecycle of a subscription, mirroring the {@code billing_subscription.status} domain.
 *
 * <p>The first three are <b>live</b>: a past-due subscriber still has their plan's entitlements,
 * because cutting access at the first failed payment loses more revenue to churn than it saves in
 * compute. The partial unique index enforces one live subscription per actor.
 */
public enum SubscriptionStatus {
  TRIALING,
  ACTIVE,
  PAST_DUE,
  CANCELED,
  EXPIRED;

  /**
   * Whether this status grants the plan's entitlements.
   *
   * @return {@code true} for the statuses the active-subscription index treats as live
   */
  public boolean isLive() {
    return this == TRIALING || this == ACTIVE || this == PAST_DUE;
  }
}
