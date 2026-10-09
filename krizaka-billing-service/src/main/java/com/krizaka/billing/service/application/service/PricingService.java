package com.krizaka.billing.service.application.service;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import com.krizaka.billing.service.domain.exception.UnpricedModelException;
import com.krizaka.billing.service.domain.model.PricebookRate;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Resolves what a capability × model costs, from the versioned pricebook.
 *
 * <p>Two lookups, and the difference between them is the whole point of versioning. {@link
 * #currentRate} prices a <em>new</em> hold against whatever is live now; {@link #pinnedRate} prices
 * a <em>settlement</em> against the version its hold recorded, so a price published while an MLX
 * video was rendering cannot retroactively change what that video costs.
 *
 * <p>Resolution is by {@code (capability, model)} — precisely what the caller knows before the
 * request runs. A model-specific row wins over the capability default; if neither exists the
 * request is unpriced and is refused rather than guessed (see {@link UnpricedModelException}).
 */
@Service
public class PricingService {

  private static final String SELECT_COLUMNS =
      "version, capability, model_name, unit, credits_per_unit, minimum_credits, estimate_credits";

  private final JdbcTemplate jdbcTemplate;

  public PricingService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
  }

  /**
   * The rate in force now, used to price a hold.
   *
   * @param capability the capability about to run
   * @param modelName the resolved model, or {@code null} to take the capability default
   * @return the current rate, model-specific if one exists
   * @throws UnpricedModelException when neither a model row nor a capability default is current
   */
  public PricebookRate currentRate(BillableCapability capability, String modelName) {
    Objects.requireNonNull(capability, "capability must not be null");
    List<PricebookRate> rates =
        jdbcTemplate.query(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM credit_pricebook"
                + " WHERE capability = ? AND effective_to IS NULL"
                + "   AND (model_name = ? OR model_name IS NULL)"
                // A model-specific row outranks the capability default.
                + " ORDER BY model_name NULLS LAST LIMIT 1",
            rateMapper(),
            capability.name(),
            modelName);
    return rates.stream()
        .findFirst()
        .orElseThrow(() -> new UnpricedModelException(capability, modelName));
  }

  /**
   * The rate a hold was authorised under, used to settle it.
   *
   * @param version the pricebook version pinned on the hold
   * @param capability the capability the hold covers
   * @param modelName the model the hold covers, or {@code null}
   * @return the pinned rate
   * @throws UnpricedModelException when that version no longer carries a row for this pair — a
   *     pricebook row must be closed with {@code effective_to}, never deleted
   */
  public PricebookRate pinnedRate(int version, BillableCapability capability, String modelName) {
    Objects.requireNonNull(capability, "capability must not be null");
    List<PricebookRate> rates =
        jdbcTemplate.query(
            "SELECT "
                + SELECT_COLUMNS
                + " FROM credit_pricebook"
                + " WHERE version = ? AND capability = ?"
                + "   AND (model_name = ? OR model_name IS NULL)"
                + " ORDER BY model_name NULLS LAST LIMIT 1",
            rateMapper(),
            version,
            capability.name(),
            modelName);
    return rates.stream()
        .findFirst()
        .orElseThrow(() -> new UnpricedModelException(capability, modelName));
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
}
