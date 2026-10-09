package com.krizaka.billing.service.infrastructure.adapter.amqp;

import com.krizaka.billing.domain.model.UnmeteredTurn;
import com.krizaka.billing.service.application.service.UnmeteredTurnService;
import com.krizaka.messaging.dedup.MessageDedup;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Collects the turns other services served without a hold while billing was down (ADR-064).
 *
 * <p>The other end of {@code UnmeteredTurnRepository}: a serving service writes the record to its
 * own outbox at the moment this service is unreachable, and the relay delivers it here once the
 * broker takes it. Idempotent by message id, like every consumer (AGENTS.md §6): the relay is
 * at-least-once, and a turn recorded twice would be reconciled twice.
 */
@Component
class UnmeteredTurnListener {

  private static final Logger log = LoggerFactory.getLogger(UnmeteredTurnListener.class);
  private static final String CONSUMER = "billing-unmetered-turn";

  private final UnmeteredTurnService unmeteredTurnService;
  private final MessageDedup messageDedupService;

  UnmeteredTurnListener(
      UnmeteredTurnService unmeteredTurnService, MessageDedup messageDedupService) {
    this.unmeteredTurnService =
        Objects.requireNonNull(unmeteredTurnService, "UnmeteredTurnService cannot be null");
    this.messageDedupService =
        Objects.requireNonNull(messageDedupService, "MessageDedup cannot be null");
  }

  @RabbitListener(queues = "#{unmeteredTurnQueue.name}")
  void onUnmeteredTurn(
      UnmeteredTurn turn,
      @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
    if (!messageDedupService.claim(CONSUMER, messageId)) {
      log.debug("Skipping redelivered unmetered turn messageId={}", messageId);
      return;
    }
    try {
      unmeteredTurnService.record(turn);
    } catch (RuntimeException failed) {
      // Give the claim back, or the redelivery is refused by the row this failed attempt left
      // behind — and the one record of a free turn is lost exactly when it failed to land.
      messageDedupService.release(CONSUMER, messageId);
      throw failed;
    }
    log.warn(
        "Recorded an unmetered {} turn for actor={} correlation={} ({})",
        turn.capability(),
        turn.actorId(),
        turn.correlationId(),
        turn.reason());
  }
}
