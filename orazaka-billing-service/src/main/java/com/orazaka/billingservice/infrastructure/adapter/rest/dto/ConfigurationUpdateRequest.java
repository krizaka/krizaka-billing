package com.orazaka.billingservice.infrastructure.adapter.rest.dto;

/**
 * Body of {@code PATCH /api/v1/billing/configuration}.
 *
 * <p>One key per call, deliberately: a bulk update makes a partial failure ambiguous, and these are
 * the settings where "some of it applied" is the worst possible outcome.
 *
 * @param key the config key to change
 * @param value the proposed value, validated against the key's declared domain before it is stored
 */
public record ConfigurationUpdateRequest(String key, String value) {

  /** Compact canonical constructor — the boundary validates shape, the service validates domain. */
  public ConfigurationUpdateRequest {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
