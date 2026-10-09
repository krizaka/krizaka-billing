package com.krizaka.billing.service.infrastructure.adapter.rest;

import com.krizaka.billing.service.application.service.PackSubscriptionService;
import com.krizaka.billing.service.application.service.PackSubscriptionService.PackSubscriptionView;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pack-subscriptions resource — who holds which pack.
 *
 * <p>A genuine sub-resource of the pack rather than a second packs controller (ERR-128): the
 * catalogue answers "what is for sale", this answers "what does this actor own", and they have
 * different lifecycles and different access rules.
 *
 * <p>The self-service paths resolve the actor from the token's subject, never from a path variable.
 * A user subscribing "themselves" by id could subscribe anybody, and the {@code /{actorId}} read
 * below is admin-only for exactly the mirror-image reason.
 */
@RestController
@RequestMapping("/api/v1/billing/pack-subscriptions")
class PackSubscriptionController {

  private final PackSubscriptionService packSubscriptionService;

  PackSubscriptionController(PackSubscriptionService packSubscriptionService) {
    this.packSubscriptionService =
        Objects.requireNonNull(packSubscriptionService, "PackSubscriptionService cannot be null");
  }

  /**
   * The packs the signed-in user holds.
   *
   * <p>Declared before {@code /{actorId}} so the literal wins the match.
   *
   * @param user the authenticated actor
   * @return their live pack subscriptions
   */
  @GetMapping("/me")
  List<PackSubscriptionView> mine(@AuthenticationPrincipal Jwt user) {
    return packSubscriptionService.forActor(user.getSubject());
  }

  /**
   * Adds a pack to the signed-in user.
   *
   * @param packKey the pack to add
   * @param user the authenticated actor
   * @return {@code 201} with the subscription now in force
   */
  @PostMapping("/me/{packKey}")
  ResponseEntity<PackSubscriptionView> subscribe(
      @PathVariable String packKey, @AuthenticationPrincipal Jwt user) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(packSubscriptionService.subscribe(user.getSubject(), packKey));
  }

  /**
   * Removes a pack from the signed-in user.
   *
   * @param packKey the pack to remove
   * @param user the authenticated actor
   * @return {@code 204} when removed, {@code 404} when they did not hold it
   */
  @DeleteMapping("/me/{packKey}")
  ResponseEntity<Void> unsubscribe(
      @PathVariable String packKey, @AuthenticationPrincipal Jwt user) {
    return packSubscriptionService.cancel(user.getSubject(), packKey).isPresent()
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  /**
   * Every live holder of a pack — what an admin checks before withdrawing one.
   *
   * @param packKey the pack
   * @return its live subscribers
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  List<PackSubscriptionView> holdersOf(@RequestParam String packKey) {
    return packSubscriptionService.subscribersOf(packKey);
  }

  /**
   * The packs one actor holds.
   *
   * @param actorId the opaque billable subject
   * @return their live pack subscriptions
   */
  @GetMapping("/{actorId}")
  @PreAuthorize("hasRole('ADMIN')")
  List<PackSubscriptionView> forActor(@PathVariable String actorId) {
    return packSubscriptionService.forActor(actorId);
  }

  /**
   * Turns an unknown pack into a {@code 404} rather than a 500.
   *
   * @param e the miss
   * @return the structured body
   */
  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String, String>> handleUnknownPack(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
  }

  /**
   * Turns a withdrawn pack into a {@code 409} — the offer existed, it is no longer sold.
   *
   * @param e the refusal
   * @return the structured body
   */
  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Map<String, String>> handleWithdrawnPack(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
  }
}
