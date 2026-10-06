package com.orazaka.billingservice.domain.model;

import java.util.List;

/**
 * A pack's price tag, as the admin console edits it.
 *
 * <p>Deliberately the same grammar as {@link CatalogPlan}: same entitlement rows, same editing
 * shape, so one console component serves both and a pack cannot drift into a second, subtly
 * different notion of "what this unlocks".
 *
 * <p>It used to carry {@code label}, {@code category} and {@code profession} as well. Those are the
 * catalogue's answer — "what is it and what does it do" — and they now live as a {@code pack} row
 * in the studio context alongside {@code studio_i18n}, which already had the i18n mechanism they
 * needed (ADR-036). What is left here is billing's answer: <b>what it costs and what it grants</b>.
 * The split is what stops a marketing copy change from being a deploy of the service that holds the
 * credit ledger.
 *
 * @param packKey the pack's stable key — the opaque join into the catalogue, no FK
 * @param priceCents list price
 * @param includedCredits credits bundled with it
 * @param isActive whether it can be sold
 * @param entitlements what the pack unlocks
 */
public record CatalogPack(
    String packKey,
    int priceCents,
    long includedCredits,
    boolean isActive,
    List<Entitlement> entitlements) {

  /** Compact canonical constructor enforcing the pack's invariants (ERR-106). */
  public CatalogPack {
    if (packKey == null || packKey.isBlank()) {
      throw new IllegalArgumentException("packKey must not be blank");
    }
    if (priceCents < 0 || includedCredits < 0) {
      throw new IllegalArgumentException("price and included credits must be >= 0");
    }
    entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
  }

  /**
   * Returns a copy carrying its entitlement matrix.
   *
   * @param loaded the entitlements read alongside the pack row
   * @return the fully populated pack
   */
  public CatalogPack withEntitlements(List<Entitlement> loaded) {
    return new CatalogPack(packKey, priceCents, includedCredits, isActive, loaded);
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
  public CatalogPack withKey(String key) {
    return new CatalogPack(key, priceCents, includedCredits, isActive, entitlements);
  }
}
