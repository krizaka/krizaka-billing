package com.orazaka.billingservice.application.service;

import com.orazaka.billing.domain.model.EntitlementSnapshot;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves what an actor is allowed to do.
 *
 * <p>Entitlement gates <b>access</b> and credits gate <b>volume</b>: this service answers the first
 * question only, and answers it from rows, so adding a plan or a key is an admin action.
 *
 * <p>An actor with no active subscription falls back to the <b>lowest public tier</b>, resolved by
 * {@code tier_rank} rather than by name — a hardcoded {@code "free"} would mean a deploy to rename
 * or replace the entry plan, which is the exact failure the design bans (§15).
 */
@Service
public class EntitlementService {

  /** How long a snapshot may be cached downstream — the bounded staleness a downgrade accepts. */
  private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(60);

  private static final String CONSUMER_UNKNOWN_PLAN = "unknown";

  private final JdbcTemplate jdbcTemplate;
  private final CreditLedgerService creditLedgerService;

  public EntitlementService(JdbcTemplate jdbcTemplate, CreditLedgerService creditLedgerService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
  }

  /**
   * Resolves an actor's effective entitlements plus their balance summary.
   *
   * <p><b>Plan ∪ pack</b> (design §14): the plan is the base, and every pack the actor holds is
   * merged over it. The merge only ever <em>widens</em> — see {@link #morePermissive} — because a
   * pack is something the user bought in addition to their plan. A pack that could lower a plan's
   * limit would mean buying an add-on could take access away, which nobody would ever intend and
   * which would surface as a support ticket rather than as an error.
   *
   * <p>Both reads are keyed on indexes built for this call: it sits on the authorisation hot path
   * and runs once per cached snapshot, not once per request.
   *
   * @param actorId the opaque billable subject
   * @return the snapshot in force for that actor
   */
  public EntitlementSnapshot forActor(String actorId) {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    String planKey = activePlanKey(actorId).orElseGet(this::entryPlanKey);
    Map<String, String> entitlements = new HashMap<>();
    jdbcTemplate
        .query(
            "SELECT entitlement_key, value FROM billing_plan_entitlement WHERE plan_key = ?",
            (rs, rowNum) -> Map.entry(rs.getString("entitlement_key"), rs.getString("value")),
            planKey)
        .forEach(entry -> entitlements.put(entry.getKey(), entry.getValue()));

    jdbcTemplate
        .query(
            "SELECT e.entitlement_key, e.value FROM billing_pack_entitlement e"
                + " JOIN billing_pack_subscription s ON s.pack_key = e.pack_key"
                + " WHERE s.actor_id = ? AND s.status IN ('TRIALING','ACTIVE','PAST_DUE')",
            (rs, rowNum) -> Map.entry(rs.getString("entitlement_key"), rs.getString("value")),
            actorId)
        .forEach(
            entry ->
                entitlements.merge(
                    entry.getKey(), entry.getValue(), EntitlementService::morePermissive));

    long available = creditLedgerService.wallet(actorId).map(WalletSnapshot::available).orElse(0L);
    return new EntitlementSnapshot(
        actorId, planKey, entitlements, available, Instant.now().plus(SNAPSHOT_TTL));
  }

  /**
   * Picks the wider of two values for the same entitlement key.
   *
   * <p>A dependency-free pure function over two strings, which is the sanctioned shape for a static
   * helper (ERR-127) — it has no collaborators to inject and nothing to mock.
   *
   * <p>Three cases, in the order the entitlement grammar declares its types: two numbers keep the
   * larger limit, two booleans OR (a granted capability stays granted), and anything else keeps the
   * plan's value. The last case is the conservative one on purpose: an opaque string like {@code
   * model.class} has no ordering, so "wider" is undefined and the plan — the thing the actor is
   * actually subscribed to — wins rather than whichever pack happened to be read last.
   *
   * @param planValue the value the plan already granted
   * @param packValue the value a pack grants for the same key
   * @return whichever grants more
   */
  private static String morePermissive(String planValue, String packValue) {
    try {
      return String.valueOf(Math.max(Long.parseLong(planValue), Long.parseLong(packValue)));
    } catch (NumberFormatException notNumeric) {
      boolean booleanPair = isBoolean(planValue) && isBoolean(packValue);
      if (booleanPair) {
        return String.valueOf(Boolean.parseBoolean(planValue) || Boolean.parseBoolean(packValue));
      }
      return planValue;
    }
  }

  private static boolean isBoolean(String value) {
    return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
  }

  private Optional<String> activePlanKey(String actorId) {
    return jdbcTemplate
        .query(
            "SELECT plan_key FROM billing_subscription"
                + " WHERE actor_id = ? AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
            (rs, rowNum) -> rs.getString("plan_key"),
            actorId)
        .stream()
        .findFirst();
  }

  /** The entry plan, chosen by rank rather than by name so renaming it needs no deploy. */
  private String entryPlanKey() {
    return jdbcTemplate
        .query(
            "SELECT plan_key FROM billing_plan WHERE is_active AND is_public"
                + " ORDER BY tier_rank LIMIT 1",
            (rs, rowNum) -> rs.getString("plan_key"))
        .stream()
        .findFirst()
        .orElse(CONSUMER_UNKNOWN_PLAN);
  }
}
