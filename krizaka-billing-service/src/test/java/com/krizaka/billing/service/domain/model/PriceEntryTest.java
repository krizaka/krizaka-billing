package com.krizaka.billing.service.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceEntryTest {

  private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

  private static PriceEntry entry(PriceAvailability availability, Long amount) {
    return new PriceEntry(
        UUID.randomUUID(),
        "orazaka.business",
        PriceUnit.YEAR,
        amount,
        availability,
        Map.of("credits", 5000L),
        NOW);
  }

  @Test
  @DisplayName("an internal entry has no public view")
  void internalIsNeverPublic() {
    assertTrue(entry(PriceAvailability.INTERNAL, 4500L).publicView().isEmpty());
  }

  @Test
  @DisplayName("a preparing entry is shown without figure or limits")
  void preparingHasNoFigure() {
    PriceView view = entry(PriceAvailability.PREPARING, 290000L).publicView().orElseThrow();
    assertNull(view.amountCents());
    assertTrue(view.limits().isEmpty());
    assertEquals("preparing", view.availability());
  }

  @Test
  @DisplayName("a public entry shows its amount and limits; on quote stays null")
  void publicShowsFigure() {
    assertEquals(0L, entry(PriceAvailability.PUBLIC, 0L).publicView().orElseThrow().amountCents());
    assertNull(entry(PriceAvailability.PUBLIC, null).publicView().orElseThrow().amountCents());
    assertEquals(
        "founder-limited",
        entry(PriceAvailability.FOUNDER_LIMITED, 1L).publicView().orElseThrow().availability());
  }

  @Test
  @DisplayName("a key is product.plan and an amount is never negative")
  void validates() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PriceEntry(
                UUID.randomUUID(),
                "business",
                PriceUnit.YEAR,
                1L,
                PriceAvailability.PUBLIC,
                null,
                NOW));
    assertThrows(IllegalArgumentException.class, () -> entry(PriceAvailability.PUBLIC, -1L));
  }

  @Test
  @DisplayName("a change never starts in the past")
  void neverInThePast() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PriceChange.requireNotInPast(NOW.minusSeconds(1), NOW));
    PriceChange.requireNotInPast(NOW, NOW);
  }

  @Test
  @DisplayName("wire values round-trip and unknown ones are refused")
  void wire() {
    for (PriceAvailability a : PriceAvailability.values()) {
      assertEquals(a, PriceAvailability.fromWire(a.wire()));
    }
    for (PriceUnit u : PriceUnit.values()) {
      assertEquals(u, PriceUnit.fromWire(u.wire()));
    }
    assertThrows(IllegalArgumentException.class, () -> PriceUnit.fromWire("decade"));
  }
}
