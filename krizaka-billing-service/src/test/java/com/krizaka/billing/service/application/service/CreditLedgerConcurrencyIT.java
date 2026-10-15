package com.krizaka.billing.service.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import com.krizaka.billing.domain.model.SettleCreditCommand;
import com.krizaka.billing.service.domain.model.PricebookRate;
import com.krizaka.test.container.AbstractContainerIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency guard of the hold protocol (krizaka-billing#16): release, settle and the TTL sweeper
 * racing on one hold must close it exactly once and decrement the wallet's {@code held} exactly
 * once. Real PostgreSQL (READ COMMITTED): a mock would not reproduce the race.
 */
class CreditLedgerConcurrencyIT extends AbstractContainerIntegrationTest {

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static CreditLedgerService ledger;

  @BeforeAll
  static void schema() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    for (String ddl :
        List.of(
            "CREATE TABLE IF NOT EXISTS credit_wallet (actor_id VARCHAR(255) PRIMARY KEY,"
                + " balance_granted BIGINT NOT NULL DEFAULT 0, balance_purchased BIGINT NOT NULL"
                + " DEFAULT 0, held BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,"
                + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), CONSTRAINT"
                + " ck_wallet_non_negative CHECK (balance_granted >= 0 AND balance_purchased >= 0"
                + " AND held >= 0))",
            "CREATE TABLE IF NOT EXISTS credit_hold (id UUID PRIMARY KEY DEFAULT"
                + " gen_random_uuid(), actor_id VARCHAR(255) NOT NULL, estimated_credits BIGINT"
                + " NOT NULL, settled_credits BIGINT, status VARCHAR(20) NOT NULL, capability"
                + " VARCHAR(50) NOT NULL, model_name VARCHAR(255), pricebook_version INT NOT NULL,"
                + " correlation_id VARCHAR(255) NOT NULL, job_id VARCHAR(36), created_at"
                + " TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ NOT NULL,"
                + " settled_at TIMESTAMPTZ)",
            "CREATE TABLE IF NOT EXISTS credit_ledger_entry (id UUID PRIMARY KEY DEFAULT"
                + " gen_random_uuid(), actor_id VARCHAR(255) NOT NULL, entry_type VARCHAR(20) NOT"
                + " NULL, bucket VARCHAR(20) NOT NULL, amount BIGINT NOT NULL, balance_after"
                + " BIGINT NOT NULL, reference_type VARCHAR(50), reference_id VARCHAR(255),"
                + " idempotency_key VARCHAR(255) NOT NULL UNIQUE, reason VARCHAR(255), created_by"
                + " VARCHAR(255) NOT NULL, unit_version INT NOT NULL DEFAULT 2, created_at"
                + " TIMESTAMPTZ NOT NULL DEFAULT now())",
            "CREATE TABLE IF NOT EXISTS usage_event (id UUID PRIMARY KEY DEFAULT"
                + " gen_random_uuid(), actor_id VARCHAR(255) NOT NULL, capability VARCHAR(50) NOT"
                + " NULL, model_name VARCHAR(255), unit VARCHAR(30) NOT NULL, quantity"
                + " NUMERIC(14,4) NOT NULL, credits_charged BIGINT NOT NULL, hold_id UUID, job_id"
                + " VARCHAR(36), correlation_id VARCHAR(255) NOT NULL, occurred_at TIMESTAMPTZ NOT"
                + " NULL, gpu_seconds NUMERIC(12,3), external_synced_at TIMESTAMPTZ)",
            "CREATE TABLE IF NOT EXISTS billing_plan (plan_key VARCHAR(50) PRIMARY KEY,"
                + " monthly_credit_grant BIGINT NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS billing_subscription (actor_id VARCHAR(255),"
                + " plan_key VARCHAR(50), status VARCHAR(20))")) {
      jdbc.execute(ddl);
    }

    PricingService pricing = mock(PricingService.class);
    when(pricing.pinnedRate(anyInt(), any(), any()))
        .thenReturn(
            new PricebookRate(
                1, BillableCapability.CHAT, null, BillableUnit.KILOTOKEN, BigDecimal.TEN, 1, 50));
    BillingConfigurationService config = mock(BillingConfigurationService.class);
    when(config.overshootMaxCredits()).thenReturn(1_000);
    when(config.lowBalancePercent()).thenReturn(10);
    ledger =
        new CreditLedgerService(
            jdbc,
            pricing,
            config,
            mock(OutboxService.class),
            mock(CreditRefusalService.class),
            new SimpleMeterRegistry());
  }

  private static String wallet(long balance, long held) {
    String actor = "actor-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO credit_wallet (actor_id, balance_granted, held) VALUES (?, ?, ?)",
        actor,
        balance,
        held);
    return actor;
  }

  private static UUID hold(String actor, long estimate, boolean expired) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO credit_hold (id, actor_id, estimated_credits, status, capability,"
            + " pricebook_version, correlation_id, expires_at)"
            + " VALUES (?, ?, ?, 'ACTIVE', 'CHAT', 1, 'c', now() + make_interval(secs => ?))",
        id,
        actor,
        estimate,
        expired ? -60.0 : 3600.0);
    return id;
  }

  private static long held(String actor) {
    return jdbc.queryForObject(
        "SELECT held FROM credit_wallet WHERE actor_id = ?", Long.class, actor);
  }

  /** Runs every task at the same instant, each in its own transaction. */
  private static void race(List<Consumer<Void>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (Consumer<Void> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  tx.executeWithoutResult(s -> task.accept(null));
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentReleasesOfOneHoldDecrementHeldOnce() throws Exception {
    for (int round = 0; round < 50; round++) {
      String actor = wallet(1_000, 100);
      UUID h1 = hold(actor, 50, false);
      hold(actor, 50, false);
      race(
          List.of(
              v -> ledger.release(h1.toString(), "a"), v -> ledger.release(h1.toString(), "b")));
      assertThat(held(actor)).as("round %d", round).isEqualTo(50);
    }
  }

  @Test
  void settleReleaseAndSweeperRacingOnOneHoldCloseItOnce() throws Exception {
    for (int round = 0; round < 50; round++) {
      String actor = wallet(1_000, 100);
      UUID h1 = hold(actor, 50, true);
      hold(actor, 50, false);
      SettleCreditCommand settle =
          new SettleCreditCommand(
              h1.toString(), BillableUnit.KILOTOKEN, BigDecimal.ONE, "key-" + UUID.randomUUID());
      race(
          List.of(
              v -> ledger.settle(settle),
              v -> ledger.release(h1.toString(), "failed"),
              v -> ledger.sweepExpiredHolds()));

      assertThat(held(actor)).as("held, round %d", round).isEqualTo(50);
      String status =
          jdbc.queryForObject("SELECT status FROM credit_hold WHERE id = ?", String.class, h1);
      assertThat(status).isIn("SETTLED", "RELEASED", "EXPIRED");
      Integer debits =
          jdbc.queryForObject(
              "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ?", Integer.class, actor);
      // A released / expired hold is never billed; a settled one is billed once.
      assertThat(debits).isEqualTo("SETTLED".equals(status) ? 1 : 0);
    }
  }
}
