package com.orazaka.billingservice.infrastructure.adapter.amqp;

import com.orazaka.billingservice.application.service.OutboxService;
import com.orazaka.billingservice.domain.model.PendingOutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains {@code billing_outbox} to RabbitMQ (AGENTS.md §6).
 *
 * <p>Until this existed the outbox was write-only: the ledger recorded {@code evt.usage.recorded}
 * and the subscription service {@code evt.subscription.*}, and both accumulated unread. That was
 * tolerable while the only intended consumer was an external billing engine still gated behind
 * phase 4, and stopped being tolerable once the entitlement gate started caching snapshots — those
 * caches learn that a plan changed from {@code evt.subscription.*}, so without a relay their only
 * staleness bound is their own TTL.
 *
 * <p>Mirrors the identity and app relays argument for argument: one transaction per batch, {@code
 * FOR UPDATE SKIP LOCKED} so instances share the work rather than collide, the row's {@code
 * message_id} as the AMQP message id so consumer-side dedup sees a stable identity, and an
 * exponential back-off on failure.
 *
 * <p>The payload is relayed as the database rendered it rather than re-serialised here. JSONB
 * normalises key order and spacing, so it is not byte-identical to what Jackson wrote — but it is
 * the event as recorded, and running a months-old row back through a mapper whose shape has since
 * moved on is how an outbox quietly starts publishing something other than what happened.
 */
@Component
class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private static final int BATCH_SIZE = 100;
  private static final long PUBLISHED_RETENTION_DAYS = 7;

  private final OutboxService outboxService;
  private final RabbitTemplate rabbitTemplate;

  OutboxRelay(OutboxService outboxService, RabbitTemplate rabbitTemplate) {
    this.outboxService = Objects.requireNonNull(outboxService, "OutboxService cannot be null");
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "RabbitTemplate cannot be null");
  }

  /** Publishes every due event, marking each delivered inside the batch's transaction. */
  @Scheduled(fixedDelayString = "${orazaka.billing-service.outbox.poll-interval-ms:500}")
  @Transactional
  public void relayPendingBatch() {
    for (PendingOutboxEvent event : outboxService.lockPendingBatch(BATCH_SIZE)) {
      try {
        rabbitTemplate.send(event.exchange(), event.routingKey(), toAmqpMessage(event));
        outboxService.markPublished(event.id());
      } catch (RuntimeException e) {
        log.warn(
            "Billing outbox publish failed for {} ({} -> {}), attempt {} — backing off",
            event.id(),
            event.exchange(),
            event.routingKey(),
            event.attempts() + 1,
            e);
        outboxService.recordFailure(event.id(), event.attempts());
      }
    }
  }

  /** Hourly housekeeping: drops delivered rows past the retention window, never pending ones. */
  @Scheduled(fixedDelayString = "${orazaka.billing-service.outbox.purge-interval-ms:3600000}")
  public void purgePublished() {
    int purged =
        outboxService.purgePublishedBefore(
            Instant.now().minus(PUBLISHED_RETENTION_DAYS, ChronoUnit.DAYS));
    if (purged > 0) {
      log.info("Purged {} published billing outbox events", purged);
    }
  }

  private static Message toAmqpMessage(PendingOutboxEvent event) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(event.messageId().toString());
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    // spring-amqp 4.0.0 SimpleAmqpHeaderMapper.toHeaders NPEs on a null priority.
    properties.setPriority(0);
    return new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties);
  }
}
