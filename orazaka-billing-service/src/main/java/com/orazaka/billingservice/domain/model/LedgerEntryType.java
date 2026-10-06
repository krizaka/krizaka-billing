package com.orazaka.billingservice.domain.model;

/**
 * What caused a credit movement. Every row in the append-only ledger carries one, so a balance can
 * always be explained rather than merely observed.
 */
public enum LedgerEntryType {

  /** Credits granted by a subscription period. */
  GRANT,

  /** Credits bought à la carte. */
  PURCHASE,

  /** Credits consumed by measured usage — the settle half of the protocol. */
  DEBIT,

  /** Credits returned after a debit was reversed. */
  REFUND,

  /** Granted credits withdrawn at period rollover. */
  EXPIRY,

  /** A manual admin movement — the only write that creates credits without a hold. */
  ADJUSTMENT
}
