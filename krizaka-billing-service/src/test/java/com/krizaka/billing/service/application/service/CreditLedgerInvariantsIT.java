package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.domain.exception.InsufficientCreditsException;
import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import com.krizaka.billing.domain.model.ConsumptionReport;
import com.krizaka.billing.domain.model.CreditHoldCommand;
import com.krizaka.billing.domain.model.CreditHoldResponse;
import com.krizaka.billing.domain.model.MeteredStep;
import com.krizaka.billing.domain.model.SettleCreditCommand;
import com.krizaka.billing.service.domain.model.WalletSnapshot;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The four financial invariants of the billing design (§15), exercised against the <b>real</b>
 * {@code infra/initdb/70-billing.sql} — the container runs the same bootstrap file production does,
 * so a schema change that breaks an invariant fails here rather than in a wallet.
 *
 * <ul>
 *   <li>a balance never goes negative, even under concurrent holds
 *   <li>a settlement is applied at most once, however often it is redelivered
 *   <li>a hold cannot leak — the sweeper returns expired reservations
 *   <li>the ledger is append-only, enforced by the database itself
 * </ul>
 *
 * <p>Services are wired into a small transactional context rather than a full
 * {@code @SpringBootTest} so the {@code @Transactional} proxies are real (the composition matters)
 * without booting the web, security and AMQP layers, none of which participate in these invariants.
 */
class CreditLedgerInvariantsIT {

  private static final String BILLING_DB = "orazaka_billing_db";
  private static final String BILLING_ROLE = "orazaka_billing";
  private static final String BILLING_PASSWORD = "orazaka_billing_pass";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("bootstrap")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCopyFileToContainer(
              MountableFile.forHostPath(billingBootstrapScript()),
              "/docker-entrypoint-initdb.d/70-billing.sql");

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbcTemplate;
  private static CreditLedgerService ledgerService;

  private String actorId;

  private static Path billingBootstrapScript() {
    return InitDb.locate(Path.of(System.getProperty("user.dir"))).resolve("70-billing.sql");
  }

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    // The initdb file creates this role WITHOUT a password (ADR-035); `orazaka start`
    // applies the ALTER ROLE at runtime, and a hermetic test has to do the same.
    ServiceRoles.assignPassword(POSTGRES, BILLING_ROLE, BILLING_PASSWORD);
    context = new AnnotationConfigApplicationContext(TestWiring.class);
    jdbcTemplate = context.getBean(JdbcTemplate.class);
    ledgerService = context.getBean(CreditLedgerService.class);
    // These invariants are about refusal and debit, so they are only meaningful under enforcement.
    jdbcTemplate.update(
        "UPDATE billing_runtime_config SET config_value = 'ENFORCING'"
            + " WHERE config_key = 'billing.enforcement.mode'");
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshActor() {
    actorId = UUID.randomUUID().toString();
  }

  private void giveGrantedBalance(long credits) {
    jdbcTemplate.update(
        "INSERT INTO credit_wallet (actor_id, balance_granted) VALUES (?, ?)"
            + " ON CONFLICT (actor_id) DO UPDATE SET balance_granted = EXCLUDED.balance_granted",
        actorId,
        credits);
  }

  private CreditHoldCommand chatHold(long estimate) {
    return new CreditHoldCommand(
        actorId, BillableCapability.CHAT, null, "correlation-" + actorId, null, estimate);
  }

  private WalletSnapshot wallet() {
    return ledgerService
        .wallet(actorId)
        .orElseThrow(() -> new IllegalStateException("wallet missing"));
  }

  @Test
  @DisplayName("[§15] a balance never goes negative, even when holds race for the last credits")
  void balanceNeverGoesNegative_underConcurrentHolds() throws Exception {
    giveGrantedBalance(100);
    int attempts = 10;
    long estimate = 30; // only three of these can be afforded

    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      tasks.add(
          () -> {
            try {
              ledgerService.hold(chatHold(estimate));
              return true;
            } catch (InsufficientCreditsException expected) {
              return false;
            }
          });
    }

