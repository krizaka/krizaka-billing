package com.orazaka.billingservice.domain.exception;

/**
 * Thrown when an admin's manual adjustments would pass their daily ceiling (design §12.1).
 *
 * <p>Not a validation error but a <b>control</b>: a compromised admin account must not be able to
 * mint unbounded balance, and the ceiling is what turns that from a trust assumption into a limit.
 * Above it the design calls for a second admin, so the refusal names the shortfall rather than
 * being a flat denial — the operator needs to know what they can still do today.
 */
public class AdjustmentCeilingExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient String adminId;
  private final long requested;
  private final long alreadyAdjusted;
  private final long dailyMax;

  /**
   * Constructs the refusal.
   *
   * @param adminId the admin who hit the ceiling
   * @param requested the absolute size of the adjustment attempted
   * @param alreadyAdjusted how much they have already moved today, in absolute credits
   * @param dailyMax their ceiling
   */
  public AdjustmentCeilingExceededException(
      String adminId, long requested, long alreadyAdjusted, long dailyMax) {
    super(
        "Adjustment of %d credits would put %s at %d against a daily ceiling of %d"
            .formatted(requested, adminId, alreadyAdjusted + requested, dailyMax));
    this.adminId = adminId;
    this.requested = requested;
    this.alreadyAdjusted = alreadyAdjusted;
    this.dailyMax = dailyMax;
  }

  /**
   * @return the admin who hit the ceiling
   */
  public String adminId() {
    return adminId;
  }

  /**
   * @return the absolute size of the refused adjustment
   */
  public long requested() {
    return requested;
  }

  /**
   * @return how much this admin has already moved today
   */
  public long alreadyAdjusted() {
    return alreadyAdjusted;
  }

  /**
   * @return the ceiling in force
   */
  public long dailyMax() {
    return dailyMax;
  }

  /**
   * @return what this admin can still adjust today, never negative
   */
  public long remaining() {
    return Math.max(dailyMax - alreadyAdjusted, 0);
  }
}
