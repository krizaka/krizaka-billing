package com.krizaka.billing.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Producer-side billing wiring ({@code krizaka.billing}).
 *
 * <p>This is a <b>bootstrap</b> switch, not a policy one: it decides whether the billing beans are
 * wired at all, and it cannot be DB-driven because it is read before the process knows whether a
 * billing database exists. The behavioural switch — {@code OFF | DRY_RUN | ENFORCING} — is a row
 * owned by the billing service and is never duplicated here. A switch is read before the DB exists
 * (env) <em>or</em> flipped live by an admin (DB), never both.
 *
 * <p>Default {@code false}: a fresh clone runs the whole stack without billing.
 *
 * @param enabled Whether the HTTP adapters are wired instead of the no-op ones. Off by default: a
 *     fresh clone runs the whole stack without billing.
 * @param baseUrl Internal base URL of the billing service.
 * @param connectTimeout How long a call waits for the connection; tight, billing sits on the
 *     interactive path.
 * @param readTimeout How long a call waits for the response; tight, a slow billing service must
 *     degrade through the fail modes rather than hold a request open.
 * @param serviceSecret Shared HS256 identity secret the SERVICE token of every call is signed with,
 *     which /internal/v1/** requires. Required when billing is enabled.
 */
@ConfigurationProperties(prefix = "krizaka.billing")
public record BillingProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue(BillingProperties.DEFAULT_BASE_URL) String baseUrl,
    @DefaultValue("500ms") Duration connectTimeout,
    @DefaultValue("2s") Duration readTimeout,
    String serviceSecret) {

  /** Where the billing service listens in a local stack. */
  public static final String DEFAULT_BASE_URL = "http://localhost:8095";

  /** Compact canonical constructor supplying hot-path-safe defaults (ERR-106). */
  public BillingProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    // Deliberately tight: hold() is a blocking precondition of an interactive request, so a slow
    // billing service must degrade via the fail-mode rather than hold a user's chat turn open.
    connectTimeout = connectTimeout == null ? Duration.ofMillis(500) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
  }

  /**
   * Hides the secret from logs, actuator output and exception messages (the record's own {@code
   * toString()} printed it).
   *
   * @return a description that never contains the secret
   */
  @Override
  public String toString() {
    return "BillingProperties[enabled=%s, baseUrl=%s, connectTimeout=%s, readTimeout=%s, serviceSecret=<redacted>]"
        .formatted(enabled, baseUrl, connectTimeout, readTimeout);
  }
}
