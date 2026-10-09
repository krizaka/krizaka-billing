package com.krizaka.billing.service.infrastructure.config;

/**
 * RabbitMQ topology constants for the billing service (AGENTS.md §6). Contract copy — the topic
 * exchanges are shared, stable contracts; this service owns only its {@code orazaka.events.billing}
 * queue and its {@code <queue>.dlq}.
 */
final class AmqpConstants {

  private AmqpConstants() {}

  /**
   * Terminal job outcomes are <b>events</b>, not commands: the executor announces what happened on
   * {@code orazaka.events}, where the SSE relay already listens. {@code orazaka.jobs} carries work
   * requests in the other direction and never sees a {@code job.{id}.done}.
   */
  static final String EVENTS_EXCHANGE = "orazaka.events";

  static final String DLX_EXCHANGE = "orazaka.dlx";

  static final String SETTLEMENT_QUEUE = "orazaka.events.billing";

  /** Terminal job outcomes only: a completion settles the hold, a failure releases it. */
  static final String DONE_BINDING = "job.*.done";

  static final String ERROR_BINDING = "job.*.error";
  static final String SETTLEMENT_DLQ = SETTLEMENT_QUEUE + ".dlq";

  /**
   * Turns served without a hold while billing was unreachable (ADR-064). A queue of its own: a
   * different payload from a job outcome, and a record that must outlive a settlement backlog.
   */
  static final String UNMETERED_QUEUE = "orazaka.events.billing.unmetered";

  /** Contract copy of {@code UnmeteredTurn.ROUTING_KEY}. */
  static final String UNMETERED_BINDING = "evt.turn.unmetered";

  static final String UNMETERED_DLQ = UNMETERED_QUEUE + ".dlq";
}
