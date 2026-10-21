package com.krizaka.billing.service.infrastructure.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** ADR-085, slice 1: the edge's request id reaches the audit through the logging context. */
class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void aWellFormedIdIsKeptForTheCallAndRemovedAfter() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "9f1c2d3e-aaaa-bbbb-cccc-123456789abc");
    var response = new MockHttpServletResponse();
    String[] seen = new String[1];

    filter.doFilter(request, response, (req, res) -> seen[0] = MDC.get("requestId"));

    assertThat(seen[0]).isEqualTo("9f1c2d3e-aaaa-bbbb-cccc-123456789abc");
    assertThat(MDC.get("requestId")).isNull();
    assertThat(response.getHeader("X-Request-Id"))
        .isEqualTo("9f1c2d3e-aaaa-bbbb-cccc-123456789abc");
  }

  @Test
  void noIdOrAMalformedOneGetsAFreshUuid() throws Exception {
    var bad = new MockHttpServletRequest();
    bad.addHeader("X-Request-Id", "x".repeat(200));
    String[] seen = new String[2];

    filter.doFilter(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        (req, res) -> seen[0] = MDC.get("requestId"));
    filter.doFilter(
        bad, new MockHttpServletResponse(), (req, res) -> seen[1] = MDC.get("requestId"));

    assertThat(seen[0]).hasSize(36);
    assertThat(seen[1]).hasSize(36);
  }
}
