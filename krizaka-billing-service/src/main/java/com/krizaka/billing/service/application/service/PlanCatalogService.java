package com.krizaka.billing.service.application.service;

import com.krizaka.billing.service.domain.model.CatalogPlan;
import com.krizaka.billing.service.domain.model.Entitlement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan catalogue — what Orazaka sells, as rows.
 *
 * <p>The design's robustness test is that creating a fourth plan requires <b>zero deploy</b> (§12).
 * That holds only if nothing about a plan is compiled in: the grant, the price, the rate-limit tier
 * and the entitlement matrix are all data here, and the entry plan is resolved by {@code tier_rank}
 * rather than by name so even renaming the cheapest plan is an admin action.
 *
 * <p>Every mutation is snapshotted into {@code billing_version_history} before it lands. A plan
 * change is a commercial commitment to everyone currently on it, so "what did this look like last
 * Tuesday" has to be answerable.
 */
@Service
public class PlanCatalogService {

  private static final Logger log = LoggerFactory.getLogger(PlanCatalogService.class);
  private static final String ENTITY_TYPE = "PLAN";

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;

  public PlanCatalogService(
      JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(
            versionHistoryService, "BillingVersionHistoryService cannot be null");
  }

  /**
   * Every plan, cheapest tier first.
   *
   * @param includeInactive whether to include retired plans, which existing subscribers may still
   *     be on — hiding those from the console would make their subscriptions unexplainable
   * @return the catalogue
   */
  public List<CatalogPlan> list(boolean includeInactive) {
    String sql =
        "SELECT plan_key, label, tier_rank, monthly_credit_grant, price_cents, currency,"
            + " rate_limit_tier_key, is_public, is_active FROM billing_plan"
            + (includeInactive ? "" : " WHERE is_active")
            + " ORDER BY tier_rank";
    return jdbcTemplate.query(sql, (rs, rowNum) -> readPlan(rs)).stream()
        .map(plan -> plan.withEntitlements(entitlementsOf(plan.planKey())))
        .toList();
  }

  /**
   * One plan with its entitlements.
   *
   * @param planKey the plan
   * @return the plan, empty when no such key exists
   */
  public Optional<CatalogPlan> find(String planKey) {
    return jdbcTemplate
        .query(
            "SELECT plan_key, label, tier_rank, monthly_credit_grant, price_cents, currency,"
                + " rate_limit_tier_key, is_public, is_active FROM billing_plan WHERE plan_key = ?",
            (rs, rowNum) -> readPlan(rs),
            planKey)
        .stream()
        .findFirst()
        .map(plan -> plan.withEntitlements(entitlementsOf(planKey)));
  }

  /**
   * Creates or replaces a plan and its entitlement matrix.
   *
   * <p>The matrix is replaced wholesale rather than merged: an editor that could only add would
   * make removing a capability impossible without a second endpoint, and a half-applied matrix is a
   * plan that grants something nobody chose.
   *
   * @param plan the plan to write
   * @param changedBy the admin's actor id
   * @return the plan as stored
   */
  @Transactional
  public CatalogPlan save(CatalogPlan plan, String changedBy) {
    Objects.requireNonNull(plan, "plan must not be null");
    requireAdmin(changedBy);
    find(plan.planKey())
        .ifPresent(
            prior -> versionHistoryService.snapshot(ENTITY_TYPE, plan.planKey(), prior, changedBy));

    jdbcTemplate.update(
        "INSERT INTO billing_plan (plan_key, label, tier_rank, monthly_credit_grant, price_cents,"
            + " currency, rate_limit_tier_key, is_public, is_active, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())"
            + " ON CONFLICT (plan_key) DO UPDATE SET label = EXCLUDED.label,"
            + " tier_rank = EXCLUDED.tier_rank,"
            + " monthly_credit_grant = EXCLUDED.monthly_credit_grant,"
            + " price_cents = EXCLUDED.price_cents, currency = EXCLUDED.currency,"
            + " rate_limit_tier_key = EXCLUDED.rate_limit_tier_key,"
            + " is_public = EXCLUDED.is_public, is_active = EXCLUDED.is_active,"
            + " updated_at = now()",
        plan.planKey(),
        plan.label(),
        plan.tierRank(),
        plan.monthlyCreditGrant(),
        plan.priceCents(),
        plan.currency(),
        plan.rateLimitTierKey(),
        plan.isPublic(),
        plan.isActive());

    jdbcTemplate.update("DELETE FROM billing_plan_entitlement WHERE plan_key = ?", plan.planKey());
    for (Entitlement entitlement : plan.entitlements()) {
      jdbcTemplate.update(
          "INSERT INTO billing_plan_entitlement (plan_key, entitlement_key, value_type, value)"
              + " VALUES (?, ?, ?, ?)",
          plan.planKey(),
          entitlement.key(),
          entitlement.valueType(),
          entitlement.value());
    }

    log.info(
        "Plan {} saved by {} with {} entitlements",
        plan.planKey(),
        changedBy,
        plan.entitlements().size());
    return find(plan.planKey()).orElseThrow();
  }

  /**
   * Retires a plan without deleting it.
   *
   * <p>Deactivation rather than deletion, because subscriptions reference it: removing the row
   * would orphan everyone still on the plan, and their history would stop being explicable.
   *
   * @param planKey the plan to retire
   * @param changedBy the admin's actor id
   * @return {@code true} when a plan was retired
   */
  @Transactional
  public boolean retire(String planKey, String changedBy) {
    requireAdmin(changedBy);
    Optional<CatalogPlan> prior = find(planKey);
    if (prior.isEmpty()) {
      return false;
    }
    versionHistoryService.snapshot(ENTITY_TYPE, planKey, prior.get(), changedBy);
    jdbcTemplate.update(
        "UPDATE billing_plan SET is_active = FALSE, updated_at = now() WHERE plan_key = ?",
        planKey);
    return true;
  }

  private List<Entitlement> entitlementsOf(String planKey) {
    return jdbcTemplate.query(
        "SELECT entitlement_key, value_type, value FROM billing_plan_entitlement"
            + " WHERE plan_key = ? ORDER BY entitlement_key",
        (rs, rowNum) ->
            new Entitlement(
                rs.getString("entitlement_key"), rs.getString("value_type"), rs.getString("value")),
        planKey);
  }

  private static CatalogPlan readPlan(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new CatalogPlan(
        rs.getString("plan_key"),
        rs.getString("label"),
        rs.getInt("tier_rank"),
        rs.getLong("monthly_credit_grant"),
        rs.getInt("price_cents"),
        rs.getString("currency"),
        rs.getString("rate_limit_tier_key"),
        rs.getBoolean("is_public"),
        rs.getBoolean("is_active"),
        List.of());
  }

  private static void requireAdmin(String changedBy) {
    if (changedBy == null || changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }
  }
}
