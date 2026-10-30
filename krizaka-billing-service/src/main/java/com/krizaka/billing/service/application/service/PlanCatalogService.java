package com.krizaka.billing.service.application.service;

import com.krizaka.billing.service.domain.model.CatalogPlan;
import com.krizaka.billing.service.domain.model.Entitlement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final String PRICE_ENTITY_TYPE = "PRICE";

  /** A plan's price is the catalogue entry {@code plan.<planKey>} (krizaka-billing#80). */
  static final String PRICE_KEY_PREFIX = "plan.";

  /**
   * The price is read through the versioned catalogue (the entry in force now); the column only
   * backs a plan the catalogue does not price yet.
   */
  private static final String SELECT_COLUMNS =
      "SELECT plan_key, label, tier_rank, monthly_credit_grant,"
          + " COALESCE((SELECT p.amount_cents FROM billing_price p WHERE p.price_key = '"
          + PRICE_KEY_PREFIX
          + "' || billing_plan.plan_key AND p.effective_from <= now()"
          + " ORDER BY p.effective_from DESC LIMIT 1), price_cents) AS price_cents, currency,"
          + " rate_limit_tier_key, is_public, is_active, overage_credit_price_cents, trial_days,"
          + " free_monthly_credits FROM billing_plan";

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;
  private final BillingConfigurationService configuration;

  /**
   * The catalogue as wired by Spring.
   *
   * @param jdbcTemplate the billing database
   * @param versionHistoryService where every prior version is snapshotted
   * @param configuration the runtime config, for {@code billing.currency}
   */
  @Autowired
  public PlanCatalogService(
      JdbcTemplate jdbcTemplate,
      BillingVersionHistoryService versionHistoryService,
      BillingConfigurationService configuration) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(
            versionHistoryService, "BillingVersionHistoryService cannot be null");
    this.configuration =
        Objects.requireNonNull(configuration, "BillingConfigurationService cannot be null");
  }

  /**
   * The catalogue reading {@code billing.currency} from the same database.
   *
   * @param jdbcTemplate the billing database
   * @param versionHistoryService where every prior version is snapshotted
   * @deprecated kept for binary compatibility; Spring uses the constructor taking the configuration
   *     service
   */
  @Deprecated(since = "0.2.0")
  public PlanCatalogService(
      JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
    this(
        jdbcTemplate,
        versionHistoryService,
        new BillingConfigurationService(jdbcTemplate, versionHistoryService));
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
        SELECT_COLUMNS + (includeInactive ? "" : " WHERE is_active") + " ORDER BY tier_rank";
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
        .query(SELECT_COLUMNS + " WHERE plan_key = ?", (rs, rowNum) -> readPlan(rs), planKey)
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
   * @param requested the plan to write; without a currency it is priced in {@code billing.currency}
   * @param changedBy the admin's actor id
   * @return the plan as stored
   */
  @Transactional
  public CatalogPlan save(CatalogPlan requested, String changedBy) {
    Objects.requireNonNull(requested, "plan must not be null");
    requireAdmin(changedBy);
    // A plan that names no currency is priced in the deployment's own (billing.currency) — the
    // catalogue never guesses one, and never stores a row without it.
    CatalogPlan plan =
        requested.currency() == null ? requested.withCurrency(configuration.currency()) : requested;
    Optional<CatalogPlan> prior = find(plan.planKey());
    prior.ifPresent(
        before -> versionHistoryService.snapshot(ENTITY_TYPE, plan.planKey(), before, changedBy));

    jdbcTemplate.update(
        "INSERT INTO billing_plan (plan_key, label, tier_rank, monthly_credit_grant, price_cents,"
            + " currency, rate_limit_tier_key, is_public, is_active, overage_credit_price_cents,"
            + " trial_days, free_monthly_credits, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())"
            + " ON CONFLICT (plan_key) DO UPDATE SET label = EXCLUDED.label,"
            + " tier_rank = EXCLUDED.tier_rank,"
            + " monthly_credit_grant = EXCLUDED.monthly_credit_grant,"
            + " price_cents = EXCLUDED.price_cents, currency = EXCLUDED.currency,"
            + " rate_limit_tier_key = EXCLUDED.rate_limit_tier_key,"
            + " is_public = EXCLUDED.is_public, is_active = EXCLUDED.is_active,"
            + " overage_credit_price_cents = EXCLUDED.overage_credit_price_cents,"
            + " trial_days = EXCLUDED.trial_days,"
            + " free_monthly_credits = EXCLUDED.free_monthly_credits,"
            + " updated_at = now()",
        plan.planKey(),
        plan.label(),
        plan.tierRank(),
        plan.monthlyCreditGrant(),
        plan.priceCents(),
        plan.currency(),
        plan.rateLimitTierKey(),
        plan.isPublic(),
        plan.isActive(),
        plan.overageCreditPriceCents(),
        plan.trialDays(),
        plan.freeMonthlyCredits());

    schedulePriceIfChanged(plan, prior, changedBy);

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

  /**
   * A price that differs from the one in force becomes a new version (append-only), starting now:
   * subscriptions already held keep the version they were taken at, so a change never rewrites what
   * a past period was billed. The change is audited like any other price.
   */
  private void schedulePriceIfChanged(
      CatalogPlan plan, Optional<CatalogPlan> prior, String changedBy) {
    String key = PRICE_KEY_PREFIX + plan.planKey();
    Integer versions =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_price WHERE price_key = ?", Integer.class, key);
    boolean unchanged =
        versions != null
            && versions > 0
            && prior.isPresent()
            && prior.get().priceCents() == plan.priceCents();
    if (unchanged) {
      return;
    }
    UUID versionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO billing_price (version_id, price_key, unit, amount_cents, currency,"
            + " availability, effective_from, created_by)"
            + " VALUES (?, ?, 'month', ?, ?, ?, now(), ?)",
        versionId,
        key,
        (long) plan.priceCents(),
        plan.currency(),
        plan.isPublic() ? "public" : "internal",
        changedBy);
    versionHistoryService.snapshot(
        PRICE_ENTITY_TYPE,
        key,
        Map.of("versionId", versionId.toString(), "amountCents", plan.priceCents()),
        changedBy);
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
        (Integer) rs.getObject("overage_credit_price_cents"),
        rs.getInt("trial_days"),
        (Long) rs.getObject("free_monthly_credits"),
        List.of());
  }

  private static void requireAdmin(String changedBy) {
    if (changedBy == null || changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }
  }
}
