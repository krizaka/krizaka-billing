package com.krizaka.billing.domain.port;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.ConsumptionReport;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.model.SettleCreditCommand;
import java.util.List;

/**
 * The hot-path billing port every producer depends on — conversation, job and automation services
 * alike.
 *
 * <p>{@link #hold} is the only <b>blocking</b> billing call: it is a precondition of an interactive
 * request and therefore synchronous (AGENTS.md §6). {@link #settle} and {@link #release} are off
 * the response path and must never delay the last token to the user.
 *
 * <p>Implementations are selected at bootstrap by {@code krizaka.billing.enabled}, with a no-op
 * Null Object as the fallback, so no call site ever branches on whether billing exists (ERR-127).
 */
public interface CreditAuthorizationClient {

  /**
   * Reserves the estimated cost of a request before it executes.
   *
   * @param command the reservation request
   * @return the outcome, including whether this was a shadow-metering grant
   * @throws InsufficientCreditsException when enforcement is active and the actor cannot cover the
   *     estimate — carrying everything the REST layer needs to render a structured 402
   */
  CreditHoldResponse hold(CreditHoldCommand command);

  /**
   * Closes a hold against measured consumption, writing the debit to the ledger.
   *
   * @param command the measured consumption, in a unit the caller already knows matches the hold's
   *     pinned rate — prefer {@link #settleMeasured} where it does not
   */
  void settle(SettleCreditCommand command);

  /**
   * Closes a hold against raw measurements, letting the ledger resolve the unit.
   *
   * <p>The settle of choice for an executor. Which unit a request bills in belongs to the pricebook
   * row the hold was pinned to, so a producer that named one would carry a copy of a pricing
   * decision it does not own — and would break outright the day a model is repriced into a
   * different unit.
   *
   * @param holdId the reservation to close
   * @param report what the executor measured
   * @param idempotencyKey the replay guard
   */
  void settleMeasured(String holdId, ConsumptionReport report, String idempotencyKey);

  /**
   * Closes ONE hold against the measurements of the SEVERAL steps it authorised.
   *
   * <p>The settle of choice for an orchestrator. {@link #settleMeasured} takes a single report and
   * prices it against the hold's own {@code (capability, model)} row, which is right for a job that
   * is one unit of work and wrong for a run that is many: a Studio run holds once against AGENT and
   * then spends across CHAT, IMAGE and VIDEO, whose work is priced in three different units. Sent
   * through {@code settleMeasured}, such a run settles at the AGENT rate for whichever step
   * happened to finish first — {@code quantityFor(CALL)} being a constant 1 — and the rest is free
   * (ADR-041).
   *
   * <p>Each step is priced against ITS OWN row, at the version the hold pinned, and the credits are
   * summed into a single debit. One hold, one settlement, one ledger entry — with one {@code
   * usage_event} per step, so the total stays reconstructible from the measurements that produced
   * it rather than being a number nobody can take apart.
   *
   * @param holdId the reservation to close
   * @param steps what each step measured and what priced it; an empty list releases rather than
   *     settles, because a run that measured nothing must not be billed at its estimate
   * @param idempotencyKey the replay guard
   */
  boolean settleAggregate(String holdId, List<MeteredStep> steps, String idempotencyKey);

  /**
   * Closes a hold with no debit — a failed generation is never billed.
   *
   * @param holdId the reservation to release
   * @param reason why it was released, retained for audit
   */
  void release(String holdId, String reason);
}
