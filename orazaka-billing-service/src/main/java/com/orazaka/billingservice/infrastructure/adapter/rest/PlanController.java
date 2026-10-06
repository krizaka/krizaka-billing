package com.orazaka.billingservice.infrastructure.adapter.rest;

import com.orazaka.billingservice.application.service.PlanCatalogService;
import com.orazaka.billingservice.domain.model.CatalogPlan;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plans resource — what Orazaka sells.
 *
 * <p>Reading the public catalogue is not an admin action: the pricing page needs it, and gating it
 * would mean duplicating the offer into the front end, which is exactly the drift "nothing
 * commercial in code" exists to prevent (§12). Writes are admin-only, per method (ERR-128).
 */
@RestController
@RequestMapping("/api/v1/billing/plans")
class PlanController {

  private final PlanCatalogService planCatalogService;

  PlanController(PlanCatalogService planCatalogService) {
    this.planCatalogService = Objects.requireNonNull(planCatalogService, "PlanCatalogService null");
  }

  /**
   * The catalogue.
   *
   * @param includeInactive whether to include retired plans — admins need them to explain the
   *     subscriptions still on them
   * @return the plans, cheapest tier first
   */
  @GetMapping
  List<CatalogPlan> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
    return planCatalogService.list(includeInactive);
  }

  /**
   * One plan with its entitlement matrix.
   *
   * @param planKey the plan
   * @return {@code 200} with the plan, or {@code 404}
   */
  @GetMapping("/{planKey}")
  ResponseEntity<CatalogPlan> find(@PathVariable String planKey) {
    return planCatalogService
        .find(planKey)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Creates or replaces a plan. Idempotent by key, so the console can save without tracking whether
   * it is editing or creating.
   *
   * @param planKey the plan's key, authoritative over any key in the body
   * @param plan the plan to write
   * @param admin the authenticated admin
   * @return the plan as stored
   */
  @PutMapping("/{planKey}")
  @PreAuthorize("hasRole('ADMIN')")
  CatalogPlan save(
      @PathVariable String planKey,
      @RequestBody CatalogPlan plan,
      @AuthenticationPrincipal Jwt admin) {
    // The path names the resource; a body that could rename it would let one save write another
    // plan entirely.
    CatalogPlan target = plan.withKey(planKey);
    return planCatalogService.save(target, admin.getSubject());
  }

  /**
   * Retires a plan — deactivated, never deleted, because subscriptions reference it.
   *
   * @param planKey the plan to retire
   * @param admin the authenticated admin
   * @return {@code 204} when retired, {@code 404} when no such plan
   */
  @DeleteMapping("/{planKey}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> retire(@PathVariable String planKey, @AuthenticationPrincipal Jwt admin) {
    return planCatalogService.retire(planKey, admin.getSubject())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
