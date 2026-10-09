package com.krizaka.billing.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Invalidates a cached entitlement snapshot when the actor's plan changes.
 *
 * <p>The gate caches so it does not pay a network hop per chat turn; this listener is what bounds
 * the resulting staleness to broker latency rather than to the snapshot's TTL. It is the consuming
 * half of the {@code evt.subscription.*} events the billing service records — which is why the
 * relay that drains them had to exist before this was worth writing.
 *
 * <p>Eviction rather than refresh: the next gate check will fetch the snapshot it needs, and
 * fetching one here for an actor who may not chat again is work done on the chance it is wanted.
 */
class SubscriptionChangeListener {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionChangeListener.class);

  private final HttpEntitlementProvider entitlementProvider;

  SubscriptionChangeListener(HttpEntitlementProvider entitlementProvider) {
    this.entitlementProvider =
        Objects.requireNonNull(entitlementProvider, "HttpEntitlementProvider cannot be null");
  }

  /**
   * Evicts the actor named by a subscription event.
   *
   * @param event the change announcement
   */
  @RabbitListener(queues = "#{entitlementInvalidationQueue.name}")
  void onSubscriptionChanged(SubscriptionEvent event) {
    if (event == null || event.actorId() == null) {
      // A malformed announcement must not take the listener down; the TTL still bounds staleness.
      log.warn("Subscription event carried no actor — nothing to evict");
      return;
    }
    entitlementProvider.evict(event.actorId());
  }

  /**
   * The subset of {@code evt.subscription.*} this consumer reads.
   *
   * <p>Tolerant reader: the producer's event carries plan, status and period, none of which matter
   * for an eviction. Binding only {@code actorId} means a field added for another consumer cannot
   * dead-letter a cache invalidation.
   *
   * @param actorId the actor whose commercial standing changed
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SubscriptionEvent(String actorId) {}
}
