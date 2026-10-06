package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

import com.orazaka.billingservice.domain.model.SubscriptionStatus;

/**
 * Moves an actor onto a plan.
 *
 * <p>{@code status} is explicit rather than inferred so that granting a trial and completing an
 * upgrade stay one operation with one audit trail — the difference between them is a word, not a
 * separate endpoint that could drift.
 *
 * @param planKey the plan to move the actor onto
 * @param status the status to open the subscription in; defaults to {@code ACTIVE}
 */
public record PlanChangeRequest(String planKey, SubscriptionStatus status) {

  /** Compact canonical constructor enforcing the request's invariants (ERR-106). */
  public PlanChangeRequest {
    if (planKey == null || planKey.isBlank()) {
      throw new IllegalArgumentException("planKey must not be blank");
    }
    status = status == null ? SubscriptionStatus.ACTIVE : status;
  }
}
