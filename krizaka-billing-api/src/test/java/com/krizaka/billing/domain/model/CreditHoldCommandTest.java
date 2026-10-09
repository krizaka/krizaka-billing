package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CreditHoldCommandTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";
  private static final String CORRELATION = "intention-42";

  @Test
  void accepts_aFullyPopulatedCommand() {
    CreditHoldCommand command =
        new CreditHoldCommand(ACTOR, BillableCapability.VIDEO, "svd-xt", CORRELATION, "job-7", 360);

    assertEquals(ACTOR, command.actorId());
    assertEquals(BillableCapability.VIDEO, command.capability());
    assertEquals(360, command.estimatedCredits());
  }

  @Test
  void accepts_nullModelNameAndJobId_becauseChatHasNeither() {
    CreditHoldCommand command =
        new CreditHoldCommand(ACTOR, BillableCapability.CHAT, null, CORRELATION, null, 2);

    assertNull(command.modelName());
    assertNull(command.jobId());
  }

  @Test
  void accepts_zeroEstimate_becauseAFreeCapabilityStillTakesAHold() {
    assertDoesNotThrow(
        () -> new CreditHoldCommand(ACTOR, BillableCapability.AGENT, null, CORRELATION, null, 0));
  }

  @Test
  void rejects_nullActorId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreditHoldCommand(null, BillableCapability.CHAT, null, CORRELATION, null, 1));
  }

  @Test
  void rejects_blankActorId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreditHoldCommand("  ", BillableCapability.CHAT, null, CORRELATION, null, 1));
  }

  @Test
  void rejects_nullCapability() {
    assertThrows(
        NullPointerException.class,
        () -> new CreditHoldCommand(ACTOR, null, null, CORRELATION, null, 1));
  }

  @Test
  void rejects_blankCorrelationId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreditHoldCommand(ACTOR, BillableCapability.CHAT, null, "", null, 1));
  }

  @Test
  void rejects_negativeEstimate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreditHoldCommand(ACTOR, BillableCapability.CHAT, null, CORRELATION, null, -1));
  }
}
