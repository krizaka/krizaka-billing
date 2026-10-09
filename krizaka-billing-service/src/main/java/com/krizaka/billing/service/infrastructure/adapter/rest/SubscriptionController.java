package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.service.application.service.SubscriptionService;
import com.krizaka.billing.service.application.service.SubscriptionService.SubscriptionView;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.PlanChangeRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The subscriptions resource — who is on which plan, and until when.
 *
 * <p>Admin-guarded per method (ERR-128). Every mutation goes through {@link SubscriptionService} so
 * it emits {@code evt.subscription.*}: a direct write here would leave every cached entitlement
 * snapshot in the estate stale with no bound but its own TTL.
 */
@RestController
@RequestMapping("/api/v1/billing/subscriptions")
@PreAuthorize("hasRole('ADMIN')")
class SubscriptionController {

  private final SubscriptionService subscriptionService;

  SubscriptionController(SubscriptionService subscriptionService) {
    this.subscriptionService =
        Objects.requireNonNull(subscriptionService, "SubscriptionService cannot be null");
  }

  /**
   * The subscription in force for an actor.
   *
   * @param actorId the opaque billable subject
   * @return {@code 200} with the subscription, or {@code 404} when the actor is on no plan
   */
  @GetMapping("/{actorId}")
  ResponseEntity<SubscriptionView> forActor(@PathVariable String actorId) {
    return subscriptionService
        .forActor(actorId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Every live subscriber of a plan — what an admin checks before retiring one.
   *
   * @param planKey the plan
   * @return its live subscribers, soonest renewal first
   */
  @GetMapping
  List<SubscriptionView> subscribersOf(@RequestParam String planKey) {
    return subscriptionService.subscribersOf(planKey);
  }

  /**
   * Moves an actor onto a plan — upgrade, downgrade or trial.
   *
   * @param actorId the opaque billable subject
   * @param request the target plan and the status to open it in
   * @return the subscription now in force
   */
  @PostMapping("/{actorId}")
  SubscriptionView changePlan(
      @PathVariable String actorId, @RequestBody PlanChangeRequest request) {
    return subscriptionService.changePlan(actorId, request.planKey(), request.status());
  }

  /**
   * Ends an actor's subscription.
   *
   * @param actorId the opaque billable subject
   * @param immediately {@code true} to cut access now; by default the paid period runs out
   * @return {@code 200} with the final state, or {@code 404} when the actor had no subscription
   */
  @DeleteMapping("/{actorId}")
  ResponseEntity<SubscriptionView> cancel(
      @PathVariable String actorId, @RequestParam(defaultValue = "false") boolean immediately) {
    return subscriptionService
        .cancel(actorId, immediately)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Rolls a subscription into its next period, honouring a pending cancellation.
   *
   * @param actorId the opaque billable subject
   * @return {@code 200} with the subscription after rollover, or {@code 404} when it had none
   */
  @PostMapping("/{actorId}/rollover")
  ResponseEntity<SubscriptionView> rollOver(@PathVariable String actorId) {
    return subscriptionService
        .rollOver(actorId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
