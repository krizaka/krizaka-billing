package com.krizaka.billing.service.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActorUsageTest {

  private static ActorUsage usage(long credits, int unitPriceMillicents) {
    return new ActorUsage(
        "actor-1",
        YearMonth.of(2026, 10),
        "pro",
        "Pro",
        5000,
        "CAD",
        unitPriceMillicents,
        3,
        credits,
        List.of(new ActorUsage.Line("CHAT", "m1", 3, credits, credits * unitPriceMillicents)));
  }

  @Test
  @DisplayName("cost is credits times the millicent anchor, and cents round half up")
  void costFromCredits() {
    ActorUsage u = usage(1234, 100); // 123 400 millicents = 123.4 cents
    assertEquals(123_400, u.costMillicents());
    assertEquals(123, u.costCents());
    assertEquals(124, usage(1235, 100).costCents()); // 123.5 rounds up
  }

  @Test
  @DisplayName("the grant remaining never goes negative, even when usage passes the grant")
  void remainingGrantFloorsAtZero() {
    assertEquals(3766, usage(1234, 100).includedCreditsRemaining());
    assertEquals(0, usage(9000, 100).includedCreditsRemaining());
  }

  @Test
  @DisplayName("totals cannot be negative")
  void rejectsNegatives() {
    assertThrows(IllegalArgumentException.class, () -> usage(-1, 100));
  }
}
