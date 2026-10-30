package com.krizaka.billing.service.domain.model;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
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
 * @param currency ISO 4217 code of {@code priceCents}; {@code null} (or blank) means the
 *     deployment's own currency ({@code billing.currency}), filled in by the catalogue when the
 *     plan is saved
 * @param rateLimitTierKey opaque reference into identity's rate-limit tiers — no FK
 * @param isPublic whether it appears on the pricing page
 * @param isActive whether it can be subscribed to at all
 * @param overageCreditPriceCents price of one credit used beyond the grant and the free allowance;
 *     {@code null} means no overage (usage stops at the balance)
 * @param trialDays length of the trial a {@code TRIALING} subscription gets; {@code 0} = no trial
 * @param freeMonthlyCredits credits free of charge each month on top of the grant; {@code null} =
 *     none
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
    Integer overageCreditPriceCents,
    int trialDays,
    Long freeMonthlyCredits,
    List<Entitlement> entitlements) {

  /** Compact canonical constructor enforcing the plan's invariants (ERR-106). */
  public CatalogPlan {
    requireText(planKey, "planKey");
    requireText(label, "label");
    requireText(rateLimitTierKey, "rateLimitTierKey");
    if (monthlyCreditGrant < 0 || priceCents < 0) {
      throw new IllegalArgumentException("grant and price must be >= 0");
    }
    if (overageCreditPriceCents != null && overageCreditPriceCents < 0) {
      throw new IllegalArgumentException("overageCreditPriceCents must be >= 0 or null");
    }
    if (trialDays < 0) {
      throw new IllegalArgumentException("trialDays must be >= 0");
    }
    if (freeMonthlyCredits != null && freeMonthlyCredits < 0) {
      throw new IllegalArgumentException("freeMonthlyCredits must be >= 0 or null");
    }
    currency = normaliseCurrency(currency);
    entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
  }

  private static String normaliseCurrency(String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String upper = code.trim().toUpperCase(Locale.ROOT);
    try {
      Currency.getInstance(upper);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("currency must be an ISO 4217 code, was: " + code);
    }
    return upper;
  }

  /**
   * Returns a copy priced in the given currency.
   *
   * @param code the ISO 4217 code
   * @return the re-priced plan (same amount, stated currency)
   */
  public CatalogPlan withCurrency(String code) {
    return new CatalogPlan(
        planKey,
        label,
        tierRank,
        monthlyCreditGrant,
        priceCents,
        code,
        rateLimitTierKey,
        isPublic,
        isActive,
        overageCreditPriceCents,
        trialDays,
        freeMonthlyCredits,
        entitlements);
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
        overageCreditPriceCents,
        trialDays,
        freeMonthlyCredits,
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
        overageCreditPriceCents,
        trialDays,
        freeMonthlyCredits,
        entitlements);
  }

  /**
   * What a period's usage costs beyond what the plan includes.
   *
   * <p>The plan includes its grant plus the free monthly allowance; each credit past that is billed
   * at the overage price. A plan with no overage price never bills past its allowance (usage stops
   * at the balance instead).
   *
   * @param creditsUsed credits used in the period
   * @return the overage in cents, {@code 0} while within the allowance or without an overage price
   */
  public long overageCents(long creditsUsed) {
    if (overageCreditPriceCents == null || creditsUsed < 0) {
      return 0L;
    }
    long included = monthlyCreditGrant + (freeMonthlyCredits == null ? 0L : freeMonthlyCredits);
    return Math.max(0L, creditsUsed - included) * overageCreditPriceCents;
  }
}
