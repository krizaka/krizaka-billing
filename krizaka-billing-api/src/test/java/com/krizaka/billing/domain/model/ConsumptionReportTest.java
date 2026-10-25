package com.krizaka.billing.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConsumptionReportTest {

  private static final ConsumptionReport EMPTY =
      new ConsumptionReport(null, null, null, null, null, null, null, null, null, null, null, null);

  private static ConsumptionReport video(int frames, int fps) {
    return new ConsumptionReport(
        null, frames, fps, null, null, null, null, null, null, null, null, null);
  }

  private static ConsumptionReport image(int images, int steps, int width, int height) {
    return new ConsumptionReport(
        null, null, null, images, steps, width, height, null, null, null, null, null);
  }

  private static BigDecimal quantity(ConsumptionReport report, BillableUnit unit) {
    return report.quantityFor(unit).orElseThrow();
  }

  @Test
  @DisplayName("output seconds come from the frames rendered and their playback rate")
  void outputSeconds() {
    assertEquals(
        0, quantity(video(48, 12), BillableUnit.OUTPUT_SECOND).compareTo(new BigDecimal("4")));
  }

  @Test
  @DisplayName("a megapixel-step scales with resolution, so 4K costs more than 1080p at one rate")
  void megapixelSteps() {
    // 1 image × 4 steps × (1024×1024 ÷ 1e6 = 1.048576 MP)
    assertEquals(
        0,
        quantity(image(1, 4, 1024, 1024), BillableUnit.IMAGE_STEP)
            .compareTo(new BigDecimal("4.194304")));

    BigDecimal small = quantity(image(1, 4, 512, 512), BillableUnit.IMAGE_STEP);
    BigDecimal large = quantity(image(1, 4, 1024, 1024), BillableUnit.IMAGE_STEP);
    assertEquals(0, large.compareTo(small.multiply(new BigDecimal("4"))));
  }

  @Test
  @DisplayName("speech bills the characters it was given, in thousands")
  void kilochars() {
    ConsumptionReport report =
        new ConsumptionReport(
            null, null, null, null, null, null, null, 2500L, null, null, null, null);

    assertEquals(0, quantity(report, BillableUnit.KILOCHAR).compareTo(new BigDecimal("2.5")));
  }

  @Test
  @DisplayName("transcription bills the duration of its source, in minutes")
  void audioMinutes() {
    ConsumptionReport report =
        new ConsumptionReport(
            null, null, null, null, null, null, null, null, new BigDecimal("90"), null, null, null);

    assertEquals(0, quantity(report, BillableUnit.AUDIO_MINUTE).compareTo(new BigDecimal("1.5")));
  }

  @Test
  @DisplayName("chat bills prompt plus completion tokens, in thousands")
  void kilotokens() {
    ConsumptionReport report =
        new ConsumptionReport(
            null, null, null, null, null, null, null, null, null, 3400L, null, null);

    assertEquals(0, quantity(report, BillableUnit.KILOTOKEN).compareTo(new BigDecimal("3.4")));
  }

  @Test
  @DisplayName("GPU seconds are reported as measured — the calibration basis, unconverted")
  void gpuSeconds() {
    ConsumptionReport report =
        new ConsumptionReport(
            new BigDecimal("42.5"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertEquals(0, quantity(report, BillableUnit.GPU_SECOND).compareTo(new BigDecimal("42.5")));
  }

  private static ConsumptionReport calls(Long calls) {
    return new ConsumptionReport(
        null, null, null, null, null, null, null, null, null, null, null, calls);
  }

  @Test
  @DisplayName("a CALL with no count is a flat fee: reaching a terminal outcome is the measurement")
  void call() {
    assertEquals(0, quantity(EMPTY, BillableUnit.CALL).compareTo(BigDecimal.ONE));
  }

  @Test
  @DisplayName("a reported count of calls is the CALL quantity, so three lookups bill three calls")
  void reportedCallsAreTheQuantity() {
    assertEquals(0, quantity(calls(3L), BillableUnit.CALL).compareTo(new BigDecimal("3")));
    assertEquals(0, quantity(calls(1L), BillableUnit.CALL).compareTo(BigDecimal.ONE));
  }

  @Test
  @DisplayName("the report built without `calls` keeps no count, so a CALL stays one per outcome")
  void theConstructorWithoutCallsKeepsTheFlatFee() {
    ConsumptionReport legacy =
        new ConsumptionReport(null, null, null, null, null, null, null, null, null, 3400L, null);

    assertEquals(null, legacy.calls());
    assertEquals(0, quantity(legacy, BillableUnit.CALL).compareTo(BigDecimal.ONE));
  }

  @Test
  @DisplayName("zero calls settles nothing: a check that never reached its provider is released")
  void zeroCallsDerivesNothing() {
    assertTrue(calls(0L).quantityFor(BillableUnit.CALL).isEmpty());
  }

  @Test
  @DisplayName("a negative count of calls is refused (nothing settles), never read as one call")
  void negativeCallsDeriveNothing() {
    assertTrue(calls(-1L).quantityFor(BillableUnit.CALL).isEmpty());
    assertTrue(calls(Long.MIN_VALUE).quantityFor(BillableUnit.CALL).isEmpty());
  }

  @Test
  @DisplayName("a count of calls prices only the CALL unit, never a unit it cannot measure")
  void callsDoNotPriceOtherUnits() {
    ConsumptionReport report = calls(4L);

    assertTrue(report.quantityFor(BillableUnit.KILOTOKEN).isEmpty());
    assertTrue(report.quantityFor(BillableUnit.OUTPUT_SECOND).isEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = BillableUnit.class,
      names = {"CALL"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("an unmeasured job yields no quantity, so it is released rather than billed")
  void emptyReportDerivesNothing(BillableUnit unit) {
    assertTrue(EMPTY.quantityFor(unit).isEmpty());
  }

  @Test
  @DisplayName("a partial video report derives nothing — frames without fps is not a duration")
  void framesWithoutFps() {
    ConsumptionReport report =
        new ConsumptionReport(null, 48, null, null, null, null, null, null, null, null, null, null);

    assertTrue(report.quantityFor(BillableUnit.OUTPUT_SECOND).isEmpty());
  }

  @Test
  @DisplayName("an image report missing its resolution derives nothing rather than assuming one")
  void imageWithoutResolution() {
    ConsumptionReport report =
        new ConsumptionReport(null, null, null, 1, 4, null, null, null, null, null, null, null);

    assertTrue(report.quantityFor(BillableUnit.IMAGE_STEP).isEmpty());
  }

  @Test
  @DisplayName("zero and negative measurements are treated as absent, never as a free ride")
  void nonPositiveMeasurementsAreAbsent() {
    assertTrue(video(0, 12).quantityFor(BillableUnit.OUTPUT_SECOND).isEmpty());
    assertTrue(video(48, 0).quantityFor(BillableUnit.OUTPUT_SECOND).isEmpty());
    assertTrue(image(1, 4, -512, 512).quantityFor(BillableUnit.IMAGE_STEP).isEmpty());

    ConsumptionReport negativeGpu =
        new ConsumptionReport(
            new BigDecimal("-1"), null, null, null, null, null, null, null, null, null, null, null);
    assertTrue(negativeGpu.quantityFor(BillableUnit.GPU_SECOND).isEmpty());
  }

  @Test
  @DisplayName("a null unit derives nothing rather than throwing at the settlement boundary")
  void nullUnit() {
    assertEquals(Optional.empty(), video(48, 12).quantityFor(null));
  }

  @Test
  @DisplayName("output seconds are the file's own duration when the executor measured it")
  void durationWinsOverFramesAndRate() {
    // ADR-066 reverses ADR-063's fps forcing: the composer no longer passes `-r 30` to make
    // frames/fps divisible, so the duration is what is reported and what is priced.
    ConsumptionReport measured =
        new ConsumptionReport(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new java.math.BigDecimal("2.02"),
            null);

    assertEquals(
        0,
        measured
            .quantityFor(BillableUnit.OUTPUT_SECOND)
            .orElseThrow()
            .compareTo(new java.math.BigDecimal("2.02")));
  }

  @Test
  @DisplayName("frames over rate still answers for an executor that reports no duration")
  void framesRemainTheFallback() {
    ConsumptionReport legacy =
        new ConsumptionReport(null, 150, 30, null, null, null, null, null, null, null, null, null);

    assertEquals(
        0,
        legacy
            .quantityFor(BillableUnit.OUTPUT_SECOND)
            .orElseThrow()
            .compareTo(new java.math.BigDecimal("5")));
  }

  @Test
  @DisplayName("a measured duration outranks a frame count that disagrees with it")
  void aDisagreeingFrameCountDoesNotWin() {
    // The 9.00-for-2.02 case (audit #20) in its new shape: a slideshow that reports the frames it
    // meant to render and a file that is shorter bills the file.
    ConsumptionReport diverging =
        new ConsumptionReport(
            null,
            270,
            30,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new java.math.BigDecimal("2.02"),
            null);

    assertEquals(
        0,
        diverging
            .quantityFor(BillableUnit.OUTPUT_SECOND)
            .orElseThrow()
            .compareTo(new java.math.BigDecimal("2.02")));
  }
}
