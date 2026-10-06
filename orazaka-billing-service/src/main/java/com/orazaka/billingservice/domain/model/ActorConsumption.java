package com.orazaka.billingservice.domain.model;

/**
 * One actor's consumption over a window — the "top consumers" view of design §12.
 *
 * @param actorId the opaque billable subject
 * @param events how many settled usage events they generated
 * @param creditsCharged what those cost them in total
 */
public record ActorConsumption(String actorId, long events, long creditsCharged) {

  /** Compact canonical constructor enforcing the row's invariants (ERR-106). */
  public ActorConsumption {
    if (events < 0 || creditsCharged < 0) {
      throw new IllegalArgumentException("consumption totals must be >= 0");
    }
  }
}
