package com.orazaka.billing.domain.port;

import com.orazaka.billing.domain.model.EntitlementSnapshot;

/**
 * Read side of the billing contract: what is this actor allowed to do?
 *
 * <p>Consumed by the entitlement gate on the synchronous path, which is why implementations are
 * expected to serve from a short-lived cache rather than a service hop per chat turn.
 */
public interface EntitlementProvider {

  /**
   * Resolves an actor's effective entitlements — plan ∪ pack — plus their balance summary.
   *
   * @param actorId the opaque billable subject
   * @return the snapshot in force for that actor
   */
  EntitlementSnapshot forActor(String actorId);
}
