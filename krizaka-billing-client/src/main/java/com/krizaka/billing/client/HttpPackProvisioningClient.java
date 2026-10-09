package com.krizaka.billing.client;

import com.krizaka.billing.domain.exception.PackProvisioningException;
import com.krizaka.billing.domain.model.PackProvision;
import com.krizaka.billing.domain.port.PackProvisioningClient;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Writes a pack's price and grants to the billing service.
 *
 * <p><b>Fails hard, unlike every other adapter in this module.</b> {@code HttpPackPricingClient}
 * degrades to an empty price table and {@code HttpEntitlementProvider} fails open, because both sit
 * on an interactive path where a billing outage must not take a page down. This one sits inside a
 * pack install, where the opposite is true: swallowing the failure produces a catalogue entry
 * promising an entitlement billing cannot grant, and the first person to notice is a customer who
 * has paid and is locked out (ADR-036, invariant #3). The installer needs the exception so it can
 * compensate.
 *
 * <p>No cache and no retry: this is an admin-speed write, called once per install. Retrying it
 * would only widen the window in which the installer is holding a transaction open.
 */
final class HttpPackProvisioningClient implements PackProvisioningClient {

  private static final Logger log = LoggerFactory.getLogger(HttpPackProvisioningClient.class);

  private static final String PACK_PATH = "/internal/v1/billing/packs/{packKey}";

  private final RestClient restClient;

  HttpPackProvisioningClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public void provision(PackProvision provision) {
    Objects.requireNonNull(provision, "provision must not be null");
    try {
      restClient
          .put()
          .uri(PACK_PATH, provision.packKey())
          .body(provision)
          .retrieve()
          .toBodilessEntity();
      log.info(
          "Provisioned pack {} at {} cents with {} entitlement(s)",
          provision.packKey(),
          provision.priceCents(),
          provision.entitlements().size());
    } catch (RestClientException e) {
      throw new PackProvisioningException(
          "Could not provision pack " + provision.packKey() + " in billing", e);
    }
  }

  @Override
  public boolean withdraw(String packKey) {
    if (packKey == null || packKey.isBlank()) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(
          restClient
              .delete()
              .uri(PACK_PATH, packKey)
              .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false));
    } catch (RestClientException e) {
      // Compensation is best-effort by construction: it runs while another failure is already
      // being handled, and throwing here would replace the error the caller must report with
      // this one. Logged loudly because a pack left sellable is a pack someone can buy.
      log.error("Could not withdraw pack {} while compensating an install", packKey, e);
      return false;
    }
  }
}
