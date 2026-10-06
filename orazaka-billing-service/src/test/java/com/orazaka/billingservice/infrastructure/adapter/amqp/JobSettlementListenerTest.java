package com.orazaka.billingservice.infrastructure.adapter.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orazaka.billing.domain.model.ConsumptionReport;
import com.orazaka.billingservice.application.service.CreditLedgerService;
import com.orazaka.billingservice.application.service.MessageDedupService;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class JobSettlementListenerTest {

  private static final String HOLD = "9f1c0a10-0000-4000-8000-000000000009";
  private static final String MESSAGE_ID = "amqp-1";

  @Mock private CreditLedgerService creditLedgerService;
  @Mock private MessageDedupService messageDedupService;

  private JobSettlementListener listener;
  private AutoCloseable mocks;

  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
    listener = new JobSettlementListener(creditLedgerService, messageDedupService);
  }

  @AfterEach
  void tearDown() throws Exception {
    mocks.close();
  }

  private static final ConsumptionReport RENDERED_48_FRAMES =
      new ConsumptionReport(
          new BigDecimal("31.4"), 48, 12, null, null, null, null, null, null, null, null);

  private JobOutcomeEvent completed(ConsumptionReport consumption) {
    return new JobOutcomeEvent("job-1", HOLD, consumption, null);
  }

  @Test
  @org.junit.jupiter.api.DisplayName(
      "a settlement that fails gives its claim back — work done and never billed is the worst end")
  void releasesTheClaimWhenSettlementFails() {
    // audit #30, the defect standing in the settlement path: claimed atomically, never released.
    // The redelivery finds the claim its own failed attempt left, skips, and the settlement is
    // lost forever.
    when(messageDedupService.claim("billing-settlement", MESSAGE_ID)).thenReturn(true);
    when(creditLedgerService.settleMeasured(
            org.mockito.ArgumentMatchers.eq(HOLD),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(MESSAGE_ID)))
        .thenThrow(new IllegalStateException("the ledger was unreachable"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> listener.onJobOutcome(completed(RENDERED_48_FRAMES), MESSAGE_ID))
        .isInstanceOf(IllegalStateException.class);

    verify(messageDedupService).release("billing-settlement", MESSAGE_ID);
  }

  @Test
  @DisplayName("a completed job settles its hold from what the executor measured")
  void settlesOnCompletion() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(true);
    when(creditLedgerService.settleMeasured(anyString(), any(), anyString())).thenReturn(true);

    listener.onJobOutcome(completed(RENDERED_48_FRAMES), MESSAGE_ID);

    ArgumentCaptor<ConsumptionReport> captor = ArgumentCaptor.forClass(ConsumptionReport.class);
    verify(creditLedgerService).settleMeasured(eq(HOLD), captor.capture(), eq(MESSAGE_ID));
    assertEquals(48, captor.getValue().frames());
    verify(creditLedgerService, never()).release(anyString(), anyString());
    verify(messageDedupService).claim(anyString(), eq(MESSAGE_ID));
  }

  @Test
  @DisplayName("the message id becomes the ledger's idempotency key, so both guards agree")
  void carriesTheMessageIdIntoTheLedgerKey() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(true);
    when(creditLedgerService.settleMeasured(anyString(), any(), anyString())).thenReturn(true);

    listener.onJobOutcome(completed(RENDERED_48_FRAMES), MESSAGE_ID);

    verify(creditLedgerService).settleMeasured(eq(HOLD), any(), eq(MESSAGE_ID));
  }

  @Test
  @DisplayName("a failed job is released, never billed")
  void releasesOnFailure() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(true);

    listener.onJobOutcome(
        new JobOutcomeEvent("job-2", HOLD, null, "MLX out of memory"), MESSAGE_ID);

    verify(creditLedgerService).release(eq(HOLD), anyString());
    verify(creditLedgerService, never()).settleMeasured(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("a completed job with no metrics is released rather than billed at its estimate")
  void releasesWhenNoMetricsWereReported() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(true);

    listener.onJobOutcome(new JobOutcomeEvent("job-3", HOLD, null, null), MESSAGE_ID);

    verify(creditLedgerService).release(eq(HOLD), anyString());
    verify(creditLedgerService, never()).settleMeasured(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("metrics the pricebook's unit cannot be derived from release rather than bill")
  void releasesWhenTheReportDoesNotPrice() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(true);
    when(creditLedgerService.settleMeasured(anyString(), any(), anyString())).thenReturn(false);

    listener.onJobOutcome(completed(RENDERED_48_FRAMES), MESSAGE_ID);

    verify(creditLedgerService).release(eq(HOLD), anyString());
  }

  @Test
  @DisplayName("a redelivered outcome never touches the ledger a second time")
  void skipsRedeliveries() {
    when(messageDedupService.claim(anyString(), eq(MESSAGE_ID))).thenReturn(false);

    listener.onJobOutcome(completed(RENDERED_48_FRAMES), MESSAGE_ID);

    verifyNoInteractions(creditLedgerService);
  }

  @Test
  @DisplayName("a job submitted without a hold is not billable and is ignored")
  void ignoresJobsWithoutAHold() {
    listener.onJobOutcome(new JobOutcomeEvent("job-4", null, RENDERED_48_FRAMES, null), MESSAGE_ID);

    verifyNoInteractions(creditLedgerService);
    verifyNoInteractions(messageDedupService);
  }
}
