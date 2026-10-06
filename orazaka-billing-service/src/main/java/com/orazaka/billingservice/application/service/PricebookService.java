package com.orazaka.billingservice.application.service;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.BillableUnit;
import com.orazaka.billingservice.domain.model.MarginPreview;
import com.orazaka.billingservice.domain.model.PricebookRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes credit rates, and shows what a publication would have cost before it happens.
 *
 * <p>A rate is <b>never updated in place</b>. Publishing closes the current row with {@code
 * effective_to} and inserts a new version, because holds pin the version they were priced with: a
 * mutated row would retroactively change what an already-authorised MLX render costs, which is the
 * one thing versioning exists to prevent. It is also why a price change is append-only in the same
 * sense the ledger is — the old number stays readable, so a six-month-old invoice can still be
 * explained.
 */
@Service
public class PricebookService {

  private static final Logger log = LoggerFactory.getLogger(PricebookService.class);

  /** The replay window of design §12 — a month of traffic is the evidence a price change needs. */
  private static final Duration PREVIEW_WINDOW = Duration.ofDays(30);

  private static final String SELECT_COLUMNS =
      "version, capability, model_name, unit, credits_per_unit, minimum_credits, estimate_credits";

  private final JdbcTemplate jdbcTemplate;
  private final BillingVersionHistoryService versionHistoryService;

  public PricebookService(
      JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
    this.versionHistoryService =
        Objects.requireNonNull(
            versionHistoryService, "BillingVersionHistoryService cannot be null");
  }

  /**
   * Every rate currently in force.
   *
   * @return the live pricebook, ordered for display
   */
  public List<PricebookRate> current() {
    return jdbcTemplate.query(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM credit_pricebook WHERE effective_to IS NULL"
            + " ORDER BY capability, model_name NULLS FIRST",
        rateMapper());
  }

  /**
   * Every version of one capability × model, newest first — the backing query for a rollback view.
   *
   * @param capability the capability
   * @param modelName the model, or {@code null} for the capability default
   * @return the rate's full history
   */
  public List<PricebookRate> history(BillableCapability capability, String modelName) {
    Objects.requireNonNull(capability, "capability must not be null");
    return jdbcTemplate.query(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM credit_pricebook WHERE capability = ?"
            + "   AND COALESCE(model_name, '*') = COALESCE(?, '*')"
            + " ORDER BY version DESC",
        rateMapper(),
        capability.name(),
        modelName);
  }

  /**
   * Replays the last 30 days of real consumption against a proposed rate.
   *
   * <p>Reprices the recorded {@code quantity} of each usage event rather than scaling its charged
   * credits, so the floor is re-applied per event exactly as it would be in production — scaling
   * the totals would quietly misreport any rate whose minimum does the work.
   *
   * @param capability the capability being repriced
   * @param modelName the model being repriced, or {@code null} for the capability default
   * @param proposedCreditsPerUnit the rate under consideration
   * @param proposedMinimumCredits the floor under consideration
   * @return what the sampled traffic did cost and would have cost
   */
  public MarginPreview preview(
      BillableCapability capability,
      String modelName,
      BigDecimal proposedCreditsPerUnit,
      long proposedMinimumCredits) {
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(proposedCreditsPerUnit, "proposedCreditsPerUnit must not be null");

    List<UsageSample> samples =
        jdbcTemplate.query(
            "SELECT quantity, credits_charged FROM usage_event"
                + " WHERE capability = ? AND COALESCE(model_name, '*') = COALESCE(?, '*')"
                + "   AND occurred_at >= now() - make_interval(secs => ?)",
            (rs, rowNum) ->
                new UsageSample(rs.getBigDecimal("quantity"), rs.getLong("credits_charged")),
            capability.name(),
            modelName,
            (double) PREVIEW_WINDOW.toSeconds());

    long currentCredits = 0;
    long proposedCredits = 0;
    for (UsageSample sample : samples) {
      currentCredits += sample.creditsCharged();
      proposedCredits +=
          repriced(sample.quantity(), proposedCreditsPerUnit, proposedMinimumCredits);
    }
    return new MarginPreview(
        capability, modelName, samples.size(), currentCredits, proposedCredits);
  }

