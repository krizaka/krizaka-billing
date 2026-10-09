package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UnmeteredTurnTest {

  private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

  @Test
  void aCompleteRecordIsAccepted() {
    UnmeteredTurn turn =
        new UnmeteredTurn("actor", BillableCapability.CHAT, "conv-1", "RestClientException", NOW);

    assertEquals("conv-1", turn.correlationId());
    assertTrue(UnmeteredTurn.ROUTING_KEY.startsWith("evt."));
  }

  @Test
  void aRecordThatCannotBeReconciledIsRefused() {
    BillableCapability chat = BillableCapability.CHAT;
    assertThrows(IllegalArgumentException.class, () -> new UnmeteredTurn(" ", chat, "c", "r", NOW));
    assertThrows(IllegalArgumentException.class, () -> new UnmeteredTurn("a", chat, "", "r", NOW));
    assertThrows(NullPointerException.class, () -> new UnmeteredTurn("a", null, "c", "r", NOW));
    assertThrows(
        IllegalArgumentException.class, () -> new UnmeteredTurn("a", chat, "c", null, NOW));
    assertThrows(NullPointerException.class, () -> new UnmeteredTurn("a", chat, "c", "r", null));
  }
}
