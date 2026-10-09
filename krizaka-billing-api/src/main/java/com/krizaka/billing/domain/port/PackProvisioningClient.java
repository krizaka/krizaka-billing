package com.krizaka.billing.domain.port;

import com.krizaka.billing.domain.model.PackProvision;

/**
 * Write side of the billing contract for packs: what does this pack cost, and what does it grant?
 *
 * <p>The complement of {@link PackPricingClient}, and separate from it for the same reason the
 * capability registry splits read from write: {@code PackPricingClient} serves a marketing page and
 * is explicitly allowed to degrade to "price unknown" on an outage, because a card that renders "—"
 * beats a 500. Provisioning may not degrade at all — a pack whose grant was silently skipped is a
 * pack a customer can buy and not use.
 *
 * <p>Called by a pack installer in another bounded context. The studio context does not write
 * {@code billing_pack}: it is another context's table, in another database, owned by the service
 * that holds the credit ledger (SEAM-001).
 */
public interface PackProvisioningClient {

  /**
   * Creates or replaces a pack's price and its entitlement matrix.
   *
   * <p>Idempotent by pack key, so re-running a failed install rewrites rather than duplicates.
   *
   * @param provision the price and grants to write
   * @throws com.krizaka.billing.domain.exception.PackProvisioningException when billing refused the
   *     write or could not be reached — the caller must abandon and compensate the install rather
   *     than continue into a catalogue that promises an entitlement nobody can be granted
   */
  void provision(PackProvision provision);

  /**
   * Withdraws a pack from sale.
   *
   * <p>Exists for compensation: when a later slice of an install fails, the price and grants this
   * one wrote have to stop being sellable.
   *
   * @param packKey the pack to withdraw
   * @return {@code true} when a pack was withdrawn, {@code false} when billing knew of none
   */
  boolean withdraw(String packKey);
}
