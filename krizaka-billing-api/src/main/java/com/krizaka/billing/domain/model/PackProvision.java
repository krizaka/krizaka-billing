package com.krizaka.billing.domain.model;

import java.util.List;

/**
 * What a pack costs and what buying it grants — the billing half of a pack install.
 *
 * <p>Applied <b>before</b> the catalogue rows that depend on it. A {@code pack_studio} row whose
 * matching {@code billing_pack_entitlement} does not exist is ADR-036's invariant #3 violated: the
 * omission is silent, it looks like a billing bug, and it locks out exactly the customer who just
 * paid. Ordering the install so the grant exists first is what makes the window impossible rather
 * than merely unlikely.
 *
 * @param packKey the opaque key mirrored from the catalogue, with no FK between the two databases
 * @param priceCents list price
 * @param includedCredits credits bundled with the purchase
 * @param isActive whether it can be sold
 * @param entitlements what the purchase unlocks; defensively copied
 */
public record PackProvision(
    String packKey,
    int priceCents,
    long includedCredits,
    boolean isActive,
    List<EntitlementGrant> entitlements) {

  /** Compact canonical constructor enforcing the provision's invariants (ERR-106). */
  public PackProvision {
    if (packKey == null || packKey.isBlank()) {
      throw new IllegalArgumentException("packKey must not be blank");
    }
    if (priceCents < 0 || includedCredits < 0) {
      throw new IllegalArgumentException("price and included credits must be >= 0");
    }
    entitlements = entitlements == null ? List.of() : List.copyOf(entitlements);
  }
}
