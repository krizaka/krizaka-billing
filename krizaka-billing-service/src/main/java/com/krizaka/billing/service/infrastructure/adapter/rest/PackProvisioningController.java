package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.domain.model.EntitlementGrant;
import com.krizaka.billing.domain.model.PackProvision;
import com.krizaka.billing.service.application.service.PackPricingService;
import com.krizaka.billing.service.domain.model.CatalogPack;
import com.krizaka.billing.service.domain.model.Entitlement;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine surface of the pack resource: an installer writing what a pack costs and grants.
 *
 * <p>Separate from {@link PackController} because the surfaces differ in who calls them and how
 * they authenticate, which is the one structural split {@code SecurityConfig} draws: {@code
 * /internal/v1/**} is service-to-service and gated on the {@code SERVICE} authority, while the
 * {@code /api/v1} pack endpoints are an admin with a session acting on their own behalf. The
 * installer in the studio context has no session to present and is not an admin; it is a service
 * applying a manifest an admin approved.
 *
 * <p>The two surfaces share {@link PackPricingService}, so there is exactly one implementation of
 * "write a pack's price and grants" no matter which door it arrives through.
 */
@RestController
@RequestMapping("/internal/v1/billing/packs")
class PackProvisioningController {

  /**
   * Who the audit trail credits an installed pack to.
   *
   * <p>Not an admin's id: the caller is a service, and attributing the write to whichever human
   * happened to be logged in somewhere would put a name on a change they did not make.
   */
  private static final String INSTALLER = "pack-installer";

  private final PackPricingService packPricingService;

  PackProvisioningController(PackPricingService packPricingService) {
    this.packPricingService =
        Objects.requireNonNull(packPricingService, "PackPricingService cannot be null");
  }

  /**
   * Creates or replaces a pack's price and entitlement matrix.
   *
   * @param packKey the pack's key, authoritative over any key in the body
   * @param provision the price and grants to write
   * @return the pack as stored
   */
  @PutMapping("/{packKey}")
  CatalogPack provision(@PathVariable String packKey, @RequestBody PackProvision provision) {
    List<Entitlement> entitlements =
        provision.entitlements().stream().map(PackProvisioningController::toEntitlement).toList();
    return packPricingService.save(
        new CatalogPack(
            packKey,
            provision.priceCents(),
            provision.includedCredits(),
            provision.isActive(),
            entitlements),
        INSTALLER);
  }

  /**
   * Withdraws a pack from sale, so a failed install can undo the half it already applied.
   *
   * @param packKey the pack to withdraw
   * @return {@code 204} when withdrawn, {@code 404} when no such pack
   */
  @DeleteMapping("/{packKey}")
  ResponseEntity<Void> withdraw(@PathVariable String packKey) {
    return packPricingService.withdraw(packKey, INSTALLER)
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /** Crosses the Tier-1 grant into this context's own {@code Entitlement}, which validates it. */
  private static Entitlement toEntitlement(EntitlementGrant grant) {
    return new Entitlement(grant.key(), grant.valueType(), grant.value());
  }
}
