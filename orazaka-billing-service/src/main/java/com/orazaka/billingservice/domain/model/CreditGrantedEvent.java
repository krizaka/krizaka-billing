package com.orazaka.billingservice.domain.model;

import java.time.Instant;

/**
 * Announces a balance change made outside the hold/settle protocol ({@code evt.credit.granted}).
 *
 * <p>Emitted for claw-backs as well as goodwill, and for the credits bundled with a pack: the
 * amount is signed and the bucket is explicit, so one event type covers every case and the client
 * UI updates from the same subscription either way. A separate "revoked" or "purchased" event would
 * be a second thing to remember to consume.
 *
 * @param actorId the opaque billable subject whose balance moved
 * @param bucket which balance moved — granted credits expire, purchased ones are the user's
 *     property
 * @param amount signed credits: positive is a grant, negative a claw-back
 * @param balanceAfter that bucket's balance once applied, the audit anchor
 * @param reason why, free-text and mandatory
 * @param createdBy the admin who did it, or {@code system} when a purchase applied it
 * @param occurredAt when it was applied
 */
public record CreditGrantedEvent(
    String actorId,
    String bucket,
    long amount,
    long balanceAfter,
    String reason,
    String createdBy,
    Instant occurredAt) {}
