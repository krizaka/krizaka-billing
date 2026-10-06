package com.orazaka.billingservice.infrastructure.adapter.rest;

import com.orazaka.billingservice.application.service.BillingConfigurationService;
import com.orazaka.billingservice.domain.model.ConfigKeySpec;
import com.orazaka.billingservice.infrastructure.adapter.rest.dto.ConfigurationUpdateRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The billing configuration resource — the Settings surface of the admin console.
 *
 * <p>Admin access is a method concern, not a class name (ERR-128): this is {@code
 * ConfigurationController}, not {@code AdminConfigurationController}, gated by
 * {@code @PreAuthorize}.
 *
 * <p>Writes go through the whitelist, so an unknown key or an out-of-domain value is a {@code 400}
 * rather than a stored typo. That matters more here than anywhere else in the product: {@code
 * billing.enforcement.mode} decides whether Orazaka takes money from people, and {@code ENFORCNG}
 * must not be a way to silently disable authorisation.
 */
@RestController
@RequestMapping("/api/v1/billing/configuration")
@PreAuthorize("hasRole('ADMIN')")
class ConfigurationController {

  private final BillingConfigurationService configurationService;

  ConfigurationController(BillingConfigurationService configurationService) {
    this.configurationService =
        Objects.requireNonNull(configurationService, "BillingConfigurationService cannot be null");
  }

  /**
   * The declared key vocabulary — type, permitted domain and code default per key.
   *
   * @return the whitelist backing the Settings screen's inputs
   */
  @GetMapping
  Map<String, ConfigKeySpec> whitelist() {
    return configurationService.whitelist();
  }

  /**
   * Applies one validated configuration change.
   *
   * @param request the key and its proposed value
   * @return {@code 204} when stored, {@code 400} when the key is unknown or the value is outside
   *     its declared domain
   */
  @PatchMapping
  ResponseEntity<Void> update(
      @RequestBody ConfigurationUpdateRequest request, Authentication authentication) {
    boolean applied =
        configurationService.update(request.key(), request.value(), authentication.getName());
    return applied ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
  }
}
