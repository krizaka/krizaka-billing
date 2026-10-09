package com.krizaka.billing.domain.port;

import com.krizaka.billing.domain.model.UnmeteredTurn;

/**
 * Durably records work served without a hold, so it can be reconciled (ADR-064).
 *
 * <p>Implemented by the host that serves the work, never by the billing client: the moment this is
 * called is the moment billing is unreachable. The host writes it where its own transactions live —
 * its outbox — and billing collects it when it is back.
 *
 * <p><b>Contract.</b> Returns only once the record is durable. When it cannot be, it throws, and
 * the caller must not serve the work: the fail-open posture is a posture for reconcilable free
 * inference, and silent free inference was never chosen.
 */
public interface UnmeteredTurnRepository {

  /**
   * Records one turn served without a hold.
   *
   * @param turn what was served, to whom, and why no hold was taken
   * @throws RuntimeException when the record could not be made durable
   */
  void record(UnmeteredTurn turn);
}
