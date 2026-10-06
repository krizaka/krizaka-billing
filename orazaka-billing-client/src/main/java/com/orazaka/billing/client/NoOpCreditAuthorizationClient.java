package com.orazaka.billing.client;

import com.orazaka.billing.domain.model.ConsumptionReport;
import com.orazaka.billing.domain.model.CreditHoldCommand;
import com.orazaka.billing.domain.model.CreditHoldResponse;
import com.orazaka.billing.domain.model.MeteredStep;
import com.orazaka.billing.domain.model.SettleCreditCommand;
import com.orazaka.billing.domain.port.CreditAuthorizationClient;
import java.util.List;

/**
 * Null Object: always grants, records nothing.
 *
 * <p>This is what lets {@code orazaka.billing.enabled=false} be a real answer instead of a branch.
 * Without it every producer would carry {@code if (billingEnabled)} around each hold, spreading a
 * monetisation concern through the conversation, job and automation services and giving four places
 * for the check to drift out of step (ERR-127).
 *
 * <p>A contributor cloning the repo runs the whole stack against this adapter and never knows the
 * billing service exists.
 */
final class NoOpCreditAuthorizationClient implements CreditAuthorizationClient {

  @Override
  public CreditHoldResponse hold(CreditHoldCommand command) {
    // Not a real reservation: producers see metered() == false and propagate no hold id.
    return CreditHoldResponse.notMetered();
  }

  @Override
  public void settle(SettleCreditCommand command) {
    // Nothing is metered when billing is not wired.
  }

  @Override
  public void settleMeasured(String holdId, ConsumptionReport report, String idempotencyKey) {
    // Nothing is metered when billing is not wired.
  }

  @Override
  public boolean settleAggregate(String holdId, List<MeteredStep> steps, String idempotencyKey) {
    // False, not true: with billing unwired nothing was debited, and telling the caller otherwise
    // would have it log a settlement that never happened.
    return false;
  }

  @Override
  public void release(String holdId, String reason) {
    // Nothing was ever reserved.
  }
}
