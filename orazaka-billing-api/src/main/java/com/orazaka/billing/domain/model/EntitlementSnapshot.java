package com.orazaka.billing.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A point-in-time view of what an actor is allowed to do and how much volume they have left.
 *
 * <p>Entitlement gates <b>access</b>, credits gate <b>volume</b>: a {@code free} actor is not
 * entitled to video at any balance. Entitlements arrive as typed key/value strings ({@code
 * capability.video=true}, {@code concurrency.jobs=5}) so that a new plan or a new key is an admin
 * action, never a deploy.
 *
 * <p>The record reads its own payload ({@link #allows} / {@link #limit}) rather than exposing the
 * map to an external utility — smart payload, ERR-127.
 *
 * @param actorId the opaque billable subject this snapshot describes
 * @param planKey the plan the actor is subscribed to
 * @param entitlements the effective entitlement matrix (plan ∪ pack), defensively copied
 * @param availableCredits {@code balance_granted + balance_purchased − held} at capture time
 * @param expiresAt when this snapshot goes stale — the bounded staleness a cached read accepts
 */
public record EntitlementSnapshot(
    String actorId,
    String planKey,
    Map<String, String> entitlements,
    long availableCredits,
    Instant expiresAt) {

  /**
   * Plan key of a snapshot that could not be resolved. Owned here so no caller compares a plan-key
   * literal in production Java — the entry plan is chosen by {@code tier_rank}, never by name.
   */
  private static final String UNRESOLVED_PLAN = "__unresolved__";

  /**
   * Compact canonical constructor: validates and takes a defensive copy of the matrix (ERR-106).
   */
  public EntitlementSnapshot {
    if (actorId == null || actorId.isBlank()) {
      throw new IllegalArgumentException("actorId must not be blank");
    }
    if (planKey == null || planKey.isBlank()) {
      throw new IllegalArgumentException("planKey must not be blank");
    }
    Objects.requireNonNull(entitlements, "entitlements must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    entitlements = Map.copyOf(entitlements);
  }

  /**
   * The snapshot to use when billing could not answer.
   *
   * <p>A gate must not deny on an outage: {@link #allows} reads an absent key as a denial, which is
   * right for a plan that genuinely omits a capability and wrong for a lookup that never completed.
   * Distinguishing the two is what {@link #resolved()} is for — the caller passes an unresolved
   * snapshot through rather than locking every user out of a capability they pay for.
   *
   * @param actorId the actor whose entitlements could not be read
   * @param retryAfter when the caller may try again — kept short, this is an outage not a policy
   * @return an unresolved snapshot
   */
  public static EntitlementSnapshot unresolved(String actorId, Instant retryAfter) {
    return new EntitlementSnapshot(actorId, UNRESOLVED_PLAN, Map.of(), 0L, retryAfter);
  }

  /**
   * Whether this snapshot reflects a real plan.
   *
   * @return {@code false} when billing could not be reached, in which case {@link #allows} says
   *     nothing about the actor and must not be used to refuse them
   */
  public boolean resolved() {
    return !UNRESOLVED_PLAN.equals(planKey);
  }

  /**
   * Whether a boolean entitlement is granted.
   *
   * @param entitlementKey the entitlement key, e.g. {@code capability.video}
   * @return {@code true} only when the key is present and set to {@code true}; an absent key is a
   *     denial, so a plan that never mentions a capability does not accidentally unlock it
   */
  public boolean allows(String entitlementKey) {
    return Boolean.parseBoolean(entitlements.get(entitlementKey));
  }

  /**
   * Reads a numeric entitlement, falling back to a code default.
   *
   * @param entitlementKey the entitlement key, e.g. {@code concurrency.jobs}
   * @param defaultValue the value to use when the key is absent or not an integer
   * @return the configured limit, or {@code defaultValue} — deleting a key reverts cleanly instead
   *     of failing (ADR-031)
   */
  public int limit(String entitlementKey, int defaultValue) {
    String raw = entitlements.get(entitlementKey);
    if (raw == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }
}
