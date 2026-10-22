package com.krizaka.billing.service.domain.model;

import java.time.YearMonth;
import java.util.List;

/**
 * What one actor consumed in a calendar month, valued at the deployment's credit price.
 *
 * <p>A product that calls Orazaka with its own API key is one actor, so this is that product's
 * usage and cost this month. It is list-price value, not an invoice: the money anchor times the
 * credits settled, stated next to the plan the actor is on.
 *
 * @param actorId the opaque billable subject (the owner of the API key)
 * @param month the calendar month (UTC) covered
 * @param planKey the plan in force, or the entry plan when the actor has no subscription
 * @param planLabel the plan's display name
 * @param monthlyCreditGrant credits the plan grants each period
 * @param currency ISO 4217 code of the amounts stated
 * @param unitPriceMillicents millicents one credit costs at list price (1000 = 1 cent)
 * @param events settled usage events in the month
 * @param creditsCharged credits those events cost
 * @param byCapability the breakdown per capability × model, heaviest first
 */
public record ActorUsage(
    String actorId,
    YearMonth month,
    String planKey,
    String planLabel,
    long monthlyCreditGrant,
    String currency,
    int unitPriceMillicents,
    long events,
    long creditsCharged,
    List<Line> byCapability) {

  /** Compact canonical constructor (ERR-106). */
  public ActorUsage {
    if (events < 0 || creditsCharged < 0 || monthlyCreditGrant < 0 || unitPriceMillicents < 0) {
      throw new IllegalArgumentException("usage totals must be >= 0");
    }
    byCapability = byCapability == null ? List.of() : List.copyOf(byCapability);
  }

  /**
   * @return the cost in millicents (1000 = 1 cent)
   */
  public long costMillicents() {
    return creditsCharged * unitPriceMillicents;
  }

  /**
   * @return the cost in cents, rounded half up
   */
  public long costCents() {
    return (costMillicents() + 500) / 1000;
  }

  /**
   * @return the plan's grant still unspent this month, never below zero
   */
  public long includedCreditsRemaining() {
    return Math.max(0, monthlyCreditGrant - creditsCharged);
  }

  /**
   * One capability × model line.
   *
   * @param capability the capability
   * @param modelName the model, or {@code null} at the capability default
   * @param events settled events
   * @param creditsCharged credits charged
   * @param costMillicents the line's cost in millicents
   */
  public record Line(
      String capability, String modelName, long events, long creditsCharged, long costMillicents) {}
}
