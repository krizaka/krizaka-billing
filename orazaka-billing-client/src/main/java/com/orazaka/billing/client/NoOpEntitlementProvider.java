package com.orazaka.billing.client;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.EntitlementProvider;
import java.time.Duration;
import java.time.Instant;

/**
 * Null Object: reports every actor as unresolved, so nothing is gated.
 *
 * <p>Same purpose as its credit counterpart — {@code orazaka.billing.enabled=false} stays a real
 * answer instead of a branch at each call site (ERR-127). Unresolved rather than fully-entitled
 * because that is the truth: with billing unwired there is no plan to read, and a snapshot claiming
 * a plan would be a fiction the gate might later act on.
 */
final class NoOpEntitlementProvider implements EntitlementProvider {

  private static final Duration HORIZON = Duration.ofMinutes(5);

  @Override
  public EntitlementSnapshot forActor(String actorId) {
    return EntitlementSnapshot.unresolved(actorId, Instant.now().plus(HORIZON));
  }
}
