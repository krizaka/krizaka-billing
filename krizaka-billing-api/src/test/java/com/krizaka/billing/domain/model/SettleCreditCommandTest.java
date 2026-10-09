package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettleCreditCommandTest {

  private static final String HOLD = "hold-1";
  private static final String KEY = "amqp-message-id-1";

  @Test
  void accepts_aMeasuredSettlement() {
    SettleCreditCommand command =
        new SettleCreditCommand(HOLD, BillableUnit.KILOTOKEN, new BigDecimal("3.4210"), KEY);

    assertEquals(BillableUnit.KILOTOKEN, command.unit());
    assertEquals(new BigDecimal("3.4210"), command.quantity());
  }

  @Test
  void accepts_zeroQuantity_becauseAnEmptyCompletionIsStillASettlement() {
    assertDoesNotThrow(
        () -> new SettleCreditCommand(HOLD, BillableUnit.CALL, BigDecimal.ZERO, KEY));
  }

  @Test
  void rejects_blankHoldId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SettleCreditCommand("", BillableUnit.CALL, BigDecimal.ONE, KEY));
  }

  @Test
  void rejects_nullUnit() {
    assertThrows(
        NullPointerException.class, () -> new SettleCreditCommand(HOLD, null, BigDecimal.ONE, KEY));
  }

  @Test
  void rejects_nullQuantity() {
    assertThrows(
        NullPointerException.class,
        () -> new SettleCreditCommand(HOLD, BillableUnit.CALL, null, KEY));
  }

  @Test
  void rejects_negativeQuantity_comparedAsBigDecimalNotAsDouble() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SettleCreditCommand(HOLD, BillableUnit.GPU_SECOND, new BigDecimal("-0.0001"), KEY));
  }

  @Test
  void accepts_scaledZero_becauseScaleIsIrrelevantToTheComparison() {
    assertDoesNotThrow(
        () ->
            new SettleCreditCommand(HOLD, BillableUnit.GPU_SECOND, new BigDecimal("0.0000"), KEY));
  }

  @Test
  void rejects_blankIdempotencyKey() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SettleCreditCommand(HOLD, BillableUnit.CALL, BigDecimal.ONE, "  "));
  }
}
