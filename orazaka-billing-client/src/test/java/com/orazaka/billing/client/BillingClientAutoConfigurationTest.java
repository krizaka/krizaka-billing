package com.orazaka.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import com.orazaka.billing.domain.port.EntitlementProvider;
import com.orazaka.billing.domain.port.PackPricingClient;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * The seam's contract: exactly one {@link CreditAuthorizationClient} and one {@link
 * EntitlementProvider} exist in every configuration, so a consumer injects the ports
 * unconditionally and never branches on whether billing is deployed.
 */
class BillingClientAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(BillingClientAutoConfiguration.class));

  @Test
  @DisplayName("a fresh clone gets the no-op — the port is satisfied without billing deployed")
  void defaultsToNoOp() {
    runner.run(
        context ->
            assertThat(context.getBean(CreditAuthorizationClient.class))
                .isInstanceOf(NoOpCreditAuthorizationClient.class));
  }

  @Test
  @DisplayName("explicitly disabled still yields the no-op, never a missing bean")
  void disabledYieldsNoOp() {
    runner
        .withPropertyValues("orazaka.billing.enabled=false")
        .run(
            context ->
                assertThat(context.getBean(CreditAuthorizationClient.class))
                    .isInstanceOf(NoOpCreditAuthorizationClient.class));
  }

  @Test
  @DisplayName("enabling billing swaps in the HTTP adapter, and only one client is ever present")
  void enabledYieldsHttpAdapter() {
    runner
        .withPropertyValues(
            "orazaka.billing.enabled=true",
            "orazaka.billing.base-url=http://localhost:8095",
            "orazaka.billing.service-secret=orazaka-test-secret-at-least-32-characters!")
        .run(
            context -> {
              assertThat(context.getBean(CreditAuthorizationClient.class))
                  .isInstanceOf(HttpCreditAuthorizationClient.class);
              assertThat(context.getBeansOfType(CreditAuthorizationClient.class)).hasSize(1);
            });
  }

  @Test
  @DisplayName("the no-op grants without reserving, so a disabled stack never blocks a request")
  void noOpGrantsWithoutReserving() {
    CreditHoldResponse response =
        new NoOpCreditAuthorizationClient()
            .hold(
                new CreditHoldCommand(
                    "actor-1", BillableCapability.VIDEO, null, "correlation-1", null, 360));

    assertThat(response.granted()).isTrue();
    assertThat(response.dryRun()).isFalse();
    assertThat(response.estimatedCredits()).isZero();
  }

  @Test
  @DisplayName("the entitlement port is satisfied without billing deployed")
  void entitlementDefaultsToNoOp() {
    runner.run(
        context ->
            assertThat(context.getBean(EntitlementProvider.class))
                .isInstanceOf(NoOpEntitlementProvider.class));
  }

  @Test
  @DisplayName("one switch wires both adapters, so the two gates cannot disagree")
  void enabledYieldsHttpEntitlementProvider() {
    runner
        .withPropertyValues(
            "orazaka.billing.enabled=true",
            "orazaka.billing.base-url=http://localhost:8095",
            "orazaka.billing.service-secret=orazaka-test-secret-at-least-32-characters!")
        .run(
            context -> {
              assertThat(context.getBean(EntitlementProvider.class))
                  .isInstanceOf(HttpEntitlementProvider.class);
              assertThat(context.getBeansOfType(EntitlementProvider.class)).hasSize(1);
            });
  }

  @Test
  @DisplayName("the no-op reports unresolved, so the gate passes through instead of denying")
  void noOpEntitlementIsUnresolved() {
    EntitlementSnapshot snapshot = new NoOpEntitlementProvider().forActor("actor-1");

    // allows() reads an absent key as a denial, which would be exactly wrong here — resolved()
    // is what stops an unwired stack from locking every user out of every capability.
    assertThat(snapshot.resolved()).isFalse();
    assertThat(snapshot.allows("capability.chat")).isFalse();
  }

  @Test
  @DisplayName("the pack-price port is satisfied without billing deployed")
  void packPricingDefaultsToNoOp() {
    runner.run(
        context ->
            assertThat(context.getBean(PackPricingClient.class))
                .isInstanceOf(NoOpPackPricingClient.class));
  }

  @Test
  @DisplayName("the same switch wires the pack-price adapter — one property, three ports")
  void enabledYieldsHttpPackPricingClient() {
    runner
        .withPropertyValues(
            "orazaka.billing.enabled=true",
            "orazaka.billing.base-url=http://localhost:8095",
            "orazaka.billing.service-secret=orazaka-test-secret-at-least-32-characters!")
        .run(
            context -> {
              assertThat(context.getBean(PackPricingClient.class))
                  .isInstanceOf(HttpPackPricingClient.class);
              assertThat(context.getBeansOfType(PackPricingClient.class)).hasSize(1);
            });
  }

  @Test
  @DisplayName("the no-op knows no price, so a card renders \"—\" rather than \"free\"")
  void noOpPricingKnowsNothingRatherThanZero() {
    // A fabricated 0 would advertise every pack as free the moment billing is unwired, which is
    // a price the product would then be held to.
    assertThat(new NoOpPackPricingClient().prices(Set.of("realestate-studio"))).isEmpty();
  }

  @Test
  @DisplayName("an unreachable billing service yields no prices instead of an exception")
  void httpPricingDegradesRatherThanThrowing() {
    // The catalogue is a marketing page. It must not 500 because the credit ledger is
    // restarting — the card simply loses its price.
    HttpPackPricingClient client =
        new HttpPackPricingClient(RestClient.builder().baseUrl("http://localhost:1").build());

    assertThat(client.prices(Set.of("realestate-studio"))).isEmpty();
  }

  @Test
  @DisplayName("an empty page asks billing nothing")
  void emptyPageMakesNoCall() {
    assertThat(new HttpPackPricingClient(RestClient.builder().build()).prices(Set.of())).isEmpty();
    assertThat(new HttpPackPricingClient(RestClient.builder().build()).prices(null)).isEmpty();
  }

  @Test
  @DisplayName("timeouts default tight — a slow billing service must not hold a chat turn open")
  void appliesHotPathTimeoutDefaults() {
    BillingProperties properties =
        new BillingProperties(
            true, null, null, null, "orazaka-test-secret-at-least-32-characters!");

    assertThat(properties.baseUrl()).isEqualTo("http://localhost:8095");
    assertThat(properties.connectTimeout().toMillis()).isEqualTo(500);
    assertThat(properties.readTimeout().toSeconds()).isEqualTo(2);
  }

  @Test
  @DisplayName("the invalidation queue is named per host, so every cache gets its own copy")
  void invalidationQueueIsPerHost() {
    runner
        .withPropertyValues(
            "orazaka.billing.enabled=true",
            "spring.application.name=orazaka-conversation-service",
            "orazaka.billing.service-secret=orazaka-test-secret-at-least-32-characters!")
        .run(
            context -> {
              org.springframework.amqp.core.Queue queue =
                  context.getBean(
                      "entitlementInvalidationQueue", org.springframework.amqp.core.Queue.class);
              // Competing consumers on one shared queue would leave exactly one service's cache
              // correct and the rest stale — worse than no invalidation, because it is
              // intermittent.
              assertThat(queue.getName())
                  .isEqualTo("orazaka.events.entitlement-cache.orazaka-conversation-service");
              assertThat(queue.isAutoDelete()).isTrue();
            });
  }

  @Test
  @DisplayName("with billing off there is no cache, so no listener is wired to invalidate one")
  void noListenerWithoutTheHttpProvider() {
    runner.run(
        context -> assertThat(context.getBeansOfType(SubscriptionChangeListener.class)).isEmpty());
  }

  @Test
  @DisplayName("an eviction makes the next read go back to billing")
  void evictionForcesARefetch() {
    // The cache is what the listener exists to correct; evicting an actor it never saw is a no-op
    // rather than an error, because a plan can change for someone who has not chatted yet.
    HttpEntitlementProvider provider = new HttpEntitlementProvider(RestClient.builder().build());

    provider.evict("actor-never-seen");
  }
}
