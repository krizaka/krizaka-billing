package com.krizaka.billing.service.infrastructure.adapter.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The wire reads {@code job.{id}.done} by name, so a new measurement crosses without a version. */
class JobOutcomeEventTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  @DisplayName("a check's `calls` crosses the wire into the consumption billing settles")
  void readsCallsByName() {
    JobOutcomeEvent event =
        mapper.readValue(
            "{\"jobId\":\"job-1\",\"holdId\":\"hold-1\",\"consumption\":{\"calls\":3}}",
            JobOutcomeEvent.class);

    assertThat(event.consumption().calls()).isEqualTo(3L);
    assertThat(event.consumption().tokens()).isNull();
  }

  @Test
  @DisplayName("a producer that reports no `calls` leaves it absent, never zero")
  void absentCallsStayAbsent() {
    JobOutcomeEvent event =
        mapper.readValue(
            "{\"jobId\":\"job-1\",\"holdId\":\"hold-1\",\"consumption\":{\"tokens\":3400}}",
            JobOutcomeEvent.class);

    assertThat(event.consumption().calls()).isNull();
  }
}
