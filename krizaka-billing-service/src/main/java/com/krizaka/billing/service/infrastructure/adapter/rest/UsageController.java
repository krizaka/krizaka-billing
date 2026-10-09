package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.service.application.service.UsageAnalyticsService;
import com.krizaka.billing.service.domain.model.ActorConsumption;
import com.krizaka.billing.service.domain.model.CapabilityUsage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The usage resource — what the platform consumed, and what it refused.
 *
 * <p>Backs the analytics screen of design §12, whose headline number is the refusal rate: high
 * means the price is wrong or the paywall sits in the wrong place. During DRY_RUN it counts what
 * enforcement <em>would</em> have refused, which is the calibration signal phase 0 exists to gather
 * — the reason this screen is worth having before enforcement is ever switched on.
 */
@RestController
@RequestMapping("/api/v1/billing/usage")
@PreAuthorize("hasRole('ADMIN')")
class UsageController {

  /** Matches the pricebook preview's replay window, so the two screens agree on "recent". */
  private static final int DEFAULT_WINDOW_DAYS = 30;

  private static final int DEFAULT_TOP_CONSUMERS = 20;

  private final UsageAnalyticsService usageAnalyticsService;

  UsageController(UsageAnalyticsService usageAnalyticsService) {
    this.usageAnalyticsService =
        Objects.requireNonNull(usageAnalyticsService, "UsageAnalyticsService cannot be null");
  }

  /**
   * Consumption and refusals per capability × model.
   *
   * @param days how far back to look
   * @return one row per capability × model, heaviest spend first
   */
  @GetMapping("/capabilities")
  List<CapabilityUsage> byCapability(
      @RequestParam(defaultValue = "" + DEFAULT_WINDOW_DAYS) int days) {
    return usageAnalyticsService.byCapability(Duration.ofDays(days));
  }

  /**
   * The heaviest consumers.
   *
   * @param days how far back to look
   * @param limit how many actors to return
   * @return the top consumers, heaviest spend first
   */
  @GetMapping("/top-consumers")
  List<ActorConsumption> topConsumers(
      @RequestParam(defaultValue = "" + DEFAULT_WINDOW_DAYS) int days,
      @RequestParam(defaultValue = "" + DEFAULT_TOP_CONSUMERS) int limit) {
    return usageAnalyticsService.topConsumers(Duration.ofDays(days), limit);
  }
}
