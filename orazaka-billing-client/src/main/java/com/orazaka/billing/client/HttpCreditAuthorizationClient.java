package com.orazaka.billing.client;

import com.orazaka.billing.domain.exception.InsufficientCreditsException;
import com.orazaka.billing.domain.model.ConsumptionReport;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.MeteredStep;
import com.orazaka.billing.domain.model.SettleCreditCommand;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the billing service over synchronous HTTP.
 *
 * <p>Synchronous on purpose (AGENTS.md §6): the hold is a blocking precondition of an interactive
 * request, and putting it on the broker would add a queue hop to every chat turn and turn a broker
 * hiccup into a total outage.
 *
 * <p>Only {@link #hold} propagates a refusal. {@link #settle} and {@link #release} run off the
 * response path and swallow transport failures into a log line: a settlement that fails to reach
 * billing is a reconciliation problem, but a settlement that throws would surface as an error on a
 * request the user already saw succeed.
 */
final class HttpCreditAuthorizationClient implements CreditAuthorizationClient {

  private static final Logger log = LoggerFactory.getLogger(HttpCreditAuthorizationClient.class);
  private static final String CREDITS_PATH = "/internal/v1/billing/credits";

  private final RestClient restClient;

  HttpCreditAuthorizationClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "RestClient cannot be null");
  }

  @Override
  public CreditHoldResponse hold(CreditHoldCommand command) {
    return restClient
        .post()
        .uri(CREDITS_PATH + "/holds")
        .body(command)
        .exchange(
            (request, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (status.value() == 402) {
                // The billing service already computed the shortfall and which remedies this
                // actor's plan permits; carry them across rather than re-deriving numbers this
                // side of the boundary cannot see.
                throw refusal(command, response.bodyTo(RefusalBody.class));
              }
              if (status.isError()) {
                throw new RestClientException("billing hold failed with status " + status);
              }
              return response.bodyTo(CreditHoldResponse.class);
            });
  }

  private static InsufficientCreditsException refusal(CreditHoldCommand command, RefusalBody body) {
    if (body == null) {
      return new InsufficientCreditsException(
          command.actorId(), command.capability(), command.estimatedCredits(), 0L);
    }
    return new InsufficientCreditsException(
        command.actorId(), command.capability(), body.required(), body.balance(), body.remedies());
  }

  @Override
  public void settle(SettleCreditCommand command) {
    try {
      restClient
          .post()
          .uri(CREDITS_PATH + "/" + command.holdId() + "/settle")
          .body(command)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.error("Settlement did not reach billing for hold={}", command.holdId(), e);
    }
  }

  @Override
  public void settleMeasured(String holdId, ConsumptionReport report, String idempotencyKey) {
    try {
      restClient
          .post()
          .uri(CREDITS_PATH + "/" + holdId + "/settle-measured")
          .body(new MeasuredSettleBody(report, idempotencyKey))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.error("Measured settlement did not reach billing for hold={}", holdId, e);
    }
  }

  @Override
  public boolean settleAggregate(String holdId, List<MeteredStep> steps, String idempotencyKey) {
    try {
      return Boolean.TRUE.equals(
          restClient
              .post()
              .uri(CREDITS_PATH + "/" + holdId + "/settle-aggregate")
              .body(new AggregateSettleBody(steps, idempotencyKey))
              .retrieve()
              .body(Boolean.class));
    } catch (RestClientException e) {
      // Logged, not thrown, like every other settle on this client: a run that finished must not be
      // reported as failed because the ledger was briefly unreachable. Returning false makes the
      // caller release, and a release that also fails leaves the hold to the billing sweeper —
      // which is the recoverable end of this failure, unlike a false failure shown to the user.
      log.error("Aggregate settlement did not reach billing for hold={}", holdId, e);
      return false;
    }
  }

  /** Wire body of the aggregate settlement. */
  private record AggregateSettleBody(List<MeteredStep> steps, String idempotencyKey) {}

  @Override
  public void release(String holdId, String reason) {
    try {
      restClient
          .post()
          .uri(CREDITS_PATH + "/" + holdId + "/release")
          .body(new ReleaseBody(reason))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.error("Release did not reach billing for hold={}", holdId, e);
    }
  }

  /** Request body of the release endpoint. */
  private record ReleaseBody(String reason) {}

  /** Request body of the measured-settlement endpoint. */
  private record MeasuredSettleBody(ConsumptionReport consumption, String idempotencyKey) {}

  /**
   * Contract copy of the billing service's structured refusal — that DTO is Tier-3 and owned by the
   * billing service, so this Tier-2 SDK reads the wire shape rather than importing it. Anti-
   * corruption layer (ERR-127): the JSON is converted at the boundary and never travels further.
   */
  private record RefusalBody(
      String status, String capability, long required, long balance, List<String> remedies) {

    /** Compact canonical constructor; a refusal with no remedies is still a valid refusal. */
    private RefusalBody {
      remedies = remedies == null ? List.of() : List.copyOf(remedies);
    }
  }
}
