package com.krizaka.billing.service.domain.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * What one capability × model consumed over a window, and how often it was refused.
 *
 * <p>The two halves belong together because neither is legible alone: revenue with no refusal rate
 * looks healthy while a paywall quietly turns users away, and a refusal rate with no volume cannot
 * distinguish a mispriced capability from an unused one.
 *
 * @param capability the capability measured
 * @param modelName the model measured, or {@code null} for requests priced at the capability
 *     default
 * @param events how many settled usage events the window covered
 * @param creditsCharged what those events cost their actors in total
 * @param refusals how many requests were refused, or would have been under shadow metering
 * @param enforcedRefusals how many of those were actually turned away
 * @param gpuSeconds accelerator occupancy the settled events consumed — the cost side of the
 *     margin, and {@code null} for traffic settled before executors reported it
 */
public record CapabilityUsage(
    String capability,
    String modelName,
    long events,
    long creditsCharged,
    long refusals,
    long enforcedRefusals,
    BigDecimal gpuSeconds) {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Compact canonical constructor enforcing the row's invariants (ERR-106). */
  public CapabilityUsage {
    if (events < 0 || creditsCharged < 0 || refusals < 0 || enforcedRefusals < 0) {
      throw new IllegalArgumentException("usage totals must be >= 0");
    }
    if (enforcedRefusals > refusals) {
      throw new IllegalArgumentException("enforced refusals cannot exceed refusals");
    }
  }

  /**
   * Credits charged per GPU-second consumed — the margin signal of design §7.
   *
   * <p>Not money: credits per unit of the only cost we can actually observe on owned hardware.
   * Comparing it across capabilities is what tells you which one is sold at a loss, and comparing
   * it over time is what tells you a model change moved the economics.
   *
   * @return credits per GPU-second, or empty when no occupancy was recorded for this traffic — a
   *     blank rather than a zero, because unmeasured and free are not the same claim
   */
  public Optional<BigDecimal> creditsPerGpuSecond() {
    if (gpuSeconds == null || gpuSeconds.compareTo(BigDecimal.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        BigDecimal.valueOf(creditsCharged)
            .divide(gpuSeconds, MathContext.DECIMAL64)
            .setScale(2, RoundingMode.HALF_UP));
  }

  /**
   * The pricing-health metric of design §12: refusals as a share of everything attempted.
   *
   * <p>A high rate means the price is wrong or the paywall is in the wrong place. Counting shadow
   * refusals alongside enforced ones is what lets the number mean something during DRY_RUN, when
   * nothing is actually being turned away yet.
   *
   * @return the percentage of attempts that were refused, zero when nothing was attempted
   */
  public BigDecimal refusalRatePercent() {
    long attempts = events + refusals;
    if (attempts == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(refusals)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(attempts), MathContext.DECIMAL64)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Average cost of a successful request — what a user actually feels per action.
   *
   * @return credits per settled event, zero when nothing settled
   */
  public BigDecimal averageCreditsPerEvent() {
    if (events == 0) {
      return BigDecimal.ZERO;
    }
    return BigDecimal.valueOf(creditsCharged)
        .divide(BigDecimal.valueOf(events), MathContext.DECIMAL64)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
