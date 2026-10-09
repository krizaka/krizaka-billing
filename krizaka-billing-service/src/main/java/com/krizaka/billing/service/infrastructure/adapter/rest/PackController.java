package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.service.application.service.PackPricingService;
import com.krizaka.billing.service.domain.model.CatalogPack;
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
 * The packs resource — what each pack costs and what it grants (ADR-036).
 *
 * <p>It no longer describes a pack. Name, shelf, icon and tagline are the catalogue's answer and
 * are served by {@code /api/v1/studios/packs} in the studio context, which owns the one i18n
 * mechanism this platform has. Billing narrowed to the half it is authoritative for; a marketing
 * copy change must not be a deploy of the service that holds the credit ledger.
 *
 * <p>Same surface as {@link PlanController} on purpose: one console component edits both, because
 * they grant through the same entitlement grammar.
 *
 * <p>Reads are open to any authenticated caller — including the {@code SERVICE} token the Pack
 * catalogue presents, which is how a marketplace page gets its prices in one hop. Writes are
 * admin-only. Who <em>holds</em> a pack is a different resource — {@link
 * PackSubscriptionController}.
 */
@RestController
@RequestMapping("/api/v1/billing/packs")
class PackController {

  private final PackPricingService packPricingService;

  PackController(PackPricingService packPricingService) {
    this.packPricingService =
        Objects.requireNonNull(packPricingService, "PackPricingService cannot be null");
  }

  /**
   * The priced catalogue.
   *
   * @param includeInactive whether to include withdrawn packs
   * @return the packs with their entitlement matrix, by key
   */
  @GetMapping
  List<CatalogPack> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
    return packPricingService.list(includeInactive);
  }

  /**
   * The whole price table, for the Pack catalogue to render a marketplace page from.
   *
   * <p>The literal {@code /prices} segment is matched before {@code /{packKey}} — Spring's path
   * matching prefers the literal — so this stays reachable whatever keys exist. It returns every
   * pack including withdrawn ones, because an actor who bought one must still see what they paid.
   *
   * @return each pack's price and credit grant, in key order
   */
  @GetMapping("/prices")
  List<PackPrice> prices() {
    return packPricingService.prices();
  }

  /**
   * One pack with its entitlement matrix.
   *
   * @param packKey the pack
   * @return {@code 200} with the pack, or {@code 404}
   */
  @GetMapping("/{packKey}")
  ResponseEntity<CatalogPack> find(@PathVariable String packKey) {
    return packPricingService
        .find(packKey)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Creates or replaces a pack's price and entitlements.
   *
   * @param packKey the pack's key, authoritative over any key in the body
   * @param pack the pack to write
   * @param admin the authenticated admin
   * @return the pack as stored
   */
  @PutMapping("/{packKey}")
  @PreAuthorize("hasRole('ADMIN')")
  CatalogPack save(
      @PathVariable String packKey,
      @RequestBody CatalogPack pack,
      @AuthenticationPrincipal Jwt admin) {
    return packPricingService.save(pack.withKey(packKey), admin.getSubject());
  }

  /**
   * Withdraws a pack from sale.
   *
   * @param packKey the pack to withdraw
   * @param admin the authenticated admin
   * @return {@code 204} when withdrawn, {@code 404} when no such pack
   */
  @DeleteMapping("/{packKey}")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> withdraw(@PathVariable String packKey, @AuthenticationPrincipal Jwt admin) {
    return packPricingService.withdraw(packKey, admin.getSubject())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
