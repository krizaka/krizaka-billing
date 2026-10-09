package com.krizaka.billing.domain.model;

/**
 * What one pack costs, and what buying it grants in credits.
 *
 * <p>The whole of billing's answer about a pack. Everything else a marketplace card shows — name,
 * shelf, icon, tagline — belongs to the catalogue in the studio context and is deliberately not
 * here (ADR-036). {@code packKey} is the opaque string that joins the two halves; there is no
 * foreign key between their databases.
 *
 * <p>Never copied into the catalogue's own storage. A duplicated price goes stale, and a stale
 * price is a billing dispute rather than a rendering bug.
 *
 * @param packKey the opaque pack identity, shared with the catalogue
 * @param priceCents the list price; {@code 0} means free, which is different from unknown
 * @param includedCredits credits granted at purchase
 * @param active whether the pack can still be bought — a withdrawn pack keeps its price so the
 *     actors who already own it still see what they paid
 */
public record PackPrice(String packKey, int priceCents, long includedCredits, boolean active) {

  /** Compact canonical constructor enforcing the contract's invariants (ERR-106). */
  public PackPrice {
    if (packKey == null || packKey.isBlank()) {
      throw new IllegalArgumentException("packKey must not be blank");
    }
    if (priceCents < 0) {
      throw new IllegalArgumentException("priceCents must be >= 0, was: " + priceCents);
    }
    if (includedCredits < 0) {
      throw new IllegalArgumentException("includedCredits must be >= 0, was: " + includedCredits);
    }
  }
}
