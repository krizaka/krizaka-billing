package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackPriceTest {

  @Test
  void carriesTheWholeOfBillingsAnswerAboutAPack() {
    PackPrice price = new PackPrice("realestate-studio", 4900, 5000L, true);

    assertEquals("realestate-studio", price.packKey());
    assertEquals(4900, price.priceCents());
    assertEquals(5000L, price.includedCredits());
    assertTrue(price.active());
  }

  @Test
  void acceptsAFreePack_becauseZeroIsAPriceAndNotAMissingOne() {
    assertEquals(0, new PackPrice("welcome", 0, 0L, true).priceCents());
  }

  @Test
  void rejects_aPriceOrGrantBelowZero() {
    assertThrows(
        IllegalArgumentException.class, () -> new PackPrice("realestate-studio", -1, 0L, true));
    assertThrows(
        IllegalArgumentException.class, () -> new PackPrice("realestate-studio", 0, -1L, true));
  }

  @Test
  void rejects_aPriceWithNoPackToAttachItTo() {
    assertThrows(IllegalArgumentException.class, () -> new PackPrice(null, 0, 0L, true));
    assertThrows(IllegalArgumentException.class, () -> new PackPrice("  ", 0, 0L, true));
  }
}
