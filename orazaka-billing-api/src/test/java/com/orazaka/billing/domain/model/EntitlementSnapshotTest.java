package com.orazaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntitlementSnapshotTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final Instant EXPIRY = Instant.parse("2026-07-29T12:00:00Z");

  private static EntitlementSnapshot premium(Map<String, String> entitlements) {
    return new EntitlementSnapshot(ACTOR, "premium", entitlements, 5000, EXPIRY);
  }

  @Test
  void copiesTheMatrix_soLaterMutationOfTheSourceCannotChangeTheSnapshot() {
    Map<String, String> source = new HashMap<>(Map.of("capability.video", "true"));
    EntitlementSnapshot snapshot = premium(source);

    source.put("capability.video", "false");
    source.put("capability.workflow", "true");

    assertTrue(snapshot.allows("capability.video"));
    assertFalse(snapshot.allows("capability.workflow"));
    assertEquals(1, snapshot.entitlements().size());
  }

  @Test
  void exposesAnUnmodifiableMatrix() {
    EntitlementSnapshot snapshot = premium(Map.of("capability.video", "true"));

    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.entitlements().put("capability.video", "false"));
  }

  @Test
  void allows_isTrueOnlyForAnExplicitTrue() {
    EntitlementSnapshot snapshot =
        premium(Map.of("capability.video", "true", "capability.workflow", "false"));

    assertTrue(snapshot.allows("capability.video"));
    assertFalse(snapshot.allows("capability.workflow"));
  }

  @Test
  void allows_treatsAnAbsentKeyAsADenial() {
    assertFalse(premium(Map.of()).allows("capability.video"));
  }

  @Test
  void limit_readsAConfiguredNumericEntitlement() {
    assertEquals(5, premium(Map.of("concurrency.jobs", "5")).limit("concurrency.jobs", 1));
  }

  @Test
  void limit_fallsBackToTheCodeDefaultWhenTheKeyIsAbsent() {
    assertEquals(1, premium(Map.of()).limit("concurrency.jobs", 1));
  }

  @Test
  void limit_fallsBackToTheCodeDefaultWhenTheValueIsNotAnInteger() {
    assertEquals(1, premium(Map.of("concurrency.jobs", "many")).limit("concurrency.jobs", 1));
  }

  @Test
  void rejects_blankActorId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntitlementSnapshot(" ", "premium", Map.of(), 0, EXPIRY));
  }

  @Test
  void rejects_blankPlanKey() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EntitlementSnapshot(ACTOR, "", Map.of(), 0, EXPIRY));
  }

  @Test
  void rejects_nullEntitlements() {
    assertThrows(
        NullPointerException.class,
        () -> new EntitlementSnapshot(ACTOR, "premium", null, 0, EXPIRY));
  }

  @Test
  void rejects_nullExpiry() {
    assertThrows(
        NullPointerException.class,
        () -> new EntitlementSnapshot(ACTOR, "premium", Map.of(), 0, null));
  }

  @Test
  void unresolved_isDistinguishableFromAPlanThatDeniesEverything() {
    // The distinction the gate turns on: an absent key is a denial, but a lookup that never
    // completed says nothing about the actor and must not refuse them.
    EntitlementSnapshot outage =
        EntitlementSnapshot.unresolved("actor-1", Instant.now().plusSeconds(5));

    assertFalse(outage.resolved());
    assertFalse(outage.allows("capability.chat"));
    assertTrue(outage.entitlements().isEmpty());
  }

  @Test
  void aRealSnapshotIsResolved() {
    EntitlementSnapshot snapshot =
        new EntitlementSnapshot(
            "actor-1",
            "free",
            Map.of("capability.chat", "true"),
            10L,
            Instant.now().plusSeconds(60));

    assertTrue(snapshot.resolved());
  }
}
