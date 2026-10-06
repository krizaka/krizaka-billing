package com.orazaka.billingservice.domain.model;

/**
 * What a consumer should do when billing is unreachable — configured per capability class in {@code
 * billing_runtime_config} and returned to the caller, never inferred by it.
 *
 * <p>It is a runtime row rather than a constant precisely because it is the lever pulled during an
 * incident: chat degrades gracefully, high-cost media does not.
 */
public enum FailMode {

  /** Allow the request and record a reconciliation debt — protects UX. Ship this for chat. */
  FAIL_OPEN,

  /** Refuse the request — protects margin. Ship this for media. */
  FAIL_CLOSED
}
