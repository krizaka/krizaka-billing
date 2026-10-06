package com.orazaka.billing.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Producer-side billing wiring ({@code orazaka.billing}).
 *
 * <p>This is a <b>bootstrap</b> switch, not a policy one: it decides whether the billing beans are
 * wired at all, and it cannot be DB-driven because it is read before the process knows whether a
 * billing database exists. The behavioural switch — {@code OFF | DRY_RUN | ENFORCING} — is a row
 * owned by the billing service and is never duplicated here. A switch is read before the DB exists
 * (env) <em>or</em> flipped live by an admin (DB), never both.
 *
 * <p>Default {@code false}: a fresh clone runs the whole stack without billing.
 *
 * @param enabled whether the HTTP adapter is wired instead of the no-op
 * @param baseUrl the billing service's internal base URL
 * @param connectTimeout how long to wait for the connection on the hot path
 * @param readTimeout how long to wait for the response on the hot path
 * @param serviceSecret the shared HS256 identity secret, used to mint the {@code SERVICE} token
 *     that {@code /internal/v1/**} now requires (ADR-035)
 */
@ConfigurationProperties(prefix = "orazaka.billing")
public record BillingProperties(
    boolean enabled,
    String baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    String serviceSecret) {

  /** Compact canonical constructor supplying hot-path-safe defaults (ERR-106). */
  public BillingProperties {
    baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://localhost:8095" : baseUrl;
    // Deliberately tight: hold() is a blocking precondition of an interactive request, so a slow
    // billing service must degrade via the fail-mode rather than hold a user's chat turn open.
    connectTimeout = connectTimeout == null ? Duration.ofMillis(500) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
  }
}
