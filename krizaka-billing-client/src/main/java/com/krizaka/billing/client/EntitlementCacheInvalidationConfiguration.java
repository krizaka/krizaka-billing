package com.krizaka.billing.client;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the entitlement cache to {@code evt.subscription.*}.
 *
 * <p>Conditional on AMQP being present rather than requiring it: the SDK's other half is a plain
 * HTTP client, and a host without messaging should still get it (the dependency is {@code optional}
 * in the POM for the same reason).
 *
 * <p><b>One queue per service, not one shared queue.</b> Every host that gates chat keeps its own
 * cache, so every host needs its own copy of the event — competing consumers on a single queue
 * would leave exactly one service's cache correct and the rest stale, which is worse than no
 * invalidation at all because it is intermittent.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RabbitTemplate.class)
class EntitlementCacheInvalidationConfiguration {

  private static final String SUBSCRIPTION_BINDING = "evt.subscription.*";

  /**
   * Buying a pack changes an actor's entitlements exactly as changing a plan does.
   *
   * <p>It was not bound, so the only thing that unlocked a Studio somebody had just paid for was
   * the cache's own TTL — up to sixty seconds of "requires plan" at the one moment a customer is
   * least willing to see it (ADR-049 §1). Same failure as the capability-route cache in phase C,
   * same repair: the event already existed and nothing listened.
   */
  private static final String PACK_BINDING = "evt.pack.*";

  private static final String QUEUE_PREFIX = "krizaka.billing.entitlement-cache.";

  /**
   * The platform's events exchange, where billing announces subscription and pack changes.
   *
   * @param name {@code krizaka.messaging.exchanges.events}, {@code krizaka.events} by default
   * @return the exchange the invalidation queue binds to
   */
  @Bean
  TopicExchange billingEventsExchange(
      @Value("${krizaka.messaging.exchanges.events:krizaka.events}") String name) {
    return new TopicExchange(name, true, false);
  }

  /**
   * This host's own invalidation queue.
   *
   * <p>Non-durable and auto-delete: it holds cache hygiene, not money. A queue surviving a host
   * that no longer exists would accumulate events nobody will ever read, and losing one on restart
   * costs at most a TTL of staleness — the cache re-reads on its next miss anyway.
   *
   * @param applicationName names the queue after the host that owns it
   * @return the per-host queue
   */
  @Bean
  Queue entitlementInvalidationQueue(
      @Value("${spring.application.name:application}") String applicationName) {
    // Durable + auto-delete, not transient + auto-delete: RabbitMQ 4 removed
    // `transient_nonexcl_queues` and refuses the connection outright, so a non-durable
    // non-exclusive queue takes the whole host down at startup. Auto-delete still carries the
    // intent — the queue disappears with its last consumer — and durability only means it
    // survives a broker restart during the window it exists, which costs nothing here.
    return new Queue(QUEUE_PREFIX + applicationName, true, false, true);
  }

  @Bean
  Binding entitlementInvalidationBinding(
      Queue entitlementInvalidationQueue, TopicExchange billingEventsExchange) {
    return BindingBuilder.bind(entitlementInvalidationQueue)
        .to(billingEventsExchange)
        .with(SUBSCRIPTION_BINDING);
  }

  /**
   * The same queue, also fed by pack purchases (ADR-050).
   *
   * <p>A second binding rather than a second queue and listener: the consumer reads only {@code
   * actorId}, and a pack event carries one for the same reason a subscription event does. Two
   * listeners evicting the same cache would be two places to forget.
   *
   * @param entitlementInvalidationQueue this host's invalidation queue
   * @param billingEventsExchange the events exchange
   * @return the binding
   */
  @Bean
  Binding packSubscriptionInvalidationBinding(
      Queue entitlementInvalidationQueue, TopicExchange billingEventsExchange) {
    return BindingBuilder.bind(entitlementInvalidationQueue)
        .to(billingEventsExchange)
        .with(PACK_BINDING);
  }

  /**
   * The listener, wired under the same switch as the cache it evicts.
   *
   * <p><b>This was {@code @ConditionalOnBean(HttpEntitlementProvider.class)} and it never once
   * matched.</b> {@code @ConditionalOnBean} is evaluated against the beans registered *so far*, and
   * this configuration is {@code @Import}ed by the one that defines the provider — so the condition
   * asked whether a bean existed before the class that creates it had been processed. The result
   * was a queue declared on every host, bound, filling up, and consumed by nobody: {@code
   * rabbitmqctl list_queues} showed <b>0 consumers and messages accumulating</b> on all four.
   * Entitlement invalidation had therefore never worked at all — not for pack purchases, and not
   * for the plan changes it was written for. Only the sixty-second TTL was ever doing the work.
   *
   * <p>Guarded on the property instead of on the bean graph: {@code krizaka.billing.enabled} is the
   * switch that decides whether {@link HttpEntitlementProvider} exists, so asking it directly is
   * both deterministic and the same question — a declared condition rather than one inferred from
   * registration order (AGENTS.md §12).
   *
   * @param entitlementProvider the cache to invalidate
   * @return the listener
   */
  // Named for what it evicts, not for what it listens to. The studio service has its own
  // SubscriptionChangeListener — a different job, the same simple name — and the default bean name
  // collided the moment this one started being created, taking the host down at startup. The
  // condition being wrong had been hiding a second defect behind it.
  @Bean("entitlementCacheInvalidationListener")
  @ConditionalOnProperty(prefix = "krizaka.billing", name = "enabled", havingValue = "true")
  SubscriptionChangeListener entitlementCacheInvalidationListener(
      HttpEntitlementProvider entitlementProvider) {
    return new SubscriptionChangeListener(entitlementProvider);
  }
}
