package com.krizaka.billing.service.application.service;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.service.domain.model.CatalogPack;
import com.krizaka.billing.service.domain.model.Entitlement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a pack costs and what it grants (ADR-036).
 *
 * <p>Named for what it now does. It used to be {@code PackCatalogService} and it used to answer
 * "what is this pack called, which shelf does it sit on, which métier does it target" as well —
 * questions that belong to the catalogue in the studio context, where {@code studio_i18n} already
 * provided the localisation they needed. Keeping both halves here would have made the service that
 * holds the credit ledger into a CMS, and every marketing copy change into a deploy of it. The
 * catalogue's own {@code PackCatalogService} lives in orazaka-studio-service.
 *
 * <p>Intentionally the same shape as {@link PlanCatalogService}, down to the wholesale replacement
 * of the entitlement matrix, because packs and plans grant through the same grammar. Two editors
 * that drifted apart would eventually mean two answers to "what does this unlock".
 *
 * <p>Pricing a pack is only half the story: {@link PackSubscriptionService} owns who holds one, and
 * {@link EntitlementService} unions what they hold onto their plan.
 */
@Service
public class PackPricingService {

  private static final String ENTITY_TYPE = "PACK";

  private static final String SELECT_COLUMNS =
      "SELECT pack_key, price_cents, included_credits, is_active";

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;

  public PackPricingService(
      JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(
            versionHistoryService, "BillingVersionHistoryService cannot be null");
  }

  /**
   * Every priced pack, with its entitlement matrix.
   *
   * <p>No category filter any more: browsing by shelf is the catalogue's query and it runs against
   * the studio context's {@code pack} table. Billing is asked "how much", never "which shelf".
   *
   * @param includeInactive whether to include withdrawn packs
   * @return the packs, by key
   */
  public List<CatalogPack> list(boolean includeInactive) {
    String sql =
        SELECT_COLUMNS
            + " FROM billing_pack"
            + (includeInactive ? "" : " WHERE is_active")
            + " ORDER BY pack_key";
    return jdbcTemplate.query(sql, (rs, rowNum) -> readPack(rs)).stream()
        .map(pack -> pack.withEntitlements(entitlementsOf(pack.packKey())))
        .toList();
  }

  /**
   * The price table the catalogue renders its cards from.
   *
   * <p>The whole table in one statement, because the caller is a marketplace page and pricing it
   * card by card would turn one browse into as many service hops as there are cards — the
   * arithmetic {@code PackPricingClient} states in its contract. Withdrawn packs are included:
   * their owners must still see what they paid.
   *
   * @return every pack's price, in key order
   */
  public List<PackPrice> prices() {
    return jdbcTemplate.query(
        SELECT_COLUMNS + " FROM billing_pack ORDER BY pack_key",
        (rs, rowNum) ->
            new PackPrice(
                rs.getString("pack_key"),
                rs.getInt("price_cents"),
                rs.getLong("included_credits"),
                rs.getBoolean("is_active")));
  }

  /**
   * One pack with its entitlements.
   *
   * @param packKey the pack
   * @return the pack, empty when no such key exists
   */
  public Optional<CatalogPack> find(String packKey) {
    return jdbcTemplate
        .query(
            SELECT_COLUMNS + " FROM billing_pack WHERE pack_key = ?",
            (rs, rowNum) -> readPack(rs),
            packKey)
        .stream()
        .findFirst()
        .map(pack -> pack.withEntitlements(entitlementsOf(packKey)));
  }

  /**
   * Creates or replaces a pack's price and its entitlement matrix.
   *
   * @param pack the pack to write
   * @param changedBy the admin's actor id
   * @return the pack as stored
   */
  @Transactional
  public CatalogPack save(CatalogPack pack, String changedBy) {
    Objects.requireNonNull(pack, "pack must not be null");
    if (changedBy == null || changedBy.isBlank()) {
      throw new IllegalArgumentException("changedBy must not be blank");
    }
    find(pack.packKey())
        .ifPresent(
            prior -> versionHistoryService.snapshot(ENTITY_TYPE, pack.packKey(), prior, changedBy));

    jdbcTemplate.update(
        "INSERT INTO billing_pack (pack_key, price_cents, included_credits, is_active)"
            + " VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (pack_key) DO UPDATE SET price_cents = EXCLUDED.price_cents,"
            + " included_credits = EXCLUDED.included_credits, is_active = EXCLUDED.is_active",
        pack.packKey(),
        pack.priceCents(),
        pack.includedCredits(),
        pack.isActive());

    jdbcTemplate.update("DELETE FROM billing_pack_entitlement WHERE pack_key = ?", pack.packKey());
    for (Entitlement entitlement : pack.entitlements()) {
      jdbcTemplate.update(
          "INSERT INTO billing_pack_entitlement (pack_key, entitlement_key, value_type,"
              + " value) VALUES (?, ?, ?, ?)",
          pack.packKey(),
          entitlement.key(),
          entitlement.valueType(),
          entitlement.value());
    }
    return find(pack.packKey()).orElseThrow();
  }

  /**
   * Withdraws a pack from sale without deleting it.
   *
   * <p>Withdrawing does not revoke it: actors who already bought the pack keep what they paid for,
   * which is why {@link PackSubscriptionService} reads live subscriptions without joining on {@code
   * is_active}. Pulling an offer off the shelf and confiscating it are different decisions.
   *
   * @param packKey the pack to withdraw
   * @param changedBy the admin's actor id
   * @return {@code true} when a pack was withdrawn
   */
  @Transactional
  public boolean withdraw(String packKey, String changedBy) {
    Optional<CatalogPack> prior = find(packKey);
    if (prior.isEmpty()) {
      return false;
    }
    versionHistoryService.snapshot(ENTITY_TYPE, packKey, prior.get(), changedBy);
    jdbcTemplate.update("UPDATE billing_pack SET is_active = FALSE WHERE pack_key = ?", packKey);
    return true;
  }

  private List<Entitlement> entitlementsOf(String packKey) {
    return jdbcTemplate.query(
        "SELECT entitlement_key, value_type, value FROM billing_pack_entitlement"
            + " WHERE pack_key = ? ORDER BY entitlement_key",
        (rs, rowNum) ->
            new Entitlement(
                rs.getString("entitlement_key"), rs.getString("value_type"), rs.getString("value")),
        packKey);
  }

  private static CatalogPack readPack(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new CatalogPack(
        rs.getString("pack_key"),
        rs.getInt("price_cents"),
        rs.getLong("included_credits"),
        rs.getBoolean("is_active"),
        List.of());
  }
}
