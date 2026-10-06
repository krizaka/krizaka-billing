package com.orazaka.billingservice.domain.model;

import java.util.UUID;

/**
 * An outbox row waiting to reach the broker.
 *
 * <p>Contract-copy of the shape the identity and app outboxes already use — the tables are
 * per-context by doctrine, so the record is too rather than being lifted into a shared library
 * nobody owns.
 *
 * @param id the outbox row
 * @param exchange the exchange to publish to
 * @param routingKey the routing key to publish under
 * @param messageId becomes the AMQP message id, so consumer-side dedup sees a stable identity
 *     across redeliveries
 * @param payload the event body as stored JSON, relayed verbatim
 * @param attempts how many times publishing has already failed
 */
public record PendingOutboxEvent(
    UUID id, String exchange, String routingKey, UUID messageId, String payload, int attempts) {}
