package com.krizaka.billing.service.infrastructure.adapter.schedule;

import com.krizaka.billing.service.application.service.CreditLedgerService;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-triggered inbound adapter: releases {@code ACTIVE} holds that outlived their TTL.
 *
 * <p>A worker that dies mid-job never reports back, so its hold would otherwise keep the reserved
 * credits frozen forever and the user's balance would shrink with every crash. The sweeper is the
 * only thing that makes a hold a *reservation* rather than a leak, which is why it ships with the
 * protocol rather than after it.
 *
 * <p>The poll interval is bootstrap wiring (yaml); the TTL it enforces is a runtime row an admin
 * can change live.
 */
@Component
class HoldSweeper {

  private final CreditLedgerService creditLedgerService;

  HoldSweeper(CreditLedgerService creditLedgerService) {
    this.creditLedgerService =
        Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
  }

  // @fixed: a tick that finds a huge backlog stops after this many batches; the next tick continues
  static final int MAX_BATCHES_PER_TICK = 50;

  /** Releases the expired holds batch by batch (one short transaction each) until none are left. */
  @Scheduled(fixedDelayString = "${krizaka.billing-service.sweeper.interval:60000}")
  void sweep() {
    int batch = CreditLedgerService.DEFAULT_SWEEP_BATCH;
    for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
      if (creditLedgerService.sweepExpiredHolds(batch) < batch) {
        return;
      }
    }
  }
}
