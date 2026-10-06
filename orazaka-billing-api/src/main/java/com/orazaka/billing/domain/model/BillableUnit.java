package com.orazaka.billing.domain.model;

/**
 * The unit in which a capability's consumption is measured before the pricebook converts it to
 * credits.
 *
 * <p>Each unit is captured where the truth already exists — the engine metadata for chat, the
 * worker report for media — and never re-derived on the billing side.
 */
public enum BillableUnit {

  /**
   * 1,000 tokens (prompt + completion, weighted). The anchor unit: 1 credit = 1 kilotoken on the
   * default local chat model.
   */
  KILOTOKEN,

  /** Images × diffusion steps ÷ 100. */
  IMAGE_STEP,

  /** One second of generated video output. */
  OUTPUT_SECOND,

  /** 1,000 characters of input text (speech synthesis). */
  KILOCHAR,

  /** One minute of source audio (transcription). */
  AUDIO_MINUTE,

  /**
   * One second of GPU occupancy — the calibration basis for owned hardware, never shown to users.
   */
  GPU_SECOND,

  /** One invocation — flat fees such as workflow orchestration or a vision analysis call. */
  CALL
}
