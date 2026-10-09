package com.krizaka.billing.client;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.domain.port.PackPricingClient;
import java.util.Map;
import java.util.Set;

/**
 * Null Object: knows no price for any pack.
 *
 * <p>Same purpose as its entitlement and credit counterparts — {@code
 * krizaka.billing.enabled=false} stays a real answer instead of a branch at each call site
 * (ERR-127). Empty rather than zero for the reason the port states: with billing unwired there is
 * no price to read, and a fabricated {@code 0} would advertise every pack as free.
 */
final class NoOpPackPricingClient implements PackPricingClient {

  @Override
  public Map<String, PackPrice> prices(Set<String> packKeys) {
    return Map.of();
  }
}