    long granted;
    try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
      List<Future<Boolean>> results = pool.invokeAll(tasks);
      granted = results.stream().filter(CreditLedgerInvariantsIT::resolve).count();
    }

    assertEquals(3, granted, "exactly three 30-credit holds fit inside a 100-credit balance");
    WalletSnapshot wallet = wallet();
    assertEquals(90, wallet.held());
    assertTrue(wallet.available() >= 0, "available balance must never go negative");
  }

  @Test
  @DisplayName("[§15] a redelivered settlement debits exactly once")
  void settleIsIdempotent_whenTheSameMessageIsReplayed() {
    giveGrantedBalance(1000);
    CreditHoldResponse hold = ledgerService.hold(chatHold(50));
    String messageId = "amqp-message-" + actorId;
    SettleCreditCommand settle =
        new SettleCreditCommand(
            hold.holdId(), BillableUnit.KILOTOKEN, new BigDecimal("10"), messageId);

    ledgerService.settle(settle);
    long afterFirst = wallet().balanceGranted();

    ledgerService.settle(settle);
    ledgerService.settle(settle);

    assertEquals(afterFirst, wallet().balanceGranted(), "replays must not move the balance again");
    Integer debits =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ? AND entry_type = 'DEBIT'",
            Integer.class,
            actorId);
    assertEquals(1, debits, "exactly one debit entry for one settled hold");
    assertEquals(0, wallet().held(), "settling releases the reservation");
    Integer outboxRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_outbox WHERE aggregate_id = ?"
                + " AND routing_key = 'evt.usage.recorded'",
            Integer.class,
            actorId);
    assertEquals(1, outboxRows, "the debit is announced exactly once, in the same transaction");
  }

  @Test
  @DisplayName("[§15] an expired hold cannot leak — the sweeper returns it")
  void expiredHoldIsSwept_andHeldCreditsAreReturned() {
    giveGrantedBalance(500);
    CreditHoldResponse hold = ledgerService.hold(chatHold(120));
    assertEquals(120, wallet().held());

    // Simulate a worker that died: the hold outlives its TTL and nobody ever reports back.
    jdbcTemplate.update(
        "UPDATE credit_hold SET expires_at = now() - INTERVAL '1 hour' WHERE id = ?::uuid",
        hold.holdId());

    int swept = ledgerService.sweepExpiredHolds();

    assertTrue(swept >= 1, "the expired hold must be swept");
    assertEquals(0, wallet().held(), "held credits return to the wallet");
    assertEquals(500, wallet().balanceGranted(), "an unsettled hold is never billed");
    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM credit_hold WHERE id = ?::uuid", String.class, hold.holdId());
    assertEquals("EXPIRED", status);
  }

  @Test
  @DisplayName("a measured outcome prices against the pinned rate's unit, not the producer's")
  void settleMeasured_derivesTheQuantityFromThePricebooksUnit() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.VIDEO, null, "correlation-" + actorId, "job-1", 0));

    // The executor reports frames and fps. It never names OUTPUT_SECOND — that is the seeded
    // VIDEO rate's unit, resolved here: 48 frames at 12fps = 4s × 90 credits/s = 360 credits.
    boolean settled =
        ledgerService.settleMeasured(
            hold.holdId(),
            new ConsumptionReport(
                new BigDecimal("31.4"), 48, 12, null, null, null, null, null, null, null, null),
            "measured-" + actorId);

    assertTrue(settled, "a report the pricebook's unit can be derived from settles");
    // 48 frames @ 12 fps = 4 output-seconds x 900 = 3600 credits (ADR-047: every rate x10).
    assertEquals(5000 - 3600, wallet().balanceGranted());
    assertEquals(0, wallet().held(), "settling releases the reservation");
    String unit =
        jdbcTemplate.queryForObject(
            "SELECT unit FROM usage_event WHERE hold_id = ?::uuid", String.class, hold.holdId());
    assertEquals("OUTPUT_SECOND", unit);
  }

  @Test
  @DisplayName("a completed job whose report does not price is not settled — the caller releases")
  void settleMeasured_reportsFailure_whenNothingPriceable() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.VIDEO, null, "correlation-" + actorId, "job-2", 0));

    // GPU seconds alone cannot become output seconds: frames and fps are what VIDEO bills on.
    boolean settled =
        ledgerService.settleMeasured(
            hold.holdId(),
            new ConsumptionReport(
                new BigDecimal("31.4"), null, null, null, null, null, null, null, null, null, null),
            "unpriceable-" + actorId);

    assertFalse(settled);
    assertEquals(5000, wallet().balanceGranted(), "an unmeasured job is never billed at a guess");
    Integer debits =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ? AND entry_type = 'DEBIT'",
            Integer.class,
            actorId);
    assertEquals(0, debits);
  }

  @Test
  @DisplayName("[ADR-041] a run settles ONCE at the sum of its steps, each at its own rate")
  void settleAggregate_sumsEachStepAtItsOwnCapabilitysRate() {
    giveGrantedBalance(5000);
    // A run holds against AGENT — an orchestration is not a generation — and then spends across
    // three capabilities whose work is priced in three different units.
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-1", 480));

    boolean settled =
        ledgerService.settleAggregate(
            hold.holdId(),
            List.of(
                // CHAT: 3400 tokens = 3.4 kilotokens × 1.0, ceiling → 4 credits.
                new MeteredStep(
                    BillableCapability.CHAT,
                    null,
                    new ConsumptionReport(
                        null, null, null, null, null, null, null, null, null, 3400L, null)),
                // IMAGE: 1 image, 4 steps, 1024×1024 = 4.194304 megapixel-steps × 1.9073 → 8
                // credits.
                new MeteredStep(
                    BillableCapability.IMAGE,
                    null,
                    new ConsumptionReport(
                        null, null, null, 1, 4, 1024, 1024, null, null, null, null)),
                // VIDEO at the compose rate: 450 frames at 30fps = 15s × 2.0 → 30 credits.
                new MeteredStep(
                    BillableCapability.VIDEO,
                    "orazaka-compose",
                    new ConsumptionReport(
                        null, 450, 30, null, null, null, null, null, null, null, null))),
            "studio-run-" + actorId);

    assertTrue(settled);
    // 34 + 80 + 300 = 414, at v2 rates (ADR-047). The same three steps were 4 + 8 + 30 = 42
    // before the credit became ten times finer, and 5 before ADR-041 — when the first step to
    // finish closed the hold at the AGENT/CALL rate, whose quantity is a hardcoded 1.
    assertEquals(5000 - 414, wallet().balanceGranted());
    assertEquals(0, wallet().held(), "the run's reservation is released by the settlement");

    Integer usageRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM usage_event WHERE hold_id = ?::uuid",
            Integer.class,
            hold.holdId());
    assertEquals(3, usageRows, "one usage row per step: the total must stay reconstructible");
    Integer debits =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ? AND entry_type = 'DEBIT'",
            Integer.class,
            actorId);
    assertEquals(
        1, debits, "one hold, one debit — the steps are a breakdown, not separate charges");
  }

  @Test
  @DisplayName("[ADR-041] an image ANALYSIS is priced in tokens, not in denoising steps")
  void settleAggregate_pricesVisionInTokens() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-5", 480));

    // A VLM turn reports tokens and the model that ran. The (IMAGE, llava) row prices in
    // KILOTOKEN; the (IMAGE, NULL) default would price in IMAGE_STEP and find no quantity at all,
    // because an analysis has no denoising steps to count.
    boolean settled =
        ledgerService.settleAggregate(
            hold.holdId(),
            List.of(
                new MeteredStep(
                    BillableCapability.IMAGE,
                    "llava:latest",
                    new ConsumptionReport(
                        null, null, null, null, null, null, null, null, null, 4200L, null))),
            "vision-" + actorId);

    assertTrue(settled, "a vision step must be billable, not free");
    // 4.2 kilotokens x 10.0, ceiling -> 42 credits (ADR-047).
    assertEquals(5000 - 42, wallet().balanceGranted());
    String unit =
        jdbcTemplate.queryForObject(
            "SELECT unit FROM usage_event WHERE hold_id = ?::uuid", String.class, hold.holdId());
    assertEquals("KILOTOKEN", unit, "analysis bills in tokens; generation still bills IMAGE_STEP");
  }

  @Test
  @DisplayName("[ADR-041] image GENERATION still bills in IMAGE_STEP — the split is per model")
  void settleAggregate_stillPricesGenerationInImageSteps() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-6", 480));

    ledgerService.settleAggregate(
        hold.holdId(),
        List.of(
            new MeteredStep(
                BillableCapability.IMAGE,
                null,
                new ConsumptionReport(null, null, null, 1, 4, 1024, 1024, null, null, null, null))),
        "generation-" + actorId);

    assertEquals(5000 - 80, wallet().balanceGranted(), "4.19 megapixel-steps x 19.073 -> 80");
  }

  @Test
  @DisplayName("[ADR-041] an assembly is not priced as a generation, though both are VIDEO")
  void settleAggregate_pricesComposeApartFromDiffusion() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-2", 480));

    // The same 15 seconds of output, from the engine that assembles rather than invents.
    ledgerService.settleAggregate(
        hold.holdId(),
        List.of(
            new MeteredStep(
                BillableCapability.VIDEO,
                "orazaka-compose",
                new ConsumptionReport(
                    null, 450, 30, null, null, null, null, null, null, null, null))),
        "compose-" + actorId);

    // 15s x 20.0 = 300 credits (ADR-047). At VIDEO's default diffusion rate the same assembly
    // would be 15 x 900 = 13 500 — more than twice the whole run's estimate, for an ffmpeg concat.
    assertEquals(5000 - 300, wallet().balanceGranted());
  }

  @Test
  @DisplayName("[ADR-041] a run that measured nothing is released, never billed at its estimate")
  void settleAggregate_reportsFailure_whenNoStepPriced() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-3", 480));

    boolean settled =
        ledgerService.settleAggregate(
            hold.holdId(),
            List.of(
                new MeteredStep(
                    BillableCapability.VIDEO,
                    null,
                    // GPU seconds alone cannot become output seconds.
                    new ConsumptionReport(
                        new BigDecimal("12.5"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))),
            "unmeasured-" + actorId);

    assertFalse(settled, "the caller must release rather than debit a guess");
    assertEquals(5000, wallet().balanceGranted());
  }

  @Test
  @DisplayName("[ADR-041] a replayed run settlement debits exactly once")
  void settleAggregate_isIdempotent() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.AGENT, null, "correlation-" + actorId, "run-4", 480));
    List<MeteredStep> steps =
        List.of(
            new MeteredStep(
                BillableCapability.CHAT,
                null,
                new ConsumptionReport(
                    null, null, null, null, null, null, null, null, null, 3400L, null)));

    ledgerService.settleAggregate(hold.holdId(), steps, "replay-" + actorId);
    ledgerService.settleAggregate(hold.holdId(), steps, "replay-" + actorId);

    assertEquals(
        5000 - 34, wallet().balanceGranted(), "a redelivered terminal transition bills once");
  }

  @Test
  @DisplayName("a chat turn settles on the tokens the model produced, priced as KILOTOKEN")
  void settleMeasured_pricesAChatTurnFromItsTokens() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold = ledgerService.hold(chatHold(0));

    // The listener reports tokens and nothing else; CHAT's seeded rate is KILOTOKEN, so 3400
    // tokens is 3.4 kilotokens at 1.0 credits each, rounded up — the ceiling is deliberate.
    boolean settled =
        ledgerService.settleMeasured(
            hold.holdId(),
            new ConsumptionReport(
                null, null, null, null, null, null, null, null, null, 3400L, null),
            "chat:" + hold.holdId());

    assertTrue(settled);
    assertEquals(5000 - 34, wallet().balanceGranted(), "3.4 kilotokens rounds up to 34 credits");
    String unit =
        jdbcTemplate.queryForObject(
            "SELECT unit FROM usage_event WHERE hold_id = ?::uuid", String.class, hold.holdId());
    assertEquals("KILOTOKEN", unit);
  }

  @Test
  @DisplayName("a settlement in the wrong unit is refused, never silently mispriced")
  void settleRejectsAUnitThePinnedRateDoesNotUse() {
    giveGrantedBalance(5000);
    CreditHoldResponse hold =
        ledgerService.hold(
            new CreditHoldCommand(
                actorId, BillableCapability.VIDEO, null, "correlation-" + actorId, "job-3", 0));

    // 4 KILOTOKEN multiplied by a credits-per-OUTPUT_SECOND rate is a plausible number that is
    // simply wrong, and a wrong debit cannot be taken back from a user's trust.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ledgerService.settle(
                new SettleCreditCommand(
                    hold.holdId(),
                    BillableUnit.KILOTOKEN,
                    new BigDecimal("4"),
                    "mismatched-" + actorId)));
    assertEquals(5000, wallet().balanceGranted());
  }

  @Test
  @DisplayName("[§15] the ledger is append-only — the database refuses UPDATE and DELETE")
  void ledgerIsAppendOnly() {
    giveGrantedBalance(1000);
    CreditHoldResponse hold = ledgerService.hold(chatHold(40));
    ledgerService.settle(
        new SettleCreditCommand(
            hold.holdId(), BillableUnit.KILOTOKEN, new BigDecimal("5"), "settle-" + actorId));

    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                "UPDATE credit_ledger_entry SET amount = 999 WHERE actor_id = ?", actorId),
        "a financial record must not be rewritable");
    assertThrows(
        DataAccessException.class,
        () -> jdbcTemplate.update("DELETE FROM credit_ledger_entry WHERE actor_id = ?", actorId),
        "a financial record must not be deletable");
  }

  private static boolean resolve(Future<Boolean> future) {
    try {
      return Boolean.TRUE.equals(future.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("a refusal is recorded even though the 402 rolls its own transaction back")
  void refusalSurvivesTheRollback() {
    giveGrantedBalance(10);

    assertThrows(InsufficientCreditsException.class, () -> ledgerService.hold(chatHold(500)));

    Long refusals =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_refusal WHERE actor_id = ? AND enforced",
            Long.class,
            actorId);
    assertEquals(1, refusals, "the evidence of a refusal must outlive the refusal");
    Long available =
        jdbcTemplate.queryForObject(
            "SELECT available FROM credit_refusal WHERE actor_id = ?", Long.class, actorId);
    assertEquals(10, available);
  }

  /** Minimal transactional wiring: real {@code @Transactional} proxies, no web/security/AMQP. */
  @Configuration
  // Class proxies, as Spring Boot creates them in production: a service that implements a port
  // (OutboxService is an OutboxStore) must still be injectable by its class.
  @EnableTransactionManagement(proxyTargetClass = true)
  static class TestWiring {

    @Bean
    DataSource dataSource() {
      HikariDataSource dataSource = new HikariDataSource();
      dataSource.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + BILLING_DB);
      dataSource.setUsername(BILLING_ROLE);
      dataSource.setPassword(BILLING_PASSWORD);
      dataSource.setDriverClassName("org.postgresql.Driver");
      dataSource.setMaximumPoolSize(20);
      return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    BillingVersionHistoryService billingVersionHistoryService(JdbcTemplate jdbcTemplate) {
      return new BillingVersionHistoryService(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    BillingConfigurationService billingConfigurationService(
        JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
      return new BillingConfigurationService(jdbcTemplate, versionHistoryService);
    }

    @Bean
    PricingService pricingService(JdbcTemplate jdbcTemplate) {
      return new PricingService(jdbcTemplate);
    }

    @Bean
    OutboxService outboxService(JdbcTemplate jdbcTemplate) {
      return new OutboxService(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    CreditRefusalService creditRefusalService(JdbcTemplate jdbcTemplate) {
      return new CreditRefusalService(jdbcTemplate);
    }

    @Bean
    CreditLedgerService creditLedgerService(
        JdbcTemplate jdbcTemplate,
        PricingService pricingService,
        BillingConfigurationService configurationService,
        OutboxService outboxService,
        CreditRefusalService creditRefusalService) {
      return new CreditLedgerService(
          jdbcTemplate,
          pricingService,
          configurationService,
          outboxService,
          creditRefusalService,
          new SimpleMeterRegistry());
    }
  }
}
