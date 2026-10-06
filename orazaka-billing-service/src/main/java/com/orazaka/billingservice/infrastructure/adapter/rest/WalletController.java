package com.orazaka.billingservice.infrastructure.adapter.rest;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billingservice.application.service.CreditAdjustmentService;
import com.orazaka.billingservice.application.service.CreditLedgerService;
import com.orazaka.billingservice.application.service.EntitlementService;
import com.orazaka.billingservice.domain.exception.AdjustmentCeilingExceededException;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import com.orazaka.billingservice.infrastructure.adapter.rest.dto.AdjustmentRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The wallet resource — balances, the entitlements that go with them, and the one write that moves
 * credits without a hold.
 *
 * <p>Reading another actor's wallet is an admin action; a user reads their own through the BFF,
 * which resolves the actor from the session rather than trusting a path variable.
 *
 * <p>Adjustments live here rather than in a controller of their own because they are a write to
 * <em>this</em> resource (ERR-128): one resource, one controller, split only by genuine
 * sub-resource. The guardrails they need are in {@link CreditAdjustmentService} and in the request
 * record, not in the URL.
 */
@RestController
@RequestMapping("/api/v1/billing/wallets")
class WalletController {

  private final CreditLedgerService creditLedgerService;
  private final EntitlementService entitlementService;
  private final CreditAdjustmentService creditAdjustmentService;

  WalletController(
      CreditLedgerService creditLedgerService,
      EntitlementService entitlementService,
      CreditAdjustmentService creditAdjustmentService) {
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
    this.entitlementService =
        Objects.requireNonNull(entitlementService, "EntitlementService cannot be null");
    this.creditAdjustmentService =
        Objects.requireNonNull(creditAdjustmentService, "CreditAdjustmentService cannot be null");
  }

  /**
   * The signed-in user's own balances.
   *
   * <p>Resolved from the token's subject, never from a path variable — a user reading "their own"
   * wallet by id could read anybody's. Declared before {@code /{actorId}} so the literal wins, and
   * open to any authenticated actor rather than admins, because this is the one wallet read that is
   * not an administrative act.
   *
   * @param user the authenticated actor
   * @return {@code 200} with their wallet, or {@code 404} when they have never been billed
   */
  @GetMapping("/me")
  ResponseEntity<WalletSnapshot> myWallet(@AuthenticationPrincipal Jwt user) {
    return creditLedgerService
        .wallet(user.getSubject())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * The signed-in user's own entitlements and available balance.
   *
   * @param user the authenticated actor
   * @return their snapshot
   */
  @GetMapping("/me/entitlements")
  EntitlementSnapshot myEntitlements(@AuthenticationPrincipal Jwt user) {
    return entitlementService.forActor(user.getSubject());
  }

  /**
   * Reads an actor's balances.
   *
   * @param actorId the opaque billable subject
   * @return {@code 200} with the wallet, or {@code 404} when the actor has never been billed
   */
  @GetMapping("/{actorId}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<WalletSnapshot> wallet(@PathVariable String actorId) {
    return creditLedgerService
        .wallet(actorId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Reads an actor's effective entitlements plus their balance summary.
   *
   * <p>This is what the interceptor caches on the synchronous path, so it answers for an actor with
   * no wallet too — being unbilled is not the same as being unentitled.
   *
   * @param actorId the opaque billable subject
   * @return the snapshot in force
   */
  @GetMapping("/{actorId}/entitlements")
  @PreAuthorize("hasRole('ADMIN')")
  EntitlementSnapshot entitlements(@PathVariable String actorId) {
    return entitlementService.forActor(actorId);
  }

  /**
   * Issues a manual credit adjustment — support goodwill, or a claw-back.
   *
   * <p>Attribution comes from the authenticated admin, never from the request body: a payload that
   * could name its own author would make the audit trail worth nothing.
   *
   * @param actorId the opaque billable subject
   * @param request the signed amount, its bucket and the mandatory reason
   * @param admin the authenticated admin
   * @return the wallet after the adjustment
   */
  @PostMapping("/{actorId}/adjustments")
  @PreAuthorize("hasRole('ADMIN')")
  WalletSnapshot adjust(
      @PathVariable String actorId,
      @RequestBody AdjustmentRequest request,
      @AuthenticationPrincipal Jwt admin) {
    return creditAdjustmentService.adjust(
        actorId, request.bucket(), request.amount(), request.reason(), admin.getSubject());
  }

  /**
   * What an admin has already adjusted today, against their ceiling.
   *
   * <p>Read before the console offers the action, so an admin learns they are near the limit before
   * typing an amount rather than after submitting one.
   *
   * @param admin the authenticated admin
   * @return their absolute total moved today
   */
  @GetMapping("/adjustments/today")
  @PreAuthorize("hasRole('ADMIN')")
  long adjustedToday(@AuthenticationPrincipal Jwt admin) {
    return creditAdjustmentService.adjustedTodayBy(admin.getSubject());
  }

  /**
   * Renders a ceiling refusal so the console can say what is still possible today.
   *
   * @param exception the refusal
   * @return {@code 409} naming the ceiling and the remaining headroom
   */
  @ExceptionHandler(AdjustmentCeilingExceededException.class)
  ResponseEntity<Map<String, Object>> onCeilingExceeded(
      AdjustmentCeilingExceededException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            Map.of(
                "status", "adjustment_ceiling_exceeded",
                "requested", exception.requested(),
                "alreadyAdjusted", exception.alreadyAdjusted(),
                "dailyMax", exception.dailyMax(),
                "remaining", exception.remaining()));
  }
}
