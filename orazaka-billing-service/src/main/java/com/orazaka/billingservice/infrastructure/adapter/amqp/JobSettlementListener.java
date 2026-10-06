package com.orazaka.billingservice.infrastructure.adapter.amqp;

import com.orazaka.billingservice.application.service.CreditLedgerService;
import com.orazaka.billingservice.application.service.MessageDedupService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Closes the loop of the metering protocol: a job that finished settles its hold, a job that failed
 * releases it.
 *
 * <p>Settlement is deliberately asynchronous. The hold is the only blocking billing call, so the
 * debit arrives here, off the response path, where it cannot delay a user's last token.
 *
 * <p>Two independent idempotency guards, because a double debit is unrecoverable trust damage: the
 * {@code processed_messages} check below, and the ledger's unique {@code idempotency_key} beneath
 * it. The message id is carried into the ledger key so the inner guard sees the same identity.
 */
@Component
class JobSettlementListener {

  private static final Logger log = LoggerFactory.getLogger(JobSettlementListener.class);
  private static final String CONSUMER = "billing-settlement";

  private final CreditLedgerService creditLedgerService;
  private final MessageDedupService messageDedupService;

  JobSettlementListener(
      CreditLedgerService creditLedgerService, MessageDedupService messageDedupService) {
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedupService cannot be null");
  }

  @RabbitListener(queues = "#{settlementQueue.name}")
  void onJobOutcome(
      JobOutcomeEvent event,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (event == null || !event.isBillable()) {
      // A job submitted without a hold was never authorised through billing. Nothing to close.
      return;
    }
    if (!messageDedupService.claim(CONSUMER, messageId)) {
      log.debug("Skipping redelivered job outcome messageId={}", messageId);
      return;
    }

    try {
      if (event.failed()) {
        creditLedgerService.release(event.holdId(), "job failed: " + event.error());
      } else if (!settled(event, messageId)) {
        // Completed but reported nothing the pricebook's unit can be derived from: releasing is
        // the only honest option — billing an unmeasured job at its estimate charges a guess and
        // hides the reporting gap behind revenue.
        log.warn(
            "Job {} completed without usable consumption metrics; releasing hold {}",
            event.jobId(),
            event.holdId());
        creditLedgerService.release(event.holdId(), "no consumption metrics reported");
      }
    } catch (RuntimeException failed) {
      // Give the claim back, or the redelivery is refused by the row this failed attempt left
      // behind and THE SETTLEMENT IS LOST FOREVER: work done, never billed (ADR-067, audit #30).
      // The same three lines UnmeteredTurnListener already carries; re-settling is safe because
      // the ledger's idempotency key is this message id.
      messageDedupService.release(CONSUMER, messageId);
      throw failed;
    }
  }

  /**
   * Attempts settlement from what the executor measured. The message id becomes the ledger's
   * idempotency key so the inner unique-key guard sees the same identity this consumer deduped on.
   */
  private boolean settled(JobOutcomeEvent event, String messageId) {
    if (event.consumption() == null) {
      return false;
    }
    return creditLedgerService.settleMeasured(
        event.holdId(), event.consumption(), messageId == null ? event.holdId() : messageId);
  }
}
