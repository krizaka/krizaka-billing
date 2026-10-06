package com.orazaka.billingservice.infrastructure.adapter.rest;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billingservice.application.service.EntitlementService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entitlements resource — what an actor's plan permits, read on the synchronous chat path.
 *
 * <p>Service-to-service, like {@code CreditController}: the gate runs inside the interceptor
 * pipeline, before a request has become anything a user could be shown, and the caller is a process
 * rather than a person. The user-facing view of the same data is the admin-guarded read on {@code
 * WalletController}, which the BFF resolves from the session instead of a path variable.
 *
 * <p>Separate from {@code WalletController} because the two answer different questions on different
 * resources ({@code ERR-128}): entitlement gates <b>access</b>, the wallet reports <b>volume</b>.
 */
@RestController
@RequestMapping("/internal/v1/billing/entitlements")
class EntitlementController {

  private final EntitlementService entitlementService;

  EntitlementController(EntitlementService entitlementService) {
    this.entitlementService =
        Objects.requireNonNull(entitlementService, "EntitlementService cannot be null");
  }

  /**
   * Reads an actor's effective entitlements plus their balance summary.
   *
   * <p>Answers for an actor with no wallet too — being unbilled is not the same as being
   * unentitled, and a gate that refused unknown actors would lock out every new signup.
   *
   * @param actorId the opaque billable subject
   * @return the snapshot in force, carrying the staleness bound a caller may cache it for
   */
  @GetMapping("/{actorId}")
  EntitlementSnapshot entitlements(@PathVariable String actorId) {
    return entitlementService.forActor(actorId);
  }
}
