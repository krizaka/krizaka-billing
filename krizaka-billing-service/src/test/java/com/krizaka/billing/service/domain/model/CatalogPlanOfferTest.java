package com.krizaka.billing.service.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The overage and the validation of a plan's offer fields (krizaka-billing#80). */
class CatalogPlanOfferTest {

  private static CatalogPlan plan(Integer overage, int trialDays, Long free) {
    return new CatalogPlan(
        "studio", "Studio", 4, 1_000L, 2900, "CAD", "premium", true, true, overage, trialDays, free,
        null);
  }

  @Test
  @DisplayName("overage bills each credit past the grant plus the free allowance")
  void overageSettles() {
    CatalogPlan plan = plan(3, 0, 200L);
    assertEquals(0L, plan.overageCents(1_200L));
    assertEquals(3L, plan.overageCents(1_201L));
    assertEquals(1_500L, plan.overageCents(1_700L));
  }

  @Test
  @DisplayName("a plan without an overage price never bills past its allowance")
  void noOveragePrice() {
    assertEquals(0L, plan(null, 0, null).overageCents(1_000_000L));
  }

  @Test
  @DisplayName("negative offer fields are refused")
  void refusesNegatives() {
    assertThrows(IllegalArgumentException.class, () -> plan(-1, 0, null));
    assertThrows(IllegalArgumentException.class, () -> plan(null, -1, null));
    assertThrows(IllegalArgumentException.class, () -> plan(null, 0, -5L));
  }
}
