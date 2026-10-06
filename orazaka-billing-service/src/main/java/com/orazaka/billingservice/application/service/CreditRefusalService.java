package com.orazaka.billingservice.application.service;

import com.orazaka.billing.domain.model.CreditHoldCommand;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records credit refusals, in a transaction of their own.
 *
 * <p>{@code REQUIRES_NEW} is the entire reason this is a separate bean. A refusal under {@code
 * ENFORCING} ends with {@code InsufficientCreditsException}, which rolls back the authorisation
 * transaction — and would take the audit row with it, leaving the 402 rate permanently reading zero
 * no matter how many users were turned away. The evidence of a refusal has to outlive the refusal.
 *
 * <p>Separate bean rather than a private method because self-invocation bypasses the proxy, so
 * {@code REQUIRES_NEW} on a private method is silently the calling transaction — the failure mode
 * being that this looks correct and does nothing.
 */
@Service
public class CreditRefusalService {

  private final JdbcTemplate jdbcTemplate;

  public CreditRefusalService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Records that an actor could not cover a request.
   *
   * @param command the reservation that was refused
   * @param required what the request would have cost
   * @param available what the actor could actually cover
   * @param enforced whether it was actually refused, or merely shadow-metered under DRY_RUN
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(CreditHoldCommand command, long required, long available, boolean enforced) {
    jdbcTemplate.update(
        "INSERT INTO credit_refusal (actor_id, capability, model_name, required, available,"
            + " enforced, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        command.actorId(),
        command.capability().name(),
        command.modelName(),
        required,
        available,
        enforced,
        command.correlationId());
  }
}
