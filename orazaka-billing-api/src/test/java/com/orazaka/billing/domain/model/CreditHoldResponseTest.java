package com.orazaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreditHoldResponseTest {

  @Test
  void accepts_aGrantedResponse() {
    CreditHoldResponse response = new CreditHoldResponse("hold-1", true, false, 90, 4910, 1);

    assertTrue(response.granted());
    assertEquals(90, response.estimatedCredits());
    assertEquals(4910, response.balanceAfterHold());
    assertEquals(1, response.pricebookVersion());
  }

  @Test
  void accepts_aDryRunGrant_thatWouldHaveBeenRefused() {
    CreditHoldResponse response = new CreditHoldResponse("hold-2", true, true, 360, -10, 1);

    assertTrue(response.dryRun());
    assertEquals(-10, response.balanceAfterHold());
  }

  @Test
  void rejects_nullHoldId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CreditHoldResponse(null, true, false, 1, 1, 1));
  }

  @Test
  void rejects_blankHoldId() {
    assertThrows(
        IllegalArgumentException.class, () -> new CreditHoldResponse(" ", true, false, 1, 1, 1));
  }

  @Test
  void rejects_pricebookVersionBelowOne() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreditHoldResponse("hold-3", true, false, 1, 1, 0));
  }

  @Test
  void notMetered_grantsWithoutReserving() {
    CreditHoldResponse response = CreditHoldResponse.notMetered();

    assertTrue(response.granted());
    assertFalse(response.metered());
    assertEquals(0, response.estimatedCredits());
  }

  @Test
  void metered_isTrue_forARealReservation() {
    assertTrue(new CreditHoldResponse("hold-4", true, false, 90, 10, 1).metered());
  }
}
