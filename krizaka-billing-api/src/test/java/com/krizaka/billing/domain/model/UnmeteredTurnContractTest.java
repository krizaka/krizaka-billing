package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.krizaka.test.events.EventContractTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link UnmeteredTurn}, as a calling service publishes it, conforms to its schema. */
class UnmeteredTurnContractTest extends EventContractTest {

  private static UnmeteredTurn turn(BillableCapability capability) {
    return new UnmeteredTurn(
        "user-1",
        capability,
        "corr-1",
        "billing-unreachable",
        Instant.parse("2026-10-09T10:00:00Z"));
  }

  @Test
  void anUnmeteredTurnConforms() {
    assertDoesNotThrow(
        () -> assertConforms(UnmeteredTurn.ROUTING_KEY, 1, turn(BillableCapability.CHAT)));
  }

  /**
   * The schema's {@code capability} enum is a hand-written mirror of {@link BillableCapability}. A
   * capability added to the enum and not to the schema would make every unmetered turn of that
   * capability invalid for a consumer validating against the published schema.
   */
  @ParameterizedTest
  @EnumSource(BillableCapability.class)
  @DisplayName("every billable capability is in the schema's capability enum")
  void everyCapabilityConforms(BillableCapability capability) {
    assertDoesNotThrow(() -> assertConforms(UnmeteredTurn.ROUTING_KEY, 1, turn(capability)));
  }
}
