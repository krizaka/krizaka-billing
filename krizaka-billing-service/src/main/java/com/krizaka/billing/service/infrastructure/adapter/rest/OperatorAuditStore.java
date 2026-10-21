package com.krizaka.billing.service.infrastructure.adapter.rest;

import java.time.Instant;
import java.util.List;

/** Where the operator audit lines of billing are written and read (ADR-085). */
public interface OperatorAuditStore {

  /** One operator write: who, what, which target, in which request, and how it ended. */
  record Line(
      String actorId,
      String actorLabel,
      String action,
      String targetId,
      String requestId,
      String outcome) {}

  /** A stored line. */
  record Stored(
      long id,
      Instant at,
      String actor,
      String action,
      String targetId,
      String requestId,
      String outcome) {}

  void append(Line line);

  /** The newest lines first. */
  List<Stored> recent(int limit);
}
