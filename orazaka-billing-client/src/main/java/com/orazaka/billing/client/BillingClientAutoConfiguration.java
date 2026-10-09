package com.orazaka.billing.client;

import com.krizaka.security.token.ServiceTokenProvider;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import com.orazaka.billing.domain.port.EntitlementProvider;
import com.orazaka.billing.domain.port.PackPricingClient;
import com.orazaka.billing.domain.port.PackProvisioningClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Selects the billing adapter at bootstrap.
 *
 * <p>The whole point of the two-bean arrangement: producers depend on {@link
 * CreditAuthorizationClient} and never learn whether billing is deployed. Turning billing on is a
 * property change, not a code change, and turning it off cannot leave a half-wired call site
 * behind. Same discipline as the provider mesh — one port, adapters swapped by config.
 *
 * <p>Registered through the AutoConfiguration SPI so a consuming service inherits it by adding the
 * dependency, without widening its component scan.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
@Import(EntitlementCacheInvalidationConfiguration.class)
public class BillingClientAutoConfiguration {

  /**
   * The real adapter, wired only when billing is explicitly enabled.
   *
   * @param properties the producer-side wiring
   * @return an HTTP client pointed at the billing service
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.billing", name = "enabled", havingValue = "true")
  CreditAuthorizationClient httpCreditAuthorizationClient(BillingProperties properties) {
    return new HttpCreditAuthorizationClient(billingRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return a client that always grants and records nothing
   */
  @Bean
  @ConditionalOnMissingBean(CreditAuthorizationClient.class)
  CreditAuthorizationClient noOpCreditAuthorizationClient() {
    return new NoOpCreditAuthorizationClient();
  }

  /**
   * The real entitlement reader, wired by the same switch as the credit adapter.
   *
   * @param properties the producer-side wiring
   * @return a provider that reads entitlements from the billing service, cached to their own
   *     staleness bound
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.billing", name = "enabled", havingValue = "true")
  HttpEntitlementProvider httpEntitlementProvider(BillingProperties properties) {
    return new HttpEntitlementProvider(billingRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return a provider that gates nothing
   */
  @Bean
  @ConditionalOnMissingBean(EntitlementProvider.class)
  EntitlementProvider noOpEntitlementProvider() {
    return new NoOpEntitlementProvider();
  }

  /**
   * The real pack-price reader, wired by the same switch as the other two adapters.
   *
   * @param properties the producer-side wiring
   * @return a client that reads the whole price table in one call, cached to a short bound
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.billing", name = "enabled", havingValue = "true")
  PackPricingClient httpPackPricingClient(BillingProperties properties) {
    return new HttpPackPricingClient(billingRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return a client that knows no price, leaving the catalogue to render "—"
   */
  @Bean
  @ConditionalOnMissingBean(PackPricingClient.class)
  PackPricingClient noOpPackPricingClient() {
    return new NoOpPackPricingClient();
  }

  /**
   * The write half of the pack contract, for a pack installer in another context.
   *
   * @param properties the producer-side wiring
   * @return a client that writes a pack's price and grants, and throws when it cannot
   */
  @Bean
  @ConditionalOnProperty(prefix = "orazaka.billing", name = "enabled", havingValue = "true")
  PackProvisioningClient httpPackProvisioningClient(BillingProperties properties) {
    return new HttpPackProvisioningClient(billingRestClient(properties));
  }

  /**
   * The fallback, so the port is always satisfied.
   *
   * @return a client that refuses the write rather than accepting it silently
   */
  @Bean
  @ConditionalOnMissingBean(PackProvisioningClient.class)
  PackProvisioningClient noOpPackProvisioningClient() {
    return new NoOpPackProvisioningClient();
  }

  /**
   * One client shape for every adapter. Timeouts are deliberately tight: both calls sit on the
   * interactive path, so a slow billing service must degrade through the fail modes rather than
   * hold a user's chat turn open.
   */
  private static RestClient billingRestClient(BillingProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    ServiceTokenProvider tokens =
        new ServiceTokenProvider(properties.serviceSecret(), "orazaka-billing-client");
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        // Attached here rather than in each adapter: /internal/v1 authentication must not be a
        // thing the next method added to this client can forget.
        .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.token()))
        .build();
  }
}
