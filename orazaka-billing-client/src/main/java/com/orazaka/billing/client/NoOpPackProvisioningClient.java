package com.orazaka.billing.client;

import com.orazaka.billing.domain.model.PackProvision;
import com.orazaka.billing.domain.port.PackProvisioningClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Null Object: records that a pack's price and grants were not written, and lets the install stand.
 *
 * <p><b>Why this skips where {@link HttpPackProvisioningClient} throws.</b> The two failures are
 * not the same failure. Billing enabled and refusing means the grant should exist and does not — a
 * customer will pay and stay locked out, so the install must be abandoned. Billing deliberately
 * switched off means nothing is gated at all: {@code NoOpEntitlementProvider} returns {@code
 * EntitlementSnapshot.unresolved}, whose {@code resolved()} is {@code false}, and {@code
 * StudioAccessService} is explicitly forbidden from refusing an actor on an unresolved snapshot.
 * There is no one to lock out.
 *
 * <p>Refusing here instead would make the platform's own flagship pack uninstallable on every
 * deployment that runs without a ledger — which in the local phase is the default ({@code
 * BILLING_ENABLED=false}). That is a fitness function firing on a condition it was not written for.
 *
 * <p>It logs at WARN rather than DEBUG because the pack IS installed with no price: the catalogue
 * will show it, and the fact that buying it does nothing must be visible in the log of the run that
 * installed it, not discovered later.
 */
final class NoOpPackProvisioningClient implements PackProvisioningClient {

  private static final Logger log = LoggerFactory.getLogger(NoOpPackProvisioningClient.class);

  @Override
  public void provision(PackProvision provision) {
    log.warn(
        "Billing is disabled (orazaka.billing.enabled=false): pack {} is installed WITHOUT its price"
            + " ({} cents / {} credits) or its {} entitlement grant(s). Nothing gates access on this"
            + " deployment, so no actor is locked out — but the pack is not sellable until billing"
            + " is wired and the bundle re-installed.",
        provision.packKey(),
        provision.priceCents(),
        provision.includedCredits(),
        provision.entitlements().size());
  }

  @Override
  public boolean withdraw(String packKey) {
    log.debug("Billing disabled; nothing to withdraw for {}", packKey);
    return false;
  }
}
