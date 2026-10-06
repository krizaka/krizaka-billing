package com.orazaka.billingservice.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billing.domain.model.BillableCapability;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarginPreviewTest {

  private static MarginPreview preview(long events, long current, long proposed) {
    return new MarginPreview(BillableCapability.VIDEO, "wan-2.1", events, current, proposed);
  }

  @Test
  @DisplayName("a price rise reports what it would have gained")
  void reportsAGain() {
    MarginPreview preview = preview(120, 1000, 1250);

    assertEquals(250, preview.deltaCredits());
    assertEquals(0, preview.deltaPercent().compareTo(new BigDecimal("25.00")));
  }

  @Test
  @DisplayName("a price cut reports what it would have given up, signed")
  void reportsALoss() {
    MarginPreview preview = preview(120, 1000, 800);

    assertEquals(-200, preview.deltaCredits());
    assertEquals(0, preview.deltaPercent().compareTo(new BigDecimal("-20.00")));
  }

  @Test
  @DisplayName("a preview over no traffic says so rather than implying a verdict")
  void flagsAnEmptySample() {
    MarginPreview preview = preview(0, 0, 0);

    assertFalse(preview.hasEvidence());
    assertEquals(0, preview.deltaPercent().compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName(
      "traffic that charged nothing cannot yield a percentage, and does not divide by zero")
  void handlesAZeroBaseline() {
    MarginPreview preview = preview(40, 0, 500);

    assertTrue(preview.hasEvidence());
    assertEquals(500, preview.deltaCredits());
    assertEquals(0, preview.deltaPercent().compareTo(BigDecimal.ZERO));
  }

  @Test
  void rejectsNegativeTotals() {
    assertThrows(IllegalArgumentException.class, () -> preview(-1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> preview(1, -5, 0));
    assertThrows(IllegalArgumentException.class, () -> preview(1, 0, -5));
  }
}
