package com.krizaka.billing.service.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.UnmeteredTurn;
import com.krizaka.billing.service.application.service.UnmeteredTurnService;
import com.krizaka.messaging.dedup.MessageDedup;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnmeteredTurnListenerTest {

  private static final String MESSAGE_ID = "outbox-64";

  private final UnmeteredTurnService service = mock(UnmeteredTurnService.class);
  private final MessageDedup dedup = mock(MessageDedup.class);
  private final UnmeteredTurnListener listener = new UnmeteredTurnListener(service, dedup);
  private final UnmeteredTurn turn =
      new UnmeteredTurn(
          "actor",
          BillableCapability.CHAT,
          "conv-64",
          "billing unreachable: RestClientException",
          Instant.parse("2026-09-15T12:00:00Z"));

  @Test
  @DisplayName("a turn served without a hold is recorded once")
  void recordsTheTurn() {
    when(dedup.claim("billing-unmetered-turn", MESSAGE_ID)).thenReturn(true);

    listener.onUnmeteredTurn(turn, MESSAGE_ID);

    verify(service).record(turn);
  }

  @Test
  @DisplayName("a redelivery is not recorded twice — it would be reconciled twice")
  void skipsARedelivery() {
    when(dedup.claim("billing-unmetered-turn", MESSAGE_ID)).thenReturn(false);

    listener.onUnmeteredTurn(turn, MESSAGE_ID);

    verify(service, never()).record(any());
  }

  @Test
  @DisplayName("a record that fails to land gives its claim back, so the redelivery lands it")
  void releasesTheClaimWhenTheRecordFails() {
    when(dedup.claim("billing-unmetered-turn", MESSAGE_ID)).thenReturn(true);
    doThrow(new IllegalStateException("database down")).when(service).record(turn);

    assertThrows(IllegalStateException.class, () -> listener.onUnmeteredTurn(turn, MESSAGE_ID));

    verify(dedup).release("billing-unmetered-turn", MESSAGE_ID);
  }
}
