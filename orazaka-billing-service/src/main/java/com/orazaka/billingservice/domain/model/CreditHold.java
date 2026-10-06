package com.orazaka.billingservice.domain.model;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.HoldStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * A credit reservation as stored.
 *
 * <p>Carries {@link #pricebookVersion()} so settlement re-prices against the rate the work was
 * authorised under, not against whatever is current when the worker finishes.
 *
 * @param id the reservation identifier
 * @param actorId the opaque billable subject
 * @param estimatedCredits the credits reserved
 * @param status where the hold is in its lifecycle
 * @param capability the capability reserved for
 * @param modelName the model reserved for, or {@code null} for the capability default
 * @param pricebookVersion the pinned pricebook version
 */
public record CreditHold(
    UUID id,
    String actorId,
    long estimatedCredits,
    HoldStatus status,
    BillableCapability capability,
    String modelName,
    int pricebookVersion) {

  /** Compact canonical constructor enforcing the hold's invariants (ERR-106). */
  public CreditHold {
    Objects.requireNonNull(id, "hold id must not be null");
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (estimatedCredits < 0) {
      throw new IllegalArgumentException("estimatedCredits must be >= 0");
    }
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(capability, "capability must not be null");
    if (pricebookVersion < 1) {
      throw new IllegalArgumentException("pricebookVersion must be >= 1");
    }
  }

  /**
   * Whether this hold may still be settled or released.
   *
   * @return {@code true} only while {@link HoldStatus#ACTIVE} — settle and release are no-ops
   *     otherwise, which is what makes redelivery safe
   */
  public boolean isOpen() {
    return status == HoldStatus.ACTIVE;
  }
}
