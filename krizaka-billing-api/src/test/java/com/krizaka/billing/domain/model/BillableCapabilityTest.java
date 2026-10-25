package com.krizaka.billing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BillableCapabilityTest {

  @Test
  @DisplayName("external checks and grounded research are billable capabilities of their own")
  void externalChecksAndResearchAreBillable() {
    assertThat(Arrays.stream(BillableCapability.values()).map(Enum::name))
        .containsExactly("CHAT", "IMAGE", "AUDIO", "VIDEO", "AGENT", "EXTERNAL_CHECK", "RESEARCH");
  }
}
