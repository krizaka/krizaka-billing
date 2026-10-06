package com.orazaka.billing.client;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.EntitlementProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads an actor's entitlements from the billing service, cached to its own staleness bound.
 *
 * <p>The gate runs on every chat turn, so an uncached read would put a network hop in front of
 * every message. The snapshot carries {@code expiresAt} precisely so a caller can cache it without
 * inventing a TTL, and honouring that field rather than a local constant means the bound is a
 * billing-side decision — one place to change how quickly a downgrade takes effect.
 *
 * <p>Fails <b>open</b>: a billing service that cannot be reached must not lock every user out of
 * chat. The credit hold that follows is the second gate and fails on its own terms; an entitlement
 * lookup is the cheaper half to lose.
 */
final class HttpEntitlementProvider implements EntitlementProvider {

  private static final Logger log = LoggerFactory.getLogger(HttpEntitlementProvider.class);
  private static final String ENTITLEMENTS_PATH = "/internal/v1/billing/entitlements/";

  /** How soon to retry after a failed lookup — this is an outage, not a policy decision. */
  private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

  private final RestClient restClient;
  private final Map<String, EntitlementSnapshot> cache = new ConcurrentHashMap<>();

  HttpEntitlementProvider(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public EntitlementSnapshot forActor(String actorId) {
    EntitlementSnapshot cached = cache.get(actorId);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return cached;
    }
    try {
      EntitlementSnapshot fresh =
          restClient
              .get()
              .uri(ENTITLEMENTS_PATH + actorId)
              .retrieve()
              .body(EntitlementSnapshot.class);
      if (fresh == null) {
        return unresolved(actorId);
      }
      cache.put(actorId, fresh);
      return fresh;
    } catch (RestClientException e) {
      log.error("Entitlement lookup failed for actor={} — allowing through", actorId, e);
      return unresolved(actorId);
    }
  }

  /**
   * Drops an actor's cached snapshot, so the next gate check reads their current plan.
   *
   * <p>Called when {@code evt.subscription.*} says their standing changed. The TTL alone would get
   * there eventually, but "eventually" is the window in which a downgraded actor keeps the
   * capability they no longer pay for and an upgraded one keeps being refused the capability they
   * just bought — the second being the one that generates a support ticket within seconds.
   *
   * @param actorId the actor whose plan changed
   */
  void evict(String actorId) {
    if (actorId != null && cache.remove(actorId) != null) {
      log.debug("Evicted cached entitlements for actor={}", actorId);
    }
  }

  /** Marks the lookup as unresolved so the caller passes through instead of refusing. */
  private static EntitlementSnapshot unresolved(String actorId) {
    return EntitlementSnapshot.unresolved(actorId, Instant.now().plus(RETRY_AFTER));
  }
}
