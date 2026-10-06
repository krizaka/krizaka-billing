package com.orazaka.billing.domain.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billing.domain.model.BillableCapability;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsufficientCreditsExceptionTest {

  private static final String ACTOR = "550e8400-e29b-41d4-a716-446655440002";

  @Test
  void carriesEverythingTheStructured402Needs() {
    InsufficientCreditsException exception =
        new InsufficientCreditsException(ACTOR, BillableCapability.VIDEO, 360, 12);

    assertEquals(ACTOR, exception.actorId());
    assertEquals(BillableCapability.VIDEO, exception.capability());
    assertEquals(360, exception.required());
    assertEquals(12, exception.available());
  }

  @Test
  void messageStatesTheRefusalWithoutTheCallerReDerivingIt() {
    InsufficientCreditsException exception =
        new InsufficientCreditsException(ACTOR, BillableCapability.VIDEO, 360, 12);

    assertTrue(exception.getMessage().contains("VIDEO"));
    assertTrue(exception.getMessage().contains("360"));
    assertTrue(exception.getMessage().contains("12"));
  }

  @Test
  void remediesAreEmpty_whenTheThrowSiteCouldNotResolveThem() {
    InsufficientCreditsException exception =
        new InsufficientCreditsException(ACTOR, BillableCapability.VIDEO, 360, 12);

    assertTrue(exception.remedies().isEmpty());
  }

  @Test
  void remediesTravelWithTheRefusalAcrossAServiceBoundary() {
    InsufficientCreditsException exception =
        new InsufficientCreditsException(
            ACTOR, BillableCapability.VIDEO, 360, 12, List.of("upgrade_plan", "top_up_credits"));

    assertEquals(List.of("upgrade_plan", "top_up_credits"), exception.remedies());
  }

  @Test
  void remediesAreDefensivelyCopied() {
    List<String> source = new ArrayList<>(List.of("upgrade_plan"));
    InsufficientCreditsException exception =
        new InsufficientCreditsException(ACTOR, BillableCapability.VIDEO, 360, 12, source);

    source.add("top_up_credits");

    assertEquals(List.of("upgrade_plan"), exception.remedies());
  }
}
