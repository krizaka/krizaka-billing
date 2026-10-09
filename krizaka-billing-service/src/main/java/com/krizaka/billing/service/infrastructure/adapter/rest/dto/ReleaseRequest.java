package com.krizaka.billing.service.infrastructure.adapter.rest.dto;

/**
 * Body of {@code POST /api/v1/billing/credits/{holdId}/release}.
 *
 * @param reason why the hold is being released, retained on the audit trail — an unexplained
 *     release is indistinguishable from a lost debit months later
 */
public record ReleaseRequest(String reason) {

  /** Compact canonical constructor supplying a default rather than rejecting an absent reason. */
  public ReleaseRequest {
    if (reason == null || reason.isBlank()) {
      reason = "unspecified";
    }
  }
}
