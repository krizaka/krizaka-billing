package com.krizaka.billing.service.application.service;

import com.krizaka.billing.service.domain.model.ActorConsumption;
import com.krizaka.billing.service.domain.model.CapabilityUsage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads what the platform consumed, and what it refused.
 *
 * <p>Refusals are joined in rather than reported separately because the pair is what makes either
 * number legible (design §12): revenue alone looks healthy while a paywall turns users away, and a
 * refusal count alone cannot tell a mispriced capability from an unused one.
 *
 * <p><b>Not here: margin.</b> The design asks for margin per capability, and it is deliberately
 * absent — margin needs a cost basis, and the only honest one on owned hardware is GPU seconds,
 * which executors now report but {@code usage_event} does not store. Inventing a cost model to fill
 * the gap would produce a confident number with nothing behind it, which is worse than the blank.
 */
@Service
public class UsageAnalyticsService {

  private final JdbcTemplate jdbcTemplate;

  public UsageAnalyticsService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * Consumption and refusals per capability × model over a window.
   *
   * <p>A full outer join, because a capability can have refusals and no successes (priced out of
   * reach) or successes and no refusals (priced comfortably) — dropping either side would hide
   * exactly the case worth seeing.
   *
   * @param window how far back to look
   * @return one row per capability × model, heaviest spend first
   */
  public List<CapabilityUsage> byCapability(Duration window) {
    Objects.requireNonNull(window, "window must not be null");
    double seconds = window.toSeconds();
    return jdbcTemplate.query(
        "WITH used AS ("
            + "  SELECT capability, model_name, count(*) AS events,"
            + "         COALESCE(SUM(credits_charged), 0) AS credits,"
            + "         SUM(gpu_seconds) AS gpu_seconds"
            + "    FROM usage_event WHERE occurred_at >= now() - make_interval(secs => ?)"
            + "   GROUP BY capability, model_name),"
            + " refused AS ("
            + "  SELECT capability, model_name, count(*) AS refusals,"
            + "         COUNT(*) FILTER (WHERE enforced) AS enforced_refusals"
            + "    FROM credit_refusal WHERE occurred_at >= now() - make_interval(secs => ?)"
            + "   GROUP BY capability, model_name)"
            + " SELECT COALESCE(u.capability, r.capability) AS capability,"
            + "        COALESCE(u.model_name, r.model_name) AS model_name,"
            + "        COALESCE(u.events, 0) AS events, COALESCE(u.credits, 0) AS credits,"
            + "        COALESCE(r.refusals, 0) AS refusals,"
            + "        COALESCE(r.enforced_refusals, 0) AS enforced_refusals,"
            + "        u.gpu_seconds AS gpu_seconds"
            + "   FROM used u FULL OUTER JOIN refused r"
            + "     ON u.capability = r.capability"
            + "    AND COALESCE(u.model_name, '*') = COALESCE(r.model_name, '*')"
            + "  ORDER BY credits DESC, capability",
        (rs, rowNum) ->
            new CapabilityUsage(
                rs.getString("capability"),
                rs.getString("model_name"),
                rs.getLong("events"),
                rs.getLong("credits"),
                rs.getLong("refusals"),
                rs.getLong("enforced_refusals"),
                rs.getBigDecimal("gpu_seconds")),
        seconds,
        seconds);
  }

  /**
   * The heaviest consumers over a window.
   *
   * @param window how far back to look
   * @param limit how many actors to return
   * @return the top consumers, heaviest spend first
   */
  public List<ActorConsumption> topConsumers(Duration window, int limit) {
    Objects.requireNonNull(window, "window must not be null");
    return jdbcTemplate.query(
        "SELECT actor_id, count(*) AS events, COALESCE(SUM(credits_charged), 0) AS credits"
            + "  FROM usage_event WHERE occurred_at >= now() - make_interval(secs => ?)"
            + " GROUP BY actor_id ORDER BY credits DESC LIMIT ?",
        (rs, rowNum) ->
            new ActorConsumption(
                rs.getString("actor_id"), rs.getLong("events"), rs.getLong("credits")),
        (double) window.toSeconds(),
        limit);
  }
}
