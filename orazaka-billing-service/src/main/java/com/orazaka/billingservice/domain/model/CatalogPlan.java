package com.orazaka.billingservice.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * A plan as the admin console edits it — the commercial offer plus what it unlocks.
 *
 * @param planKey the plan's stable key
 * @param label what a user sees
 * @param tierRank ordering for upgrade and downgrade; also how the entry plan is resolved, so it
 *     must be unique and meaningful rather than decorative
 * @param monthlyCreditGrant credits granted at each period start
 * @param priceCents list price
 * @param currency ISO currency code
 * @param rateLimitTierKey opaque reference into identity's rate-limit tiers — no FK
 * @param isPublic whether it appears on the pricing page
 * @param isActive whether it can be subscribed to at all
 * @param entitlements what the plan unlocks
 */
public record CatalogPlan(
    String planKey,
    String label,
    int tierRank,
    long monthlyCreditGrant,
    int priceCents,
    String currency,
    String rateLimitTierKey,
    boolean isPublic,
    boolean isActive,
    List<Entitlement> entitlements) {

  /** Compact canonical constructor enforcing the plan's invariants (ERR-106). */
  public CatalogPlan {
    requireText(planKey, "planKey");
    requireText(label, "label");
    requireText(rateLimitTierKey, "rateLimitTierKey");
    if (monthlyCreditGrant < 0 || priceCents < 0) {
      throw new IllegalArgumentException("grant and price must be >= 0");
    }
    currency = (currency == null || currency.isBlank()) ? "EUR" : currency;
    entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    Objects.requireNonNull(value);
  }

  /**
   * Returns a copy carrying its entitlement matrix.
   *
   * @param loaded the entitlements read alongside the plan row
   * @return the fully populated plan
   */
  public CatalogPlan withEntitlements(List<Entitlement> loaded) {
    return new CatalogPlan(
        planKey,
        label,
        tierRank,
        monthlyCreditGrant,
        priceCents,
        currency,
        rateLimitTierKey,
        isPublic,
        isActive,
        loaded);
  }

  /**
   * Returns a copy under the given key.
   *
   * <p>The URL names the resource; letting a request body rename it would allow one save to write a
   * different entry entirely.
   *
   * @param key the authoritative key
   * @return the re-keyed entry
   */
  public CatalogPlan withKey(String key) {
    return new CatalogPlan(
        key,
        label,
        tierRank,
        monthlyCreditGrant,
        priceCents,
        currency,
        rateLimitTierKey,
        isPublic,
        isActive,
        entitlements);
  }
}
