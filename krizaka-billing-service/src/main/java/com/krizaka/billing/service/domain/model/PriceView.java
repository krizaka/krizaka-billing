package com.krizaka.billing.service.domain.model;

import java.util.Map;

/**
 * A price as the public reads it (CAD, plus taxes).
 *
 * @param key {@code product.plan}
 * @param unit the unit
 * @param amountCents cents, {@code null} on quote or while preparing
 * @param availability public, founder-limited or preparing
 * @param limits named limits, empty while preparing
 */
public record PriceView(
    String key, String unit, Long amountCents, String availability, Map<String, Long> limits) {}
