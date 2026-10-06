package com.orazaka.billing.domain.port;

import com.orazaka.billing.domain.model.PackPrice;
import java.util.Map;
import java.util.Set;

/**
 * Read side of the billing contract for packs: what does each of these cost?
 *
 * <p>Consumed by the Pack catalogue in the studio context, which owns everything about a pack
 * except its price (ADR-036). The catalogue does not store the price precisely so that this call
 * exists: one source of truth for money, read at render time.
 *
 * <p><b>One call for the page, never one per pack.</b> The signature takes a set for that reason —
 * the same discipline {@code StudioAccessService.evaluateAll} states for entitlements, and for the
 * same arithmetic: a per-row lookup turns one browse into as many service hops as there are cards.
 *
 * <p><b>Degrades, never fails.</b> An implementation that cannot reach billing returns the keys it
 * knows about — possibly none — rather than throwing. A marketing page that 500s because the
 * billing service is restarting is a self-inflicted outage, and a card with no price is a card that
 * renders "—".
 */
public interface PackPricingClient {

  /**
   * Prices a whole catalogue page in one call.
   *
   * @param packKeys the opaque pack keys on the page; an empty set yields an empty map
   * @return the price of each pack that billing knows about, keyed by pack key — keys billing could
   *     not answer for are <b>absent</b>, which the caller must render as unknown rather than as
   *     free
   */
  Map<String, PackPrice> prices(Set<String> packKeys);
}
