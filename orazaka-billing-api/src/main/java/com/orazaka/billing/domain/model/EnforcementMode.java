package com.orazaka.billing.domain.model;

/**
 * How a billing refusal behaves.
 *
 * <p>Three states rather than a boolean, because {@link #DRY_RUN} is what produces a
 * <i>measured</i> pricebook instead of a guessed one and what lets enforcement reach production
 * gradually.
 *
 * <p>The mode is a row in {@code billing_runtime_config}, read by the <b>billing service alone</b>
 * (ADR-033 §6): the hold protocol returns the outcome, never the policy, so the enforcement state
 * cannot diverge between the conversation, job and automation services.
 */
public enum EnforcementMode {

  /** No hold, no ledger write, no usage event — a contributor can run the stack without billing. */
  OFF,

  /**
   * Hold computed, usage and debit recorded, refusals logged but never enforced (shadow metering).
   */
  DRY_RUN,

  /** Refusals block the request and surface as a structured 402 or 403. */
  ENFORCING
}
