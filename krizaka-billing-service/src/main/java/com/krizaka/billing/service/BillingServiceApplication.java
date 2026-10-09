package com.krizaka.billing.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Billing &amp; credits service (ADR-033) — the owner of the credit ledger, the hold/settle
 * authorisation protocol, the versioned pricebook and the entitlement matrix.
 *
 * <p>Three independent producers (conversation, job, automation) debit the same wallet, so the
 * ledger has exactly one owner and one write path. Authorisation is on the hot path of every
 * request and is therefore served synchronously; settlement arrives over the broker.
 *
 * <p>{@code @EnableScheduling} is not decoration: the hold sweeper is what stops a crashed worker
 * freezing a user's balance forever.
 */
@SpringBootApplication
@EnableScheduling
public class BillingServiceApplication {

  /**
   * Boots the billing service.
   *
   * @param args standard Spring Boot command-line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(BillingServiceApplication.class, args);
  }
}
