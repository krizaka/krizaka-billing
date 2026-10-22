package com.krizaka.billing.service.application.service;

import com.krizaka.billing.service.domain.model.ActorUsage;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * One actor's usage and cost for a calendar month — what a product calling Orazaka with its own API
 * key shows its operators ("this month: N credits, X CAD, on plan P").
 *
 * <p>Scoped to the actor by construction: the caller passes the token's subject, never a request
 * parameter, so a product cannot read another product's usage.
 */
@Service
public class ActorUsageService {

  private final JdbcTemplate jdbcTemplate;
  private final BillingConfigurationService configuration;

  public ActorUsageService(JdbcTemplate jdbcTemplate, BillingConfigurationService configuration) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.configuration =
        Objects.requireNonNull(configuration, "BillingConfigurationService cannot be null");
  }

  /**
   * Usage of one actor over a calendar month (UTC).
   *
   * @param actorId the opaque billable subject
   * @param month the month to read
   * @return the month's totals, priced, with the plan in force (the entry plan when none)
   */
  public ActorUsage forMonth(String actorId, YearMonth month) {
    Objects.requireNonNull(actorId, "actorId must not be null");
    Objects.requireNonNull(month, "month must not be null");
    Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    int unit = configuration.unitPriceMillicents();
    List<ActorUsage.Line> lines =
        jdbcTemplate.query(
            "SELECT capability, model_name, count(*) AS events,"
                + " COALESCE(SUM(credits_charged), 0) AS credits"
                + "  FROM usage_event WHERE actor_id = ? AND occurred_at >= ? AND occurred_at < ?"
                + " GROUP BY capability, model_name ORDER BY credits DESC, capability",
            (rs, rowNum) -> {
              long credits = rs.getLong("credits");
              return new ActorUsage.Line(
                  rs.getString("capability"),
                  rs.getString("model_name"),
                  rs.getLong("events"),
                  credits,
                  credits * unit);
            },
            actorId,
            Timestamp.from(from),
            Timestamp.from(to));
    long events = lines.stream().mapToLong(ActorUsage.Line::events).sum();
    long credits = lines.stream().mapToLong(ActorUsage.Line::creditsCharged).sum();
    Plan plan = planOf(actorId);
    return new ActorUsage(
        actorId,
        month,
        plan.key(),
        plan.label(),
        plan.grant(),
        configuration.currency(),
        unit,
        events,
        credits,
        lines);
  }

  private record Plan(String key, String label, long grant) {}

  /** The live subscription's plan, else the entry plan (lowest public rank), as entitlements do. */
  private Plan planOf(String actorId) {
    return jdbcTemplate
        .query(
            "SELECT p.plan_key, p.label, p.monthly_credit_grant FROM billing_plan p WHERE p.plan_key ="
                + " (SELECT s.plan_key FROM billing_subscription s WHERE s.actor_id = ?"
                + "   AND s.status IN ('TRIALING','ACTIVE','PAST_DUE'))"
                + " UNION ALL"
                + " (SELECT plan_key, label, monthly_credit_grant FROM billing_plan"
                + "  WHERE is_active AND is_public ORDER BY tier_rank LIMIT 1)",
            (rs, rowNum) ->
                new Plan(
                    rs.getString("plan_key"),
                    rs.getString("label"),
                    rs.getLong("monthly_credit_grant")),
            actorId)
        .stream()
        .findFirst()
        .orElse(new Plan("unknown", "Unknown", 0));
  }
}
