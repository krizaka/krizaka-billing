package com.krizaka.billing.service.application.service;

import com.krizaka.billing.domain.model.UnmeteredTurn;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the record of work served without a hold, for reconciliation (ADR-064).
 *
 * <p>Records and does nothing else. Whether an unmetered turn is billed after the fact or written
 * off is a decision about a customer during an outage the platform caused, and it belongs to a
 * person reading this table — not to a consumer debiting a wallet from a message.
 */
@Service
public class UnmeteredTurnService {

  private final JdbcTemplate jdbcTemplate;

  public UnmeteredTurnService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Records one turn served without a hold.
   *
   * @param turn what was served, to whom, and why no hold was taken
   */
  @Transactional
  public void record(UnmeteredTurn turn) {
    Objects.requireNonNull(turn, "turn must not be null");
    jdbcTemplate.update(
        "INSERT INTO unmetered_turn (actor_id, capability, correlation_id, reason, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        turn.actorId(),
        turn.capability().name(),
        turn.correlationId(),
        turn.reason(),
        Timestamp.from(turn.occurredAt()));
  }
}
