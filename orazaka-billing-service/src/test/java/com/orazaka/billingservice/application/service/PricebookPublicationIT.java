package com.orazaka.billingservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billing.domain.model.BillableCapability;
import com.orazaka.billing.domain.model.BillableUnit;
import com.orazaka.billingservice.domain.model.MarginPreview;
import com.orazaka.billingservice.domain.model.PricebookRate;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Pricebook publication against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>The invariant under test is the one the whole versioning scheme rests on: a rate is closed and
 * superseded, never overwritten. A settlement prices against the version its hold pinned, so an
 * in-place update would retroactively change what already-authorised work costs — and the partial
 * unique index on {@code effective_to IS NULL} is what makes the mistake impossible rather than
 * merely discouraged. Both only hold against the real schema, which is why this is an IT.
 */
class PricebookPublicationIT {

  private static final String BILLING_DB = "orazaka_billing_db";
  private static final String BILLING_ROLE = "orazaka_billing";
  private static final String BILLING_PASSWORD = "orazaka_billing_pass";
  private static final String ADMIN = "550e8400-e29b-41d4-a716-446655440001";

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
  private static PricebookService pricebookService;

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
    pricebookService = context.getBean(PricebookService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  private static long liveRowCount(BillableCapability capability, String modelName) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM credit_pricebook WHERE capability = ?"
            + " AND COALESCE(model_name, '*') = COALESCE(?, '*') AND effective_to IS NULL",
        Long.class,
        capability.name(),
        modelName);
  }

  @Test
  @DisplayName("publishing supersedes the current rate instead of overwriting it")
  void publishClosesTheCurrentRowAndOpensTheNext() {
    PricebookRate before =
        pricebookService.current().stream()
            .filter(r -> r.capability() == BillableCapability.CHAT && r.modelName() == null)
            .findFirst()
            .orElseThrow();

    PricebookRate published =
        pricebookService.publish(
            BillableCapability.CHAT,
            null,
            BillableUnit.KILOTOKEN,
            new BigDecimal("2.5000"),
            2,
            5,
            ADMIN);

    assertEquals(before.version() + 1, published.version());
    assertEquals(0, published.creditsPerUnit().compareTo(new BigDecimal("2.5000")));
    assertEquals(1, liveRowCount(BillableCapability.CHAT, null), "exactly one rate stays live");

    // The superseded row survives, so a hold pinned to it can still be settled at its own price.
    List<PricebookRate> history = pricebookService.history(BillableCapability.CHAT, null);
    assertTrue(history.size() >= 2);
    assertEquals(published.version(), history.get(0).version(), "newest first");
  }

  @Test
  @DisplayName("a superseded rate stays readable, so an old invoice can still be explained")
  void supersededRowsAreRetained() {
    pricebookService.publish(
        BillableCapability.AGENT, null, BillableUnit.CALL, new BigDecimal("7.0000"), 7, 7, ADMIN);
    pricebookService.publish(
        BillableCapability.AGENT, null, BillableUnit.CALL, new BigDecimal("9.0000"), 9, 9, ADMIN);

    Long closed =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM credit_pricebook WHERE capability = 'AGENT'"
                + " AND effective_to IS NOT NULL",
            Long.class);

    assertTrue(closed >= 2, "every prior version is closed, none deleted");
    assertEquals(1, liveRowCount(BillableCapability.AGENT, null));
  }

  @Test
  @DisplayName("every publication is attributed and snapshotted")
  void publicationIsSnapshotted() {
    pricebookService.publish(
        BillableCapability.IMAGE,
        null,
        BillableUnit.IMAGE_STEP,
        new BigDecimal("3.0000"),
        6,
        24,
        ADMIN);

    Long snapshots =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PRICEBOOK'"
                + " AND entity_key = 'IMAGE:*' AND created_by = ?",
            Long.class,
            ADMIN);

    assertTrue(snapshots >= 1);
  }

  @Test
  @DisplayName("an unattributable price change is refused")
  void refusesAnAnonymousPublication() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            pricebookService.publish(
                BillableCapability.VIDEO,
                null,
                BillableUnit.OUTPUT_SECOND,
                new BigDecimal("90.0000"),
                90,
                360,
                "  "));
  }

  @Test
  @DisplayName("a first rate for an unpriced model publishes at version 1")
  void publishesANewModelRate() {
    String model = "wan-" + UUID.randomUUID();

    PricebookRate published =
        pricebookService.publish(
            BillableCapability.VIDEO,
            model,
            BillableUnit.OUTPUT_SECOND,
            new BigDecimal("120.0000"),
            120,
            480,
            ADMIN);

    assertEquals(1, published.version());
    assertEquals(model, published.modelName());
  }

  @Test
  @DisplayName("the preview reprices real usage, applying the floor per event as settlement does")
  void previewRepricesRecordedUsage() {
    String model = "preview-" + UUID.randomUUID();
    pricebookService.publish(
        BillableCapability.VIDEO,
        model,
        BillableUnit.OUTPUT_SECOND,
        new BigDecimal("10.0000"),
        1,
        10,
        ADMIN);

    // Two events of 4 output-seconds, charged 40 credits each at the live 10 credits/second.
    for (int i = 0; i < 2; i++) {
      jdbcTemplate.update(
          "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
              + " credits_charged, correlation_id, occurred_at)"
              + " VALUES (?, 'VIDEO', ?, 'OUTPUT_SECOND', 4, 40, ?, now())",
          UUID.randomUUID().toString(),
          model,
          UUID.randomUUID().toString());
    }

    MarginPreview preview =
        pricebookService.preview(BillableCapability.VIDEO, model, new BigDecimal("15.0000"), 1);

    assertEquals(2, preview.sampleEvents());
    assertEquals(80, preview.currentCredits());
    assertEquals(120, preview.proposedCredits(), "2 events × 4s × 15 credits");
    assertEquals(40, preview.deltaCredits());
    assertTrue(preview.hasEvidence());
  }

  @Test
  @DisplayName("the preview honours the proposed floor rather than scaling the old totals")
  void previewAppliesTheProposedFloorPerEvent() {
    String model = "floor-" + UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, correlation_id, occurred_at)"
            + " VALUES (?, 'VIDEO', ?, 'OUTPUT_SECOND', 0.1, 1, ?, now())",
        UUID.randomUUID().toString(),
        model,
        UUID.randomUUID().toString());

    // 0.1 × 2 = 0.2, which rounds up to 1 and is then lifted to the proposed floor of 50.
    MarginPreview preview =
        pricebookService.preview(BillableCapability.VIDEO, model, new BigDecimal("2.0000"), 50);

    assertEquals(50, preview.proposedCredits());
  }

  @Test
  @DisplayName("usage older than the window is not evidence for today's price")
  void previewIgnoresTrafficOutsideTheWindow() {
    String model = "stale-" + UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, correlation_id, occurred_at)"
            + " VALUES (?, 'VIDEO', ?, 'OUTPUT_SECOND', 4, 40, ?, now() - INTERVAL '31 days')",
        UUID.randomUUID().toString(),
        model,
        UUID.randomUUID().toString());

    MarginPreview preview =
        pricebookService.preview(BillableCapability.VIDEO, model, new BigDecimal("15.0000"), 1);

    assertEquals(0, preview.sampleEvents());
    assertFalse(preview.hasEvidence());
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
    PricebookService pricebookService(
        JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
      return new PricebookService(jdbcTemplate, versionHistoryService);
    }
  }
}
