package com.krizaka.billing.service.infrastructure.config;

/**
 * The queues the billing service owns and what each one binds. The exchanges belong to the platform
 * the service runs on ({@code krizaka.messaging.exchanges}, krizaka-messaging).
 */
final class AmqpConstants {

  private AmqpConstants() {}

  /**
   * Terminal job outcomes are <b>events</b>, not commands: the executor announces what happened on
   * the events exchange. A completion settles the hold, a failure releases it.
   */
  static final String SETTLEMENT_QUEUE = "krizaka.billing.settlements";

  /** Terminal job outcomes only: a completion settles the hold, a failure releases it. */
  static final String DONE_BINDING = "job.*.done";

  static final String ERROR_BINDING = "job.*.error";
  static final String SETTLEMENT_DLQ = SETTLEMENT_QUEUE + ".dlq";

  /**
   * Turns served without a hold while billing was unreachable (ADR-064). A queue of its own: a
   * different payload from a job outcome, and a record that must outlive a settlement backlog.
   */
  static final String UNMETERED_QUEUE = "krizaka.billing.unmetered";

  /** Contract copy of {@code UnmeteredTurn.ROUTING_KEY}. */
  static final String UNMETERED_BINDING = "evt.turn.unmetered";

  static final String UNMETERED_DLQ = UNMETERED_QUEUE + ".dlq";
}
