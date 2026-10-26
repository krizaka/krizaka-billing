package com.krizaka.billing.service.domain.model;

import java.util.Arrays;

/** Who may see a catalogue entry — the same four values as {@code @krizaka/settings}. */
public enum PriceAvailability {
  /** Shown and sold. */
  PUBLIC("public"),
  /** Never shown outside the admin view. */
  INTERNAL("internal"),
  /** Sold in limited quantity to the founding customers. */
  FOUNDER_LIMITED("founder-limited"),
  /** Announced, not yet sold: shown without a figure. */
  PREPARING("preparing");

  private final String wire;

  PriceAvailability(String wire) {
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
   * @return the availability
   */
  public static PriceAvailability fromWire(String value) {
    return Arrays.stream(values())
        .filter(a -> a.wire.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown availability: " + value));
  }
}
