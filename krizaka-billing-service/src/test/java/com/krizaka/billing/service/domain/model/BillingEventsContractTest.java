package com.krizaka.billing.service.domain.model;

import com.krizaka.test.events.EventContractTest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The events krizaka-billing publishes conform to the schemas krizaka-billing-api ships. */
class BillingEventsContractTest extends EventContractTest {

  private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

  @Test
  void creditGranted() {
    assertConforms(
        "evt.credit.granted",
        1,
        new CreditGrantedEvent(
            "user-1", CreditBucket.GRANTED.name(), 500, 1200, "grant", "admin-1", NOW));
  }

  @Test
  void creditPurchasedBySystem() {
    assertConforms(
        "evt.credit.granted",
        1,
        new CreditGrantedEvent("user-1", CreditBucket.PURCHASED.name(), 100, 100, null, null, NOW));
  }

  @Test
  void usageRecorded() {
    assertConforms(
        "evt.usage.recorded",
        1,
        new UsageRecordedEvent(
            "user-1", "IMAGE", "flux", "IMAGE", new BigDecimal("2.5"), 40, "hold-1", "corr-1"));
  }

  @Test
  void lowBalance() {
    assertConforms("evt.wallet.low-balance", 1, new LowBalanceEvent("user-1", 90, 10, NOW));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "evt.subscription.changed",
        "evt.subscription.canceled",
        "evt.subscription.renewed"
      })
  void subscriptionEvents(String routingKey) {
    assertConforms(
        routingKey,
        1,
        new SubscriptionChangedEvent("user-1", "pro", SubscriptionStatus.ACTIVE, NOW, NOW));
  }

  @ParameterizedTest
  @ValueSource(strings = {"evt.pack.subscribed", "evt.pack.canceled"})
  void packEvents(String routingKey) {
    assertConforms(
        routingKey,
        1,
        new PackSubscriptionChangedEvent(
            "user-1", "studio", SubscriptionStatus.CANCELED, null, NOW));
  }
}
