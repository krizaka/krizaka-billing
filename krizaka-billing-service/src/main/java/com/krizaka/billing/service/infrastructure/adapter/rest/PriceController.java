package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.service.application.service.PriceCatalogueService;
import com.krizaka.billing.service.domain.model.AdminPriceView;
import com.krizaka.billing.service.domain.model.PriceView;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.PriceChangeRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The price catalogue resource (krizaka/orazaka#417): the public view any surface reads, and the
 * admin view that lists, schedules and shows the history.
 *
 * <p>The public read never returns an {@code internal} entry and shows a {@code preparing} one
 * without figure; like the packs, it is open to any authenticated caller, the {@code SERVICE} token
 * of a web client's BFF included. The admin routes are {@code ADMIN} only.
 */
@RestController
@RequestMapping("/api/v1/billing/prices")
class PriceController {

  private final PriceCatalogueService catalogue;

  PriceController(PriceCatalogueService catalogue) {
    this.catalogue = Objects.requireNonNull(catalogue, "PriceCatalogueService cannot be null");
  }

  /**
   * The public catalogue now.
   *
   * @return public, founder-limited and preparing entries, preparing without figure
   */
  @GetMapping
  List<PriceView> publicCatalogue() {
    return catalogue.publicCatalogue();
  }

  /**
   * Every entry in force, internal ones included.
   *
   * @return the full views
   */
  @GetMapping("/admin")
  @PreAuthorize("hasRole('ADMIN')")
  List<AdminPriceView> admin() {
    return catalogue.adminCatalogue();
  }

  /**
   * Every version of a key, past and scheduled.
   *
   * @param key {@code product.plan}
   * @return the versions, oldest first
   */
  @GetMapping("/admin/{key}/history")
  @PreAuthorize("hasRole('ADMIN')")
  List<AdminPriceView> history(@PathVariable String key) {
    return catalogue.history(key);
  }

  /**
   * Schedules a change from a date.
   *
   * @param key {@code product.plan}
   * @param request the next version and when it starts
   * @param admin the authenticated admin
   * @return the new version
   */
  @PostMapping("/admin/{key}/schedule")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  AdminPriceView schedule(
      @PathVariable String key,
      @RequestBody PriceChangeRequest request,
      @AuthenticationPrincipal Jwt admin) {
    return catalogue.schedule(admin.getSubject(), key, request.toChange(), request.effectiveFrom());
  }

  /**
   * A refused change (past start, unknown key or unit) is the caller's mistake.
   *
   * @param refused why it was refused
   * @return the reason
   */
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  String refused(IllegalArgumentException refused) {
    return refused.getMessage();
  }
}
