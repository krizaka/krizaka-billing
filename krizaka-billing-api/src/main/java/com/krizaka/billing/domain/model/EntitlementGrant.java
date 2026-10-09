package com.krizaka.billing.domain.model;

import java.util.Objects;

/**
 * One typed key/value grant a pack confers, as it crosses the context boundary.
 *
 * <p>The Tier-1 twin of billing's own {@code Entitlement}: same three fields, deliberately not the
 * same type. Billing's record is Tier-3 and validates against that context's rules; this one exists
 * so a pack manifest can DECLARE a grant without the declaring side depending on the billing
 * service's owned domain (AGENTS.md §2, SEAM-002).
 *
 * @param key the entitlement, e.g. {@code studio.realestate-reels}
 * @param valueType {@code boolean}, {@code int} or {@code string}
 * @param value the value, stored as text and interpreted per {@code valueType}
 */
public record EntitlementGrant(String key, String valueType, String value) {

  /** Compact canonical constructor enforcing the grant's invariants (ERR-106). */
  public EntitlementGrant {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("entitlement key must not be blank");
    }
    Objects.requireNonNull(value, "entitlement value must not be null");
    valueType = valueType == null || valueType.isBlank() ? "string" : valueType;
  }

  /**
   * The grant a Studio needs: its entitlement key, granted as a boolean.
   *
   * @param entitlementKey the {@code studio.<key>} the catalogue gates on
   * @return the grant
   */
  public static EntitlementGrant unlocking(String entitlementKey) {
    return new EntitlementGrant(entitlementKey, "boolean", "true");
  }
}
