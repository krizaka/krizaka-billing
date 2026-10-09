package com.krizaka.billing.service.infrastructure.config;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The billing service's own datasource wiring ({@code krizaka.billing-service.datasource}), bound
 * from {@code BILLING_DB_*} only. A dedicated prefix (instead of {@code spring.datasource}) keeps
 * the shared local {@code .env} — which exports {@code SPRING_DATASOURCE_*} for the app database —
 * from hijacking this service's connection through Spring's env-var precedence over yaml.
 *
 * @param url the JDBC URL of {@code krizaka_billing_db}
 * @param username the service's own database role
 * @param password the role's password
 */
@ConfigurationProperties(prefix = "krizaka.billing-service.datasource")
public record BillingDataSourceProperties(String url, String username, String password) {

  /** Compact canonical constructor rejecting an unusable connection at bootstrap (ERR-106). */
  public BillingDataSourceProperties {
    Objects.requireNonNull(url, "billing datasource url is required");
    Objects.requireNonNull(username, "billing datasource username is required");
    Objects.requireNonNull(password, "billing datasource password is required");
    if (!url.startsWith("jdbc:postgresql:")) {
      throw new IllegalArgumentException("billing datasource url must be a postgresql JDBC url");
    }
  }
}
