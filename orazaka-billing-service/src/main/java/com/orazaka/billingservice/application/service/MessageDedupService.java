package com.orazaka.billingservice.application.service;

import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Consumer-side idempotency by AMQP {@code messageId} (AGENTS.md §6).
 *
 * <p>Contract copy of the platform dedup pattern; rows live in this service's own {@code
 * processed_messages} table. This is the <em>outer</em> of two independent guards — the ledger's
 * unique {@code idempotency_key} is the inner one. Two guards is deliberate for money: this one
 * stops the work being redone, that one stops a double debit if this one is ever bypassed.
 */
@Service
public class MessageDedupService {

  private final JdbcTemplate jdbcTemplate;

  public MessageDedupService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Claims a message for processing, atomically.
   *
   * <p>An {@code INSERT} whose unique constraint on {@code (consumer, message_id)} decides the race
   * — never a read followed by a write, which is two statements a second delivery can slip between.
   * This class used to do exactly that, and so did two others (ADR-058 §2).
   *
   * @param consumer who is processing
   * @param messageId the broker's message id; blank is claimable, because a producer that sent no
   *     id asked for no deduplication and must not be silently dropped
   * @return {@code true} when this caller may process it
   */
  public boolean claim(String consumer, String messageId) {
    if (messageId == null || messageId.isBlank()) {
      return true;
    }
    try {
      jdbcTemplate.update(
          "INSERT INTO processed_messages (consumer, message_id) VALUES (?, ?)",
          consumer,
          messageId);
      return true;
    } catch (DataIntegrityViolationException alreadyClaimed) {
      return false;
    }
  }

  /**
   * Gives a claim back after processing failed, so the redelivery is processed.
   *
   * <p>Without it, {@link #claim} turns a handler exception into a lost message: the row is
   * committed, the nack redelivers, and the redelivery is refused by the row its own failed attempt
   * left behind.
   *
   * @param consumer who was processing
   * @param messageId the message to make claimable again
   */
  public void release(String consumer, String messageId) {
    if (messageId == null || messageId.isBlank()) {
      return;
    }
    jdbcTemplate.update(
        "DELETE FROM processed_messages WHERE consumer = ? AND message_id = ?",
        consumer,
        messageId);
  }
}