  /**
   * Publishes a new rate for a capability × model.
   *
   * <p>Closes the current row and inserts the next version in one transaction, so the partial
   * unique index on {@code effective_to IS NULL} is never violated and no window exists in which a
   * hold could find two live rates — or none.
   *
   * @param capability the capability priced
   * @param modelName the model priced, or {@code null} for the capability default
   * @param unit the unit the rate is expressed in
   * @param creditsPerUnit credits per unit of measured consumption
   * @param minimumCredits the floor applied after conversion
   * @param estimateCredits what a hold reserves before execution
   * @param publishedBy the admin's actor id, recorded in the snapshot
   * @return the published rate
   */
  @Transactional
  public PricebookRate publish(
      BillableCapability capability,
      String modelName,
      BillableUnit unit,
      BigDecimal creditsPerUnit,
      long minimumCredits,
      long estimateCredits,
      String publishedBy) {
    Objects.requireNonNull(capability, "capability must not be null");
    Objects.requireNonNull(unit, "unit must not be null");
    Objects.requireNonNull(creditsPerUnit, "creditsPerUnit must not be null");
    if (publishedBy == null || publishedBy.isBlank()) {
      // An unattributable price change is exactly what billing_version_history exists to prevent.
      throw new IllegalArgumentException("publishedBy must not be blank");
    }

    Optional<PricebookRate> superseded = currentRate(capability, modelName);
    superseded.ifPresent(
        rate -> versionHistoryService.snapshot("PRICEBOOK", entityKey(rate), rate, publishedBy));

    int closed =
        jdbcTemplate.update(
            "UPDATE credit_pricebook SET effective_to = now()"
                + " WHERE capability = ? AND COALESCE(model_name, '*') = COALESCE(?, '*')"
                + "   AND effective_to IS NULL",
            capability.name(),
            modelName);

    int version = superseded.map(PricebookRate::version).orElse(0) + 1;
    jdbcTemplate.update(
        "INSERT INTO credit_pricebook (version, capability, model_name, unit, credits_per_unit,"
            + " minimum_credits, estimate_credits) VALUES (?, ?, ?, ?, ?, ?, ?)",
        version,
        capability.name(),
        modelName,
        unit.name(),
        creditsPerUnit,
        minimumCredits,
        estimateCredits);

    log.info(
        "Published pricebook v{} for {}×{} by {} (superseded {} row)",
        version,
        capability,
        modelName == null ? "*" : modelName,
        publishedBy,
        closed);
    return currentRate(capability, modelName)
        .orElseThrow(() -> new IllegalStateException("published rate is not readable back"));
  }

  private Optional<PricebookRate> currentRate(BillableCapability capability, String modelName) {
    return jdbcTemplate
        .query(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM credit_pricebook WHERE capability = ?"
                + "   AND COALESCE(model_name, '*') = COALESCE(?, '*') AND effective_to IS NULL",
            rateMapper(),
            capability.name(),
            modelName)
        .stream()
        .findFirst();
  }

  /**
   * Reprices one measured quantity, applying the floor per event exactly as settlement does.
   *
   * <p>Dependency-free pure arithmetic over the proposal — a sanctioned static (ERR-127b).
   */
  private static long repriced(BigDecimal quantity, BigDecimal creditsPerUnit, long minimum) {
    long converted =
        quantity.multiply(creditsPerUnit).setScale(0, RoundingMode.CEILING).longValue();
    return Math.max(converted, minimum);
  }

  /** The history key of a rate: {@code CAPABILITY:model}, with {@code *} for the default. */
  private static String entityKey(PricebookRate rate) {
    return rate.capability().name() + ":" + (rate.modelName() == null ? "*" : rate.modelName());
  }

  private RowMapper<PricebookRate> rateMapper() {
    return (rs, rowNum) ->
        new PricebookRate(
            rs.getInt("version"),
            BillableCapability.valueOf(rs.getString("capability")),
            rs.getString("model_name"),
            BillableUnit.valueOf(rs.getString("unit")),
            rs.getBigDecimal("credits_per_unit"),
            rs.getLong("minimum_credits"),
            rs.getLong("estimate_credits"));
  }

  /** One replayed usage event: what was measured, and what it was charged at the time. */
  private record UsageSample(BigDecimal quantity, long creditsCharged) {}
}
