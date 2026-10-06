package com.orazaka.billingservice.domain.model;

import java.util.Objects;

/**
 * One typed key/value grant, the unit both plans and packs are built from (design §8.2, §14).
 *
 * <p>Typed key/value rather than columns is what makes "creating a fourth plan needs zero deploy"
 * true: a new entitlement key is a row, not a migration. The type travels with the value because
 * {@code "true"} and {@code "5"} are indistinguishable as strings, and a reader that guessed would
 * silently grant a boolean capability whenever a limit happened to parse.
 *
 * @param key the entitlement, e.g. {@code capability.video} or {@code concurrency.jobs}
 * @param valueType {@code boolean}, {@code int} or {@code string}
 * @param value the value, stored as text and interpreted per {@code valueType}
 */
public record Entitlement(String key, String valueType, String value) {

  private static final String BOOLEAN = "boolean";
  private static final String INT = "int";
  private static final String STRING = "string";

  /** Compact canonical constructor enforcing the grammar's invariants (ERR-106). */
  public Entitlement {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("entitlement key must not be blank");
    }
    Objects.requireNonNull(value, "entitlement value must not be null");
    valueType = valueType == null ? STRING : valueType;
    if (!BOOLEAN.equals(valueType) && !INT.equals(valueType) && !STRING.equals(valueType)) {
      throw new IllegalArgumentException("valueType must be boolean, int or string");
    }
    if (INT.equals(valueType)) {
      try {
        Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        // Rejected here rather than at read time: a limit that does not parse would fall back to a
        // code default and quietly grant something nobody configured (ADR-031).
        throw new IllegalArgumentException("entitlement " + key + " is not an int: " + value);
      }
    }
  }
}
