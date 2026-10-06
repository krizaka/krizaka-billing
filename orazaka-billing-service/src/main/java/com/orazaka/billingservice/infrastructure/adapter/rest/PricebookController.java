package com.orazaka.billingservice.infrastructure.adapter.rest;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billingservice.application.service.CreditLedgerService;
import com.orazaka.billingservice.application.service.PricebookService;
import com.orazaka.billingservice.application.service.PricingService;
import com.orazaka.billingservice.domain.model.CostEstimate;
import com.orazaka.billingservice.domain.model.MarginPreview;
import com.orazaka.billingservice.domain.model.PricebookRate;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import com.orazaka.billingservice.infrastructure.adapter.rest.dto.PricebookPublishRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pricebook resource — what compute costs.
 *
 * <p>Human-facing, so {@code /api/v1} behind the session JWT, unlike the machine-to-machine credits
 * resource. Editing is admin-guarded per method (ERR-128) — but {@code /estimate} is not, because
 * showing a user what an action will cost before they commit to it is the design's stated
 * non-negotiable (§12) and gating it behind an admin role would make the paywall arrive only after
 * the debit.
 *
 * <p>Publishing is deliberately two calls, not one. The design's non-negotiable is that no price
 * reaches production without the admin having seen what it would have done to the last 30 days of
 * traffic (§12), and a preview folded into the publish response arrives too late to inform the
 * decision it exists to inform.
 */
@RestController
@RequestMapping("/api/v1/billing/pricebook")
class PricebookController {

  private final PricebookService pricebookService;
  private final PricingService pricingService;
  private final CreditLedgerService creditLedgerService;

  PricebookController(
      PricebookService pricebookService,
      PricingService pricingService,
      CreditLedgerService creditLedgerService) {
    this.pricebookService = Objects.requireNonNull(pricebookService, "PricebookService is null");
    this.pricingService = Objects.requireNonNull(pricingService, "PricingService cannot be null");
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
  }

  /**
   * Every rate currently in force.
   *
   * @return the live pricebook
   */
  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping
  List<PricebookRate> current() {
    return pricebookService.current();
  }

  /**
   * Every version of one capability × model, newest first.
   *
   * @param capability the capability
   * @param modelName the model, or omitted for the capability default
   * @return the rate's history, for diff and rollback
   */
  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping("/history")
  List<PricebookRate> history(
      @RequestParam BillableCapability capability,
      @RequestParam(required = false) String modelName) {
    return pricebookService.history(capability, modelName);
  }

  /**
   * What a proposed rate would have charged over the last 30 days of real traffic.
   *
   * @param request the proposed rate
   * @return the replay, including how much evidence it rests on
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping("/preview")
  MarginPreview preview(@RequestBody PricebookPublishRequest request) {
    return pricebookService.preview(
        request.capability(),
        request.modelName(),
        request.creditsPerUnit(),
        request.minimumCredits());
  }

  /**
   * Publishes a rate, closing the current one rather than overwriting it.
   *
   * @param request the rate to publish
   * @param admin the authenticated admin, attributed on the snapshot
   * @return {@code 201} with the newly live rate
   */
  @PreAuthorize("hasRole('ADMIN')")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  PricebookRate publish(
      @RequestBody PricebookPublishRequest request, @AuthenticationPrincipal Jwt admin) {
    return pricebookService.publish(
        request.capability(),
        request.modelName(),
        request.unit(),
        request.creditsPerUnit(),
        request.minimumCredits(),
        request.estimateCredits(),
        admin.getSubject());
  }

  /**
   * What an action would cost the signed-in user, and whether they can cover it.
   *
   * <p>Read before the user commits, which is the whole point: an unexpected debit on a job they
   * did not know was expensive is the number-one support ticket in credit products. The estimate is
   * the pricebook's, not a client-side guess, so the number shown is the number the hold will
   * reserve.
   *
   * @param capability the capability about to run
   * @param modelName the resolved model, or omitted for the capability default
   * @param user the authenticated actor
   * @return the estimate and their headroom against it
   */
  @GetMapping("/estimate")
  CostEstimate estimate(
      @RequestParam BillableCapability capability,
      @RequestParam(required = false) String modelName,
      @AuthenticationPrincipal Jwt user) {
    PricebookRate rate = pricingService.currentRate(capability, modelName);
    long available =
        creditLedgerService.wallet(user.getSubject()).map(WalletSnapshot::available).orElse(0L);
    return new CostEstimate(
        capability,
        rate.modelName(),
        rate.estimateCredits(),
        available,
        available >= rate.estimateCredits());
  }
}
