package com.orazaka.billingservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billingservice.domain.exception.AdjustmentCeilingExceededException;
import com.orazaka.billingservice.domain.model.CreditBucket;
import com.orazaka.billingservice.domain.model.WalletSnapshot;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * The guardrails of design §12.1, against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>This is the one path that moves credits without a hold, so it is the one an attacker with an
 * admin session would reach for. Each test below is a guardrail that has to hold for that to be
 * survivable — and the two that matter most (a claw-back cannot go negative, the ledger cannot be
 * rewritten) are enforced by the database, which is why a mocked test would prove nothing.
 */
class CreditAdjustmentIT {

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
  private static CreditAdjustmentService adjustmentService;

  private String actorId;
  private String adminId;

  private static Path billingBootstrapScript() {
    return SqlBoundaryRules.locateInitDb(Path.of(System.getProperty("user.dir")))
        .resolve("70-billing.sql");
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
    adjustmentService = context.getBean(CreditAdjustmentService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshIdentities() {
    // A fresh admin per test, so each starts against an untouched daily ceiling.
    actorId = UUID.randomUUID().toString();
    adminId = UUID.randomUUID().toString();
  }

  private void giveBalance(long granted, long purchased) {
    jdbcTemplate.update(
        "INSERT INTO credit_wallet (actor_id, balance_granted, balance_purchased) VALUES (?, ?, ?)"
            + " ON CONFLICT (actor_id) DO UPDATE SET balance_granted = EXCLUDED.balance_granted,"
            + " balance_purchased = EXCLUDED.balance_purchased",
        actorId,
        granted,
        purchased);
  }

  @Test
  @DisplayName("a goodwill grant moves the chosen bucket and is attributed to the admin")
  void grantsIntoTheChosenBucket() {
    giveBalance(100, 0);

    WalletSnapshot after =
        adjustmentService.adjust(
            actorId, CreditBucket.PURCHASED, 500, "goodwill: failed render", adminId);

    assertEquals(100, after.balanceGranted(), "the other bucket is untouched");
    assertEquals(500, after.balancePurchased());

    List<String> createdBy =
        jdbcTemplate.queryForList(
            "SELECT created_by FROM credit_ledger_entry WHERE actor_id = ?"
                + " AND entry_type = 'ADJUSTMENT'",
            String.class,
            actorId);
    assertEquals(List.of(adminId), createdBy, "never 'system' for a manual adjustment");
  }

  @Test
  @DisplayName("a claw-back is the same operation, signed — one code path, one audit trail")
  void clawsBackWithTheSameCall() {
    giveBalance(0, 800);

    WalletSnapshot after =
        adjustmentService.adjust(
            actorId, CreditBucket.PURCHASED, -300, "duplicate top-up reversed", adminId);

    assertEquals(500, after.balancePurchased());
    Long entries =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ? AND amount = -300",
            Long.class,
            actorId);
    assertEquals(1, entries);
  }

  @Test
  @DisplayName("the reason is stored, because an unexplained balance change reads as fraud later")
  void storesTheReason() {
    giveBalance(0, 0);

    adjustmentService.adjust(actorId, CreditBucket.GRANTED, 50, "incident 2026-07-30", adminId);

    String reason =
        jdbcTemplate.queryForObject(
            "SELECT reason FROM credit_ledger_entry WHERE actor_id = ?", String.class, actorId);
    assertEquals("incident 2026-07-30", reason);
  }

  @Test
  @DisplayName("a claw-back cannot take a balance negative — the database refuses it")
  void refusesAnOverdraftingClawBack() {
    giveBalance(0, 100);

    assertThrows(
        IllegalArgumentException.class,
        () -> adjustmentService.adjust(actorId, CreditBucket.PURCHASED, -500, "abuse", adminId));

    Long balance =
        jdbcTemplate.queryForObject(
            "SELECT balance_purchased FROM credit_wallet WHERE actor_id = ?", Long.class, actorId);
    assertEquals(100, balance, "the wallet is untouched by a refused adjustment");
    Long entries =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ?", Long.class, actorId);
    assertEquals(0, entries, "no ledger entry for money that never moved");
  }

  @Test
  @DisplayName("the daily ceiling bounds what one admin account can mint")
  void enforcesTheDailyCeiling() {
    giveBalance(0, 0);
    // The ceiling is a credit QUANTITY, so it moved with the unit (ADR-047): 10 000 old credits
    // is 100 000 new ones, and an admin's daily authority is unchanged in what it can actually buy.
    long ceiling = 100_000;

    adjustmentService.adjust(actorId, CreditBucket.PURCHASED, ceiling - 100, "batch one", adminId);

    AdjustmentCeilingExceededException refusal =
        assertThrows(
            AdjustmentCeilingExceededException.class,
            () ->
                adjustmentService.adjust(
                    actorId, CreditBucket.PURCHASED, 500, "batch two", adminId));

    assertEquals(ceiling, refusal.dailyMax());
    assertEquals(100, refusal.remaining(), "the refusal says what is still possible today");
  }

  @Test
  @DisplayName("alternating grants and claw-backs cannot walk past the ceiling")
  void countsTheCeilingInAbsoluteCredits() {
    giveBalance(0, 200_000);

    adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 60_000, "grant", adminId);
    // A signed running total would now read 0 and permit unbounded further movement.
    adjustmentService.adjust(actorId, CreditBucket.PURCHASED, -30_000, "claw-back", adminId);

    assertEquals(90_000, adjustmentService.adjustedTodayBy(adminId));
    assertThrows(
        AdjustmentCeilingExceededException.class,
        () -> adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 20_000, "third", adminId));
  }

  @Test
  @DisplayName("the ceiling is per admin, so one hitting it does not block the others")
  void ceilingIsPerAdmin() {
    giveBalance(0, 0);
    adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 9_900, "batch", adminId);

    String secondAdmin = UUID.randomUUID().toString();
    WalletSnapshot after =
        adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 500, "second admin", secondAdmin);

    assertEquals(10_400, after.balancePurchased());
  }

  @Test
  @DisplayName("every adjustment is announced, so the user's balance updates without a refresh")
  void announcesTheAdjustment() {
    giveBalance(0, 0);

    adjustmentService.adjust(actorId, CreditBucket.GRANTED, 250, "goodwill", adminId);

    List<String> keys =
        jdbcTemplate.queryForList(
            "SELECT routing_key FROM billing_outbox WHERE aggregate_id = ?", String.class, actorId);
    assertEquals(List.of("evt.credit.granted"), keys);
  }

  @Test
  @DisplayName("an adjustment for an actor with no wallet opens one rather than failing")
  void createsAWalletOnDemand() {
    WalletSnapshot after =
        adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 100, "welcome credit", adminId);

    assertEquals(100, after.balancePurchased());
  }

  @Test
  @DisplayName("the ledger stays append-only — an adjustment cannot be rewritten away")
  void adjustmentsAreImmutable() {
    giveBalance(0, 0);
    adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 100, "goodwill", adminId);

    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbcTemplate.update(
                "UPDATE credit_ledger_entry SET amount = 0 WHERE actor_id = ?", actorId));
  }

  @Test
  @DisplayName("the guardrails reject a malformed adjustment before any money moves")
  void refusesMalformedAdjustments() {
    giveBalance(0, 100);

    assertThrows(
        IllegalArgumentException.class,
        () -> adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 0, "nothing", adminId));
    assertThrows(
        IllegalArgumentException.class,
        () -> adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 50, "   ", adminId));
    assertThrows(
        IllegalArgumentException.class,
        () -> adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 50, "why", "  "));
    assertThrows(
        NullPointerException.class,
        () -> adjustmentService.adjust(actorId, null, 50, "why", adminId));

    Long entries =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_ledger_entry WHERE actor_id = ?", Long.class, actorId);
    assertEquals(0, entries);
  }

  @Test
  @DisplayName("the balance recorded on the entry matches the wallet, as the audit anchor")
  void balanceAfterIsTheAuditAnchor() {
    giveBalance(0, 400);

    WalletSnapshot after =
        adjustmentService.adjust(actorId, CreditBucket.PURCHASED, 600, "goodwill", adminId);

    Long balanceAfter =
        jdbcTemplate.queryForObject(
            "SELECT balance_after FROM credit_ledger_entry WHERE actor_id = ?",
            Long.class,
            actorId);
    assertEquals(after.balancePurchased(), balanceAfter);
    assertTrue(balanceAfter == 1000);
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
    OutboxService outboxService(JdbcTemplate jdbcTemplate) {
      return new OutboxService(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    CreditAdjustmentService creditAdjustmentService(
        JdbcTemplate jdbcTemplate,
        BillingConfigurationService configurationService,
        OutboxService outboxService) {
      return new CreditAdjustmentService(jdbcTemplate, configurationService, outboxService);
    }
  }
}
