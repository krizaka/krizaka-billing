package com.krizaka.billing.service.infrastructure.adapter.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The request id of the call (ADR-085): the edge's {@code X-Request-Id} when well-formed, else a
 * fresh UUID, in the logging context as {@code requestId} for the whole call and echoed on the
 * response. Replaced by the platform kit's {@code CorrelationIdFilter} when billing takes the kit's
 * next release.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

  private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Request-Id");
    String id =
        incoming != null && WELL_FORMED.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }
}
