package com.krizaka.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

  @Test
  void theSecretNeverLeavesThroughToString() {
    BillingProperties properties =
        new BillingProperties(true, null, null, null, "a-secret-of-at-least-thirty-two-chars");

    assertThat(properties.toString())
        .doesNotContain("a-secret-of-at-least-thirty-two-chars")
        .contains("serviceSecret=<redacted>");
  }

  @Test
  void defaults() {
    BillingProperties properties = new BillingProperties(false, null, null, null, null);

    assertThat(properties.baseUrl()).isEqualTo(BillingProperties.DEFAULT_BASE_URL);
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
  }
}
