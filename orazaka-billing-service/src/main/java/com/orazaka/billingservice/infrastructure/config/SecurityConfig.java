package com.orazaka.billingservice.infrastructure.config;

import com.krizaka.security.web.SecurityBaseline;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless security for the billing service.
 *
 * <p>The session JWT is verified locally against the shared HS256 secret and its {@code roles}
 * claim becomes the authorities — the identity service already signs them in, so the hot path never
 * pays an identity hop to authorise a credit hold.
 *
 * <p>URL rules stay coarse (authenticated vs not) on purpose: per-resource access is a method
 * concern expressed with {@code @PreAuthorize} on the controllers, not a class-name or a URL
 * convention (ERR-128). The one structural rule is {@code /internal/v1/**}, which separates the
 * machine-to-machine surface from the human one — a hold taken by the sweeper or the settlement
 * consumer is acting on nobody's behalf and has no session token to present.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /**
   * The filter chain: the Krizaka security baseline, then a valid session JWT for everything else.
   *
   * <p>The baseline ({@link SecurityBaseline}) opens the CORS preflight, health, info and the error
   * page, and reserves {@code /internal/v1/**} for the {@code SERVICE} authority — authenticated,
   * not merely unrouted: the edge not routing {@code /internal/**} is topology, and one SSRF turns
   * topology into an anonymous call (ADR-035). The session decoder and the {@code roles}-claim
   * converter come from krizaka-security ({@code krizaka.security.jwt.secret}).
   *
   * @param http the builder
   * @param roles the {@code roles}-claim converter, with no authority prefix
   * @return the built chain
   * @throws Exception if the chain cannot be built
   */
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtAuthenticationConverter roles) throws Exception {
    return SecurityBaseline.apply(http, auth -> {})
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(roles)))
        .build();
  }
}
