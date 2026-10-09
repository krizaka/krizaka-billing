package com.krizaka.billing.service.infrastructure.adapter.rest.dto;

import com.krizaka.billing.service.domain.model.CreditBucket;
import java.util.Objects;

/**
 * A manual credit adjustment, as the admin console submits it (design §12.1).
 *
 * <p>Nothing here has a default. The bucket is explicit because refunding into {@code PURCHASED}
 * hands out credits that never expire; the reason is mandatory because an unexplained balance
 * change is indistinguishable from fraud once the person who made it has moved on. Validating both
 * in the compact constructor means a malformed adjustment is rejected at the boundary rather than
 * halfway through moving money (ERR-106).
 *
 * @param bucket which balance to move — granted credits expire, purchased ones are the user's
 * @param amount signed credits: positive grants, negative claws back
 * @param reason why, free-text and stored on the ledger entry
 */
public record AdjustmentRequest(CreditBucket bucket, long amount, String reason) {

  /** Compact canonical constructor enforcing the guardrails of design §12.1 (ERR-106). */
  public AdjustmentRequest {
    Objects.requireNonNull(bucket, "bucket must be chosen explicitly, never defaulted");
    if (amount == 0) {
      throw new IllegalArgumentException("amount must not be zero");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is mandatory for a manual adjustment");
    }
  }
}
