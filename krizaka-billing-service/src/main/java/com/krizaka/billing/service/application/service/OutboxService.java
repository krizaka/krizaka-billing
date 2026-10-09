package com.krizaka.billing.service.application.service;

import com.krizaka.messaging.outbox.OutboxMessage;
import com.krizaka.messaging.outbox.OutboxStore;
import com.krizaka.messaging.topology.MessagingExchanges;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional outbox (AGENTS.md §6): domain events are appended in the <em>same</em> transaction
 * as the state change that produced them, and the krizaka-messaging relay publishes them afterwards
 * — this class is the billing context's {@link OutboxStore}: it owns the {@code billing_outbox}
 * table, its claim ({@code FOR UPDATE SKIP LOCKED}) and its back-off.
 *
 * <p>This is what makes "the ledger authorises, Lago invoices" survivable. A direct publish inside
 * the settle transaction would either lose the event when the broker blinks or roll back a debit
 * that already happened; the outbox makes the debit and its announcement atomic, and lets the
 * external engine be down for hours without a user noticing.
 *
 * <p>{@code message_id} is UNIQUE and becomes the AMQP message id, so the consumer-side dedup
 * downstream sees a stable identity across redeliveries.
 */
@Service
public class OutboxService implements OutboxStore {

  /** Backoff doubles per failure, then holds — see {@link #recordFailure}. */
  private static final int MAX_BACKOFF_SHIFT = 9;

  private static final long MAX_BACKOFF_SECONDS = 512;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final String eventsExchange;

  public OutboxService(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, MessagingExchanges exchanges) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.eventsExchange =
        Objects.requireNonNull(exchanges, "MessagingExchanges cannot be null").events();
  }

  /**
   * Appends an event to the outbox. Call inside the business transaction, never after it.
   *
   * @param aggregateType the aggregate that changed, e.g. {@code WALLET}
   * @param aggregateId that aggregate's identifier
   * @param routingKey the the events exchange routing key, e.g. {@code evt.usage.recorded}
   * @param payload the event body, serialised to JSONB
   */
  public void append(String aggregateType, String aggregateId, String routingKey, Object payload) {
    jdbcTemplate.update(
        "INSERT INTO billing_outbox (id, aggregate_type, aggregate_id, exchange, routing_key,"
            + " message_id, payload) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
        UUID.randomUUID(),
        aggregateType,
        aggregateId,
        eventsExchange,
        routingKey,
        UUID.randomUUID(),
        objectMapper.writeValueAsString(payload));
  }

  /**
   * Locks a batch of due events for this relay instance.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} rather than a plain select: two instances polling the same
   * table must not both claim the same row, and skipping a locked one is what lets them share the
   * work instead of serialising behind each other.
   *
   * @param batchSize how many rows to claim
   * @return the claimed events, oldest first so ordering per aggregate is preserved
   */
  @Transactional
  @Override
  public List<OutboxMessage> lockPendingBatch(int batchSize) {
    return jdbcTemplate.query(
        "SELECT id, exchange, routing_key, message_id, payload::text, attempts"
            + " FROM billing_outbox WHERE published_at IS NULL AND next_attempt_at <= now()"
            + " ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, rowNum) ->
            new OutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getString("exchange"),
                rs.getString("routing_key"),
                rs.getObject("message_id", UUID.class).toString(),
                rs.getString("payload").getBytes(StandardCharsets.UTF_8),
                rs.getInt("attempts")),
        batchSize);
  }

  /**
   * Marks an event delivered.
   *
   * @param id the outbox row
   */
  @Override
  public void markPublished(UUID id) {
    jdbcTemplate.update("UPDATE billing_outbox SET published_at = now() WHERE id = ?", id);
  }

  /**
   * Records a failed publish and backs the row off exponentially.
   *
   * <p>Capped, because an event that has failed twenty times is a broken topology rather than a
   * blip, and an uncapped backoff would push its retry past any window an operator watches.
   *
   * @param id the outbox row
   * @param attempts how many attempts had already been made
   */
  @Override
  public void recordFailure(UUID id, int attempts) {
    long backoffSeconds =
        Math.min(1L << Math.min(attempts, MAX_BACKOFF_SHIFT), MAX_BACKOFF_SECONDS);
    jdbcTemplate.update(
        "UPDATE billing_outbox SET attempts = attempts + 1,"
            + " next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?",
        (double) backoffSeconds,
        id);
  }

  /**
   * Drops delivered rows older than the retention window.
   *
   * <p>Only published rows: an undelivered event is evidence of a problem and must survive the
   * housekeeping that removes the successes.
   *
   * @param before the cutoff
   * @return how many rows were purged
   */
  @Override
  public long purgePublishedBefore(Instant before) {
    return jdbcTemplate.update(
        "DELETE FROM billing_outbox WHERE published_at IS NOT NULL AND published_at < ?",
        java.sql.Timestamp.from(before));
  }
}
