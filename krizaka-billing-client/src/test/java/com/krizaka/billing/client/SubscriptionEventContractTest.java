package com.krizaka.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.billing.client.SubscriptionChangeListener.SubscriptionEvent;
import com.krizaka.test.events.EventContractTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SubscriptionChangeListener} keeps its own copy of the subscription and pack events; it
 * reads every document billing's schemas accept.
 */
class SubscriptionEventContractTest extends EventContractTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "evt.subscription.changed",
        "evt.subscription.canceled",
        "evt.subscription.renewed",
        "evt.pack.subscribed",
        "evt.pack.canceled"
      })
  void theCopyReadsTheActor(String routingKey) {
    String key = routingKey.startsWith("evt.pack.") ? "packKey" : "planKey";
    String example =
        "{\"actorId\":\"user-1\",\""
            + key
            + "\":\"pro\",\"status\":\"ACTIVE\",\"periodEnd\":\"2026-11-09T10:00:00Z\","
            + "\"occurredAt\":\"2026-10-09T10:00:00Z\"}";

    SubscriptionEvent event = assertReadable(routingKey, 1, example, SubscriptionEvent.class);

    assertThat(event.actorId()).isEqualTo("user-1");
  }
}
