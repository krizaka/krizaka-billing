package com.krizaka.billing.client;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.domain.port.PackPricingClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the pack price table from the billing service, cached for a short bound.
 *
 * <p><b>One call for the whole catalogue, not one per pack.</b> The remote endpoint returns every
 * pack's price and this adapter filters locally, so a marketplace of forty cards costs one hop
 * however many cards it holds — the arithmetic {@code StudioAccessService.evaluateAll} spells out
 * for entitlements.
 *
 * <p>The cache is the whole table behind one {@link AtomicReference}, not a per-key map: the table
 * is small, it is always read wholesale, and a single reference means a refresh replaces a
 * consistent snapshot rather than leaving a half-updated map (AGENTS.md §4 — no {@code
 * synchronized} around I/O).
 *
 * <p>Fails <b>soft</b>: an unreachable billing service yields an empty table, and the catalogue
 * renders its cards with no price rather than returning 500. A marketing page that goes down
 * because the credit ledger is restarting is a self-inflicted outage; a card reading "—" is a
 * degraded card the user can still click.
 */
final class HttpPackPricingClient implements PackPricingClient {

  private static final Logger log = LoggerFactory.getLogger(HttpPackPricingClient.class);
  private static final String PRICES_PATH = "/api/v1/billing/packs/prices";

  /**
   * How long a fetched price table is served before a refresh.
   *
   * <p>A constant rather than a property: this is a staleness bound on a cache, which is
   * infrastructure behaviour and not something an admin tunes at runtime (AGENTS.md §4). A minute
   * is the window in which a price change is invisible on the marketplace — short enough that an
   * admin editing a price sees it, long enough that a browse burst is one hop.
   */
  private static final Duration CACHE_TTL = Duration.ofMinutes(1);

  /** How soon to retry after a failed fetch — this is an outage, not a policy decision. */
  private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private static final ParameterizedTypeReference<List<PackPrice>> PRICE_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final AtomicReference<CachedPrices> cache = new AtomicReference<>(CachedPrices.empty());

  HttpPackPricingClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public Map<String, PackPrice> prices(Set<String> packKeys) {
    if (packKeys == null || packKeys.isEmpty()) {
      return Map.of();
    }
    Map<String, PackPrice> table = table();
    Map<String, PackPrice> requested = new LinkedHashMap<>();
    for (String packKey : packKeys) {
      PackPrice price = table.get(packKey);
      // Absent rather than a zero placeholder: the caller must be able to tell "free" from
      // "billing did not answer", and a fabricated zero is a price we would be held to.
      if (price != null) {
        requested.put(packKey, price);
      }
    }
    return Map.copyOf(requested);
  }

  private Map<String, PackPrice> table() {
    CachedPrices cached = cache.get();
    if (cached.expiresAt().isAfter(Instant.now())) {
      return cached.prices();
    }
    try {
      List<PackPrice> fresh = restClient.get().uri(PRICES_PATH).retrieve().body(PRICE_LIST);
      Map<String, PackPrice> table = index(fresh);
      cache.set(new CachedPrices(table, Instant.now().plus(CACHE_TTL)));
      return table;
    } catch (RestClientException e) {
      log.error("Pack price lookup failed — the catalogue renders without prices", e);
      cache.set(new CachedPrices(Map.of(), Instant.now().plus(RETRY_AFTER)));
      return Map.of();
    }
  }

  private static Map<String, PackPrice> index(List<PackPrice> prices) {
    if (prices == null) {
      return Map.of();
    }
    Map<String, PackPrice> table = new LinkedHashMap<>();
    for (PackPrice price : prices) {
      table.put(price.packKey(), price);
    }
    return Map.copyOf(table);
  }

  /** One consistent snapshot of the price table and the instant it stops being served. */
  private record CachedPrices(Map<String, PackPrice> prices, Instant expiresAt) {

    static CachedPrices empty() {
      return new CachedPrices(Map.of(), Instant.EPOCH);
    }
  }
}
