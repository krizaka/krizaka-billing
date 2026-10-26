package com.krizaka.billing.service.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One dated version of a catalogue price — {@code @krizaka/settings} semantics (ADR-009).
 *
 * <p>Currency is CAD and amounts are plus taxes. A purchase stores {@code versionId} and keeps that
 * price whatever is scheduled afterwards.
 *
 * @param versionId the version's id, kept by what was bought at it
 * @param key {@code product.plan}
 * @param unit what the price buys
 * @param amountCents the amount in cents, or {@code null} when the price is on quote
 * @param availability who may see it
 * @param limits named integer limits (credits, includedHours...)
 * @param effectiveFrom when this version starts to apply
 */
public record PriceEntry(
    UUID versionId,
    String key,
    PriceUnit unit,
    Long amountCents,
    PriceAvailability availability,
    Map<String, Long> limits,
    Instant effectiveFrom) {

  /** The only currency. */
  public static final String CURRENCY = "CAD";

  /** Every amount is quoted plus taxes. */
  public static final String TAXES = "plus-taxes";

  /** Compact canonical constructor enforcing the entry's invariants. */
  public PriceEntry {
    if (key == null || !key.matches("[a-z][A-Za-z0-9]*\\.[A-Za-z][A-Za-z0-9]*")) {
      throw new IllegalArgumentException("key must be product.plan: " + key);
    }
    if (unit == null || availability == null || effectiveFrom == null) {
      throw new IllegalArgumentException("unit, availability and effectiveFrom are required");
    }
    if (amountCents != null && amountCents < 0) {
      throw new IllegalArgumentException("amountCents must be >= 0 or null (on quote)");
    }
    limits = limits == null ? Map.of() : Map.copyOf(limits);
  }

  /**
   * What the public sees of this entry: never an {@code internal} one, and a {@code preparing} one
   * without figure or limits.
   *
   * @return the public view, empty for an internal entry
   */
  public Optional<PriceView> publicView() {
    return switch (availability) {
      case INTERNAL -> Optional.empty();
      case PREPARING ->
          Optional.of(new PriceView(key, unit.wire(), null, availability.wire(), Map.of()));
      default ->
          Optional.of(new PriceView(key, unit.wire(), amountCents, availability.wire(), limits));
    };
  }

  /**
   * The full view for the admin screen.
   *
   * @return every field, with the version
   */
  public AdminPriceView adminView() {
    return new AdminPriceView(
        versionId,
        key,
        unit.wire(),
        amountCents,
        CURRENCY,
        TAXES,
        availability.wire(),
        limits,
        effectiveFrom);
  }
}
