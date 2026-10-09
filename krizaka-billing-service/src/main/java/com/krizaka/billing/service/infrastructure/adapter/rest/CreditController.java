package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.SettleCreditCommand;
import com.krizaka.billing.service.application.service.CreditLedgerService;
import com.krizaka.billing.service.application.service.EntitlementService;
import com.krizaka.billing.service.domain.exception.UnpricedModelException;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.AggregateSettleRequest;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.MeasuredSettleRequest;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.RefusalResponse;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.ReleaseRequest;
import com.krizaka.billing.service.infrastructure.adapter.rest.dto.SettleRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credits resource — the hot path every producer calls before it spends compute.
 *
 * <p>Service-to-service only, hence {@code /internal/v1} rather than {@code /api/v1}: a browser
 * never reserves its own credits, and the edge route table forwards {@code /api/**} exclusively, so
 * this surface is unreachable from outside. Same placement and rationale as the identity service's
 * internal API — LAN-local in this phase, per-service credentials arrive with the off-laptop
 * hardening pass. Putting it behind the session JWT instead would mean every producer forwarding a
 * user token it does not always hold: the hold sweeper and the settlement consumer act on nobody's
 * behalf.
 *
 * <p>Only {@code hold} blocks. Settle and release are called off the response path and answer
 * {@code 204}: making a caller wait for a debit would put billing latency in front of the last
 * token a user sees.
 */
@RestController
@RequestMapping("/internal/v1/billing/credits")
class CreditController {

  /** The entitlement that decides whether "top up" is a remedy the actor can actually take. */
  private static final String TOPUP_ENTITLEMENT = "topup.enabled";

  private final CreditLedgerService creditLedgerService;
  private final EntitlementService entitlementService;

  CreditController(CreditLedgerService creditLedgerService, EntitlementService entitlementService) {
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
    this.entitlementService =
        Objects.requireNonNull(entitlementService, "EntitlementService cannot be null");
  }

  /**
   * Reserves the estimated cost of a request.
   *
   * @param command the reservation request
   * @return {@code 201} with the granted hold
   */
  @PostMapping("/holds")
  @ResponseStatus(HttpStatus.CREATED)
  CreditHoldResponse hold(@RequestBody CreditHoldCommand command) {
    return creditLedgerService.hold(command);
  }

  /**
   * Closes a hold against measured consumption.
   *
   * @param holdId the reservation
   * @param request the measured quantity and the replay guard
   */
  @PostMapping("/{holdId}/settle")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void settle(@PathVariable String holdId, @RequestBody SettleRequest request) {
    creditLedgerService.settle(
        new SettleCreditCommand(
            holdId, request.unit(), request.quantity(), request.idempotencyKey()));
  }

  /**
   * Closes a hold against an executor's raw measurements, pricing them in the unit the hold's
   * pinned rate uses.
   *
   * <p>The settle of choice for a producer. A report that prices to nothing is <b>not</b> an error
   * — it means the executor measured nothing the pricebook's unit can be derived from, and the
   * caller releases instead. Answering {@code 204} either way keeps that a normal outcome rather
   * than something a client retries.
   *
   * @param holdId the reservation
   * @param request the measurements and the replay guard
   */
  @PostMapping("/{holdId}/settle-measured")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void settleMeasured(@PathVariable String holdId, @RequestBody MeasuredSettleRequest request) {
    creditLedgerService.settleMeasured(holdId, request.consumption(), request.idempotencyKey());
  }

  /**
   * Closes one hold against the measurements of the several steps it authorised.
   *
   * <p>Answers {@code 200} with whether a debit was applied, rather than {@code 204} like its
   * single-step sibling: an orchestrator that measured nothing must release instead, and it needs
   * to be told which happened. Guessing from an empty body is how a run ends with an outstanding
   * hold waiting on the sweeper.
   *
   * @param holdId the reservation
   * @param request the per-step measurements and the replay guard
   * @return {@code true} when a debit was applied, {@code false} when the caller should release
   */
  @PostMapping("/{holdId}/settle-aggregate")
  boolean settleAggregate(
      @PathVariable String holdId, @RequestBody AggregateSettleRequest request) {
    return creditLedgerService.settleAggregate(holdId, request.steps(), request.idempotencyKey());
  }

  /**
   * Closes a hold with no debit.
   *
   * @param holdId the reservation
   * @param request why it is being released
   */
  @PostMapping("/{holdId}/release")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void release(@PathVariable String holdId, @RequestBody ReleaseRequest request) {
    creditLedgerService.release(holdId, request.reason());
  }

  /**
   * Renders the structured 402 of the billing design from the refusal the ledger already computed.
   *
   * @param exception the refusal
   * @return {@code 402} with balance, shortfall, capability and remedies
   */
  @ExceptionHandler(InsufficientCreditsException.class)
  ResponseEntity<RefusalResponse> onInsufficientCredits(InsufficientCreditsException exception) {
    // Offering "top up" to an actor whose plan forbids it is a dead end they cannot act on, so the
    // remedy comes from their actual entitlement rather than from an assumption.
    boolean canTopUp = entitlementService.forActor(exception.actorId()).allows(TOPUP_ENTITLEMENT);
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .body(
            RefusalResponse.insufficientCredits(
                exception.capability().name(),
                exception.required(),
                exception.available(),
                canTopUp));
  }

  /**
   * An unpriced capability × model is a configuration gap, not a client error — surfacing it as
   * {@code 409} keeps it out of the client's 4xx-retry logic and visible in the phase-0 log.
   *
   * @param exception the unpriced pair
   * @return {@code 409} naming what is missing a rate
   */
  @ExceptionHandler(UnpricedModelException.class)
  ResponseEntity<RefusalResponse> onUnpricedModel(UnpricedModelException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new RefusalResponse(
                "unpriced_model", exception.capability().name(), 0L, 0L, List.of()));
  }
}
