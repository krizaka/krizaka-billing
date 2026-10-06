package com.orazaka.billing.domain.model;

/**
 * The capabilities whose consumption is metered and charged against a credit wallet.
 *
 * <p>Deliberate <b>contract-copy</b> of {@code business.api.Capability} (AGENTS.md §6): this is a
 * Tier-1 contract with zero implementation dependencies and {@code orazaka-business} is Tier-3, so
 * the billing contract declares its own enum rather than importing the business one. The shape is
 * pinned by a fixture, exactly like the AMQP contract copies.
 *
 * <p>{@code ADMIN} is intentionally absent: administration is not a billable capability.
 */
public enum BillableCapability {

  /**
   * Interactive text generation, including code generation. Metered in {@link
   * BillableUnit#KILOTOKEN}.
   */
  CHAT,

  /** Image generation and analysis. Metered in {@link BillableUnit#IMAGE_STEP}. */
  IMAGE,

  /**
   * Speech synthesis and transcription. Metered in {@link BillableUnit#KILOCHAR} or {@link
   * BillableUnit#AUDIO_MINUTE}.
   */
  AUDIO,

  /** Video generation. Metered in {@link BillableUnit#OUTPUT_SECOND}. */
  VIDEO,

  /**
   * Agent loops and workflow runs — a roll-up of children plus an orchestration {@link
   * BillableUnit#CALL}.
   */
  AGENT
}
