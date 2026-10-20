package com.krizaka.billing.service.infrastructure.adapter.schedule;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.krizaka.billing.service.application.service.CreditLedgerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HoldSweeperTest {

  private static final int BATCH = CreditLedgerService.DEFAULT_SWEEP_BATCH;

  @Test
  @DisplayName("a backlog is released batch by batch until a batch comes back short")
  void sweepsUntilABatchIsShort() {
    CreditLedgerService ledger = mock(CreditLedgerService.class);
    when(ledger.sweepExpiredHolds(anyInt())).thenReturn(BATCH, BATCH, 7);

    new HoldSweeper(ledger).sweep();

    verify(ledger, times(3)).sweepExpiredHolds(BATCH);
  }

  @Test
  @DisplayName("an empty table costs one cheap call")
  void oneCallWhenNothingExpired() {
    CreditLedgerService ledger = mock(CreditLedgerService.class);
    when(ledger.sweepExpiredHolds(anyInt())).thenReturn(0);

    new HoldSweeper(ledger).sweep();

    verify(ledger, times(1)).sweepExpiredHolds(BATCH);
  }

  @Test
  @DisplayName("a tick never loops forever on a huge backlog")
  void tickIsBounded() {
    CreditLedgerService ledger = mock(CreditLedgerService.class);
    when(ledger.sweepExpiredHolds(anyInt())).thenReturn(BATCH);

    new HoldSweeper(ledger).sweep();

    verify(ledger, times(HoldSweeper.MAX_BATCHES_PER_TICK)).sweepExpiredHolds(BATCH);
  }
}
