package com.krizaka.billing.service.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A price as the admin reads it: every field and the version.
 *
 * @param versionId the version's id
 * @param key {@code product.plan}
 * @param unit the unit
 * @param amountCents cents, {@code null} on quote
 * @param currency always CAD
 * @param taxes always plus-taxes
 * @param availability one of the four availabilities
 * @param limits named limits
 * @param effectiveFrom start of this version
 */
public record AdminPriceView(
    UUID versionId,
    String key,
    String unit,
    Long amountCents,
    String currency,
    String taxes,
    String availability,
    Map<String, Long> limits,
    Instant effectiveFrom) {}
