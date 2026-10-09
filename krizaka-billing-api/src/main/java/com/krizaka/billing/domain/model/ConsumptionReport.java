package com.krizaka.billing.domain.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * What an executor actually consumed, in the raw measurements it already has (design §7).
 *
 * <p>Executors report <b>measurements</b>, never billable units. Which unit a request bills in is a
 * property of the pricebook row — {@code (capability, model)} — so a producer that named the unit
 * would be a second, drifting copy of a pricing decision it does not own, and two producers of
 * {@code job.{id}.done} (the job service and the MLX worker) would each carry their own copy. The
 * conversion therefore happens here, against the unit of the rate the hold was pinned to, which
 * also makes a unit mismatch between producer and pricebook unrepresentable rather than merely
 * unlikely.
 *
 * <p>Every field is nullable: an executor reports what it measured and nothing else. A quantity
 * that cannot be derived is {@link Optional#empty()}, which settles nothing — an unmeasured job is
 * released, never billed at a guess.
 *
 * @param gpuSeconds wall-clock occupancy of the accelerator — the calibration basis for every local
 *     model (§7), and the fallback unit when nothing more specific was measured
 * @param frames frames rendered
 * @param fps frames per second of the output, which turns {@code frames} into output seconds
 * @param images images produced
 * @param steps denoising steps per image
 * @param width output width in pixels
 * @param height output height in pixels
 * @param characters characters of input text, for speech synthesis
 * @param audioSeconds duration of the source audio, for transcription
 * @param tokens prompt plus completion tokens
 * @param durationSeconds how long the produced media actually is, read from the file. The
 *     measurement OUTPUT_SECOND wants: {@code frames / fps} only ever approached it, and getting
 *     that quotient to work is why the composer was made to force a constant frame rate on every
 *     file it wrote (ADR-063). Changing the artefact so the meter works is backwards, so the meter
 *     changed instead (ADR-066). Last in the component list because the wire reads by name and only
 *     constructors read by position
 */
public record ConsumptionReport(
    BigDecimal gpuSeconds,
    Integer frames,
    Integer fps,
    Integer images,
    Integer steps,
    Integer width,
    Integer height,
    Long characters,
    BigDecimal audioSeconds,
    Long tokens,
    BigDecimal durationSeconds) {

  private static final BigDecimal PER_KILO = new BigDecimal("1000");
  private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal("60");
  private static final BigDecimal PIXELS_PER_MEGAPIXEL = new BigDecimal("1000000");

  /**
   * Converts this report into the quantity the given unit is priced in.
   *
   * @param unit the unit of the pricebook rate the hold was pinned to
   * @return the measured quantity, or empty when this report carries nothing that unit can be
   *     derived from
   */
  public Optional<BigDecimal> quantityFor(BillableUnit unit) {
    if (unit == null) {
      return Optional.empty();
    }
    return switch (unit) {
      case OUTPUT_SECOND -> outputSeconds();
      case IMAGE_STEP -> megapixelSteps();
      case KILOCHAR -> ratio(characters, PER_KILO);
      case AUDIO_MINUTE -> divide(audioSeconds, SECONDS_PER_MINUTE);
      case KILOTOKEN -> ratio(tokens, PER_KILO);
      case GPU_SECOND -> positive(gpuSeconds);
        // A flat per-invocation fee: reaching a terminal outcome at all is the measurement.
      case CALL -> Optional.of(BigDecimal.ONE);
    };
  }

  /**
   * Output seconds — the file's own duration when the executor measured it, and otherwise the
   * frames it rendered over the rate they play back at.
   *
   * <p>The duration comes first because it is the quantity, not a proxy for it. {@code frames /
   * fps} is exact only at a constant, whole-number frame rate, which is why the composer was made
   * to pass {@code -r 30} to ffmpeg — a meter reaching back into the artefact to make itself work.
   * The fallback stays for executors that report frames and no duration; an executor reporting
   * neither still measures nothing and is released.
   */
  private Optional<BigDecimal> outputSeconds() {
    Optional<BigDecimal> measured = positive(durationSeconds);
    if (measured.isPresent()) {
      return measured;
    }
    if (frames == null || frames <= 0 || fps == null || fps <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        BigDecimal.valueOf(frames).divide(BigDecimal.valueOf(fps), MathContext.DECIMAL64));
  }

  /**
   * Megapixel-steps: {@code images × steps × (width × height ÷ 1e6)}. Resolution belongs in the
   * quantity rather than the pricing key because it is a per-request field — a 4K image costs four
   * times a 1080p one at the same rate, without a pricebook row per resolution (design §8.1).
   */
  private Optional<BigDecimal> megapixelSteps() {
    if (images == null || images <= 0 || steps == null || steps <= 0) {
      return Optional.empty();
    }
    if (width == null || width <= 0 || height == null || height <= 0) {
      return Optional.empty();
    }
    BigDecimal megapixels =
        BigDecimal.valueOf((long) width * height)
            .divide(PIXELS_PER_MEGAPIXEL, MathContext.DECIMAL64);
    return Optional.of(
        BigDecimal.valueOf((long) images * steps).multiply(megapixels, MathContext.DECIMAL64));
  }

  private static Optional<BigDecimal> ratio(Long measured, BigDecimal divisor) {
    if (measured == null || measured <= 0) {
      return Optional.empty();
    }
    return Optional.of(BigDecimal.valueOf(measured).divide(divisor, MathContext.DECIMAL64));
  }

  private static Optional<BigDecimal> divide(BigDecimal measured, BigDecimal divisor) {
    return positive(measured).map(value -> value.divide(divisor, MathContext.DECIMAL64));
  }

  private static Optional<BigDecimal> positive(BigDecimal measured) {
    if (measured == null || measured.compareTo(BigDecimal.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(measured);
  }
}
