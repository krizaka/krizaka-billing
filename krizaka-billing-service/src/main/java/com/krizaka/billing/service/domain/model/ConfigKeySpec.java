package com.krizaka.billing.service.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * The declared shape of one {@code billing_runtime_config} key: its type, its permitted domain and
 * its code default.
 *
 * <p>This is what makes the settings surface safe to expose. An admin flipping enforcement is
 * changing whether the product takes money from people, so an unknown key or a value outside the
 * domain is rejected rather than stored — otherwise a typo like {@code ENFORCNG} would silently
 * brick authorisation. The code default is the other half: deleting a row reverts cleanly instead
 * of failing at startup.
 *
 * @param key the config key
 * @param valueType {@code string} or {@code int}, mirroring the row's {@code value_type}
 * @param domain the permitted values, or empty when any value of {@code valueType} is acceptable
 * @param defaultValue the value used when the row is absent or unreadable
 */
public record ConfigKeySpec(String key, String valueType, Set<String> domain, String defaultValue) {

  /** Compact canonical constructor; the domain is defensively copied (ERR-106). */
  public ConfigKeySpec {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("config key must not be blank");
    }
    if (!"string".equals(valueType) && !"int".equals(valueType)) {
      throw new IllegalArgumentException("valueType must be 'string' or 'int', got: " + valueType);
    }
    Objects.requireNonNull(domain, "domain must not be null");
    Objects.requireNonNull(defaultValue, "defaultValue must not be null");
    domain = Set.copyOf(domain);
  }

  /**
   * Whether a candidate value is acceptable for this key.
   *
   * @param candidate the proposed value
   * @return {@code true} when it parses as {@link #valueType()} and, if a domain is declared, is a
   *     member of it
   */
  public boolean accepts(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    if ("int".equals(valueType)) {
      try {
        Integer.parseInt(candidate.trim());
      } catch (NumberFormatException ignored) {
        return false;
      }
    }
    return domain.isEmpty() || domain.contains(candidate);
  }
}
