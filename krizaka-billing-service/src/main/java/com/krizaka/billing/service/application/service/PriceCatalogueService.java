package com.krizaka.billing.service.application.service;

import com.krizaka.billing.service.domain.model.AdminPriceView;
import com.krizaka.billing.service.domain.model.PriceAvailability;
import com.krizaka.billing.service.domain.model.PriceChange;
import com.krizaka.billing.service.domain.model.PriceEntry;
import com.krizaka.billing.service.domain.model.PriceUnit;
import com.krizaka.billing.service.domain.model.PriceView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The price catalogue — same semantics as the prices of {@code @krizaka/settings} (ADR-009,
 * krizaka/orazaka#417). One source: packs read their amount through it, never from their own copy.
 *
 * <p>Versions are append-only rows of {@code billing_price}; the one in force at an instant is the
 * latest that started at or before it. A change is a new version from a date that is not in the
 * past, and one audit snapshot ({@link BillingVersionHistoryService}) is written per change. A
 * purchase keeps the {@code versionId} it was made at.
 */
@Service
public class PriceCatalogueService {

  private static final String ENTITY_TYPE = "PRICE";

  private static final String COLUMNS =
      "SELECT version_id, price_key, unit, amount_cents, availability, limits::text AS limits,"
          + " effective_from FROM billing_price";

  private static final TypeReference<Map<String, Long>> LIMITS = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public PriceCatalogueService(
      JdbcTemplate jdbcTemplate,
      BillingVersionHistoryService versionHistoryService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(versionHistoryService, "BillingVersionHistoryService is null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
    this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
  }

  /**
   * The entry in force for a key at an instant.
   *
   * @param key {@code product.plan}
   * @param at the instant
   * @return the entry, empty when the key is unknown or not yet started
   */
  public Optional<PriceEntry> entry(String key, Instant at) {
    return jdbcTemplate
        .query(
            COLUMNS
                + " WHERE price_key = ? AND effective_from <= ?"
                + " ORDER BY effective_from DESC LIMIT 1",
            (rs, rowNum) -> read(rs),
            key,
            Timestamp.from(at))
        .stream()
        .findFirst();
  }

  /**
   * Every entry in force at an instant, by key.
   *
   * @param at the instant
   * @return the entries
   */
  public List<PriceEntry> catalogue(Instant at) {
    return jdbcTemplate.query(
        COLUMNS
            + " b WHERE effective_from = (SELECT max(effective_from) FROM billing_price"
            + " WHERE price_key = b.price_key AND effective_from <= ?) ORDER BY price_key",
        (rs, rowNum) -> read(rs),
        Timestamp.from(at));
  }

  /**
   * What the public sees now: no internal entry, a preparing one without figure.
   *
   * @return the public views
   */
  public List<PriceView> publicCatalogue() {
    return catalogue(clock.instant()).stream()
        .map(PriceEntry::publicView)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * The admin's view now: every entry in force.
   *
   * @return the full views
   */
  public List<AdminPriceView> adminCatalogue() {
    return catalogue(clock.instant()).stream().map(PriceEntry::adminView).toList();
  }

  /**
   * Every version of a key, oldest first.
   *
   * @param key {@code product.plan}
   * @return the versions
   */
  public List<AdminPriceView> history(String key) {
    return jdbcTemplate
        .query(
            COLUMNS + " WHERE price_key = ? ORDER BY effective_from", (rs, rowNum) -> read(rs), key)
        .stream()
        .map(PriceEntry::adminView)
        .toList();
  }

  /**
   * The versions of a key that start after an instant.
   *
   * @param key {@code product.plan}
   * @param at the instant
   * @return the scheduled versions, soonest first
   */
  public List<AdminPriceView> upcoming(String key, Instant at) {
    return history(key).stream().filter(v -> v.effectiveFrom().isAfter(at)).toList();
  }

  /**
   * Schedules the next version of an existing key.
   *
   * @param actor the admin's id, recorded in the audit
   * @param key {@code product.plan}, already in the catalogue
   * @param change the new unit, amount, availability and limits
   * @param effectiveFrom when it starts; never in the past
   * @return the new version
   */
  @Transactional
  public AdminPriceView schedule(
      String actor, String key, PriceChange change, Instant effectiveFrom) {
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("actor must not be blank");
    }
    PriceChange.requireNotInPast(effectiveFrom, clock.instant());
    Integer known =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_price WHERE price_key = ?", Integer.class, key);
    if (known == null || known == 0) {
      throw new IllegalArgumentException("unknown price key: " + key);
    }
    UUID versionId = UUID.randomUUID();
    PriceEntry next =
        new PriceEntry(
            versionId,
            key,
            change.unit(),
            change.amountCents(),
            change.availability(),
            change.limits(),
            effectiveFrom);
    jdbcTemplate.update(
        "INSERT INTO billing_price (version_id, price_key, unit, amount_cents, availability,"
            + " limits, effective_from, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        versionId,
        key,
        next.unit().wire(),
        next.amountCents(),
        next.availability().wire(),
        objectMapper.writeValueAsString(next.limits()),
        Timestamp.from(effectiveFrom),
        actor);
    versionHistoryService.snapshot(ENTITY_TYPE, key, next.adminView(), actor);
    return next.adminView();
  }

  private PriceEntry read(ResultSet rs) throws SQLException {
    long amount = rs.getLong("amount_cents");
    return new PriceEntry(
        rs.getObject("version_id", UUID.class),
        rs.getString("price_key"),
        PriceUnit.fromWire(rs.getString("unit")),
        rs.wasNull() ? null : amount,
        PriceAvailability.fromWire(rs.getString("availability")),
        objectMapper.readValue(rs.getString("limits"), LIMITS),
        rs.getTimestamp("effective_from").toInstant());
  }
}
