package com.krizaka.billing.domain.model;

import com.krizaka.test.events.EventContractTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** {@link UnmeteredTurn}, as a calling service publishes it, conforms to its schema. */
class UnmeteredTurnContractTest extends EventContractTest {

  @Test
  void anUnmeteredTurnConforms() {
    assertConforms(
        UnmeteredTurn.ROUTING_KEY,
        1,
        new UnmeteredTurn(
            "user-1",
            BillableCapability.CHAT,
            "corr-1",
            "billing-unreachable",
            Instant.parse("2026-10-09T10:00:00Z")));
  }
}
