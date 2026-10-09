package com.krizaka.billing.service.application.service;

import com.krizaka.billing.domain.model.EnforcementMode;
import com.krizaka.billing.service.domain.model.ConfigKeySpec;
import com.krizaka.billing.service.domain.model.FailMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and validates the behavioural switches in {@code billing_runtime_config} — this service's
 * own copy of the runtime-config shape (contract-copy doctrine; no cross-database read).
 *
 * <p>Every getter falls back to a code default, so an admin deleting a row reverts to sane
 * behaviour instead of breaking startup. Every write goes through {@link #whitelist()}, so an
 * unknown key or an out-of-domain value is a rejection rather than a silently bricked gate.
 *
 * <p>Values are read per call rather than cached. The table is eight rows and Postgres serves it
 * from shared buffers, and the point of putting the enforcement mode in the database is that
 * flipping it during an incident takes effect <em>now</em> — a cache would trade that away for
 * latency the hot path does not need.
 */
@Service
public class BillingConfigurationService {

  private static final Logger log = LoggerFactory.getLogger(BillingConfigurationService.class);

  static final String KEY_ENFORCEMENT_MODE = "billing.enforcement.mode";
  static final String KEY_FAIL_MODE_CHAT = "billing.fail-mode.chat";
  static final String KEY_FAIL_MODE_MEDIA = "billing.fail-mode.media";
  static final String KEY_HOLD_TTL_SECONDS = "billing.hold.ttl-seconds";
  static final String KEY_UNIT_PRICE_MILLICENTS = "billing.credit.unit-price-millicents";
  static final String KEY_OVERSHOOT_MAX = "billing.overshoot.max-credits";
  static final String KEY_LOW_BALANCE_PERCENT = "billing.low-balance.percent";
  static final String KEY_ADJUSTMENT_DAILY_MAX = "billing.adjustment.daily-max-credits";

  private static final Map<String, ConfigKeySpec> WHITELIST = buildWhitelist();

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;

  public BillingConfigurationService(
      JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(
            versionHistoryService, "BillingVersionHistoryService cannot be null");
  }

  private static Map<String, ConfigKeySpec> buildWhitelist() {
    Set<String> modes =
        Arrays.stream(EnforcementMode.values()).map(Enum::name).collect(Collectors.toSet());
    Set<String> failModes =
        Arrays.stream(FailMode.values()).map(Enum::name).collect(Collectors.toSet());
    Map<String, ConfigKeySpec> specs = new LinkedHashMap<>();
    specs.put(
        KEY_ENFORCEMENT_MODE,
        new ConfigKeySpec(KEY_ENFORCEMENT_MODE, "string", modes, EnforcementMode.DRY_RUN.name()));
    specs.put(
        KEY_FAIL_MODE_CHAT,
        new ConfigKeySpec(KEY_FAIL_MODE_CHAT, "string", failModes, FailMode.FAIL_OPEN.name()));
    specs.put(
        KEY_FAIL_MODE_MEDIA,
        new ConfigKeySpec(KEY_FAIL_MODE_MEDIA, "string", failModes, FailMode.FAIL_CLOSED.name()));
    specs.put(
        KEY_HOLD_TTL_SECONDS, new ConfigKeySpec(KEY_HOLD_TTL_SECONDS, "int", Set.of(), "1800"));
    specs.put(
        KEY_UNIT_PRICE_MILLICENTS,
        new ConfigKeySpec(KEY_UNIT_PRICE_MILLICENTS, "int", Set.of(), "100"));
    specs.put(KEY_OVERSHOOT_MAX, new ConfigKeySpec(KEY_OVERSHOOT_MAX, "int", Set.of(), "50"));
    specs.put(
        KEY_LOW_BALANCE_PERCENT, new ConfigKeySpec(KEY_LOW_BALANCE_PERCENT, "int", Set.of(), "10"));
    specs.put(
        KEY_ADJUSTMENT_DAILY_MAX,
        new ConfigKeySpec(KEY_ADJUSTMENT_DAILY_MAX, "int", Set.of(), "10000"));
    return Map.copyOf(specs);
  }

  /**
   * The declared key vocabulary the settings surface accepts.
   *
   * @return every known key with its type, domain and code default
   */
  public Map<String, ConfigKeySpec> whitelist() {
    return WHITELIST;
  }

  /**
   * The master gate. Read by this service alone — the hold protocol returns the outcome, never the
   * policy, so the enforcement state cannot diverge across the producers.
   *
   * @return the current mode, or {@code DRY_RUN} if the row is missing or unparseable
   */
  public EnforcementMode enforcementMode() {
    return readEnum(KEY_ENFORCEMENT_MODE, EnforcementMode.class);
  }

  /**
   * What a consumer should do when billing is unreachable for an interactive request.
   *
   * @return the chat fail mode, {@code FAIL_OPEN} by default
   */
  public FailMode chatFailMode() {
    return readEnum(KEY_FAIL_MODE_CHAT, FailMode.class);
  }

  /**
   * What a consumer should do when billing is unreachable for a high-cost job.
   *
   * @return the media fail mode, {@code FAIL_CLOSED} by default
   */
  public FailMode mediaFailMode() {
    return readEnum(KEY_FAIL_MODE_MEDIA, FailMode.class);
  }

  /**
   * How long an {@code ACTIVE} hold survives before the sweeper releases it.
   *
   * @return the TTL in seconds
   */
  public int holdTtlSeconds() {
    return readInt(KEY_HOLD_TTL_SECONDS);
  }

  /**
   * How far a settlement may exceed its hold before being capped.
   *
   * @return the allowance in credits — bounded overshoot beats killing a stream mid-token
   */
  public int overshootMaxCredits() {
    return readInt(KEY_OVERSHOOT_MAX);
  }

  /**
   * The remaining-balance percentage that emits {@code evt.wallet.low-balance}.
   *
   * @return the threshold percent
   */
  public int lowBalancePercent() {
    return readInt(KEY_LOW_BALANCE_PERCENT);
  }

  /**
   * The per-admin daily ceiling on manual adjustments — a compromised admin account must not be
   * able to mint unbounded balance.
   *
   * @return the ceiling in credits
   */
  public int adjustmentDailyMaxCredits() {
    return readInt(KEY_ADJUSTMENT_DAILY_MAX);
  }

  /**
   * The money anchor.
   *
   * <p>Millicents, not cents, since ADR-047: one credit is a tenth of a cent, which no integer
   * number of cents can express. Restating the unit was not cosmetic — leaving the key named {@code
   * unit-price-cents} while the credit got ten times finer would have read as a tenfold price rise
   * that nobody decided.
   *
   * @return millicents per credit at list price; 1000 millicents = 1 cent
   */
  public int unitPriceMillicents() {
    return readInt(KEY_UNIT_PRICE_MILLICENTS);
  }

  /**
   * Applies an admin change after validating it against the whitelist, snapshotting the prior value
   * in the same transaction.
   *
   * <p>The snapshot is not optional bookkeeping: it is what makes reverting a bad enforcement flip
   * a one-click operation, which is the entire argument for keeping the mode in the database rather
   * than in yaml.
   *
   * @param key the config key
   * @param value the proposed value
   * @param changedBy the admin's actor id, recorded on the snapshot
   * @return {@code true} when the change was stored, {@code false} when the key is unknown or the
   *     value falls outside its declared domain
   */
  @Transactional
  public boolean update(String key, String value, String changedBy) {
    ConfigKeySpec spec = WHITELIST.get(key);
    if (spec == null || !spec.accepts(value)) {
      log.warn("Rejected billing config change: key={} value={} by={}", key, value, changedBy);
      return false;
    }
    String previous = rawValue(key).orElse(spec.defaultValue());
    versionHistoryService.snapshot(
        "CONFIG",
        key,
        new ConfigKeySpec(key, spec.valueType(), spec.domain(), previous),
        changedBy);
    jdbcTemplate.update(
        "UPDATE billing_runtime_config SET config_value = ? WHERE config_key = ?", value, key);
    log.info("Billing config changed: key={} from={} to={} by={}", key, previous, value, changedBy);
    return true;
  }

  private <E extends Enum<E>> E readEnum(String key, Class<E> type) {
    ConfigKeySpec spec = WHITELIST.get(key);
    String raw = rawValue(key).filter(spec::accepts).orElse(spec.defaultValue());
    return Enum.valueOf(type, raw);
  }

  private int readInt(String key) {
    ConfigKeySpec spec = WHITELIST.get(key);
    String raw = rawValue(key).filter(spec::accepts).orElse(spec.defaultValue());
    return Integer.parseInt(raw.trim());
  }

  private Optional<String> rawValue(String key) {
    return jdbcTemplate
        .query(
            "SELECT config_value FROM billing_runtime_config WHERE config_key = ?",
            (rs, rowNum) -> rs.getString("config_value"),
            key)
        .stream()
        .findFirst();
  }
}
