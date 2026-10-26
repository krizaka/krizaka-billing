package com.krizaka.billing.service.domain.model;

import java.util.Arrays;

/** What one price buys — the units of {@code @krizaka/settings}. */
public enum PriceUnit {
  /** Per month. */
  MONTH("month"),
  /** Per year. */
  YEAR("year"),
  /** Paid once. */
  ONCE("once"),
  /** Per document. */
  DOCUMENT("document"),
  /** Per order. */
  ORDER("order"),
  /** Per credit pack. */
  PACK("pack"),
  /** Per hour. */
  HOUR("hour");

  private final String wire;

  PriceUnit(String wire) {
    this.wire = wire;
  }

  /**
   * The value stored and exchanged.
   *
   * @return the wire value
   */
  public String wire() {
    return wire;
  }

  /**
   * Parses a wire value.
   *
   * @param value the stored or submitted value
   * @return the unit
   */
  public static PriceUnit fromWire(String value) {
    return Arrays.stream(values())
        .filter(u -> u.wire.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown unit: " + value));
  }
}
