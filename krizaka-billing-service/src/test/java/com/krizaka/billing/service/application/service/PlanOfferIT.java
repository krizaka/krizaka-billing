package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.krizaka.billing.service.domain.model.CatalogPlan;
import com.krizaka.billing.service.domain.model.SubscriptionStatus;
import com.krizaka.messaging.topology.MessagingExchanges;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
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
 * The catalogue against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>The claim under test is the design's own robustness test: creating a fourth plan must require
 * zero deploy (§12). That is only true if a plan and its entitlement matrix are entirely data —
 * which is a property of the schema and the writes, not of any Java object, so it is proved here.
 */
/** Versioned offers (krizaka-billing#80) against the real {@code infra/initdb/70-billing.sql}. */
class PlanOfferIT {

  private static final String BILLING_DB = "krizaka_billing_db";
  private static final String BILLING_ROLE = "krizaka_billing";
  private static final String BILLING_PASSWORD = "krizaka_billing_pass";
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
  private static PlanCatalogService planCatalog;
  private static SubscriptionService subscriptions;
  private static BillingConfigurationService configuration;

  private String planKey;

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
    planCatalog = context.getBean(PlanCatalogService.class);
    subscriptions = context.getBean(SubscriptionService.class);
    configuration = context.getBean(BillingConfigurationService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshKeys() {
    planKey = "plan-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private CatalogPlan plan(int priceCents, Integer overage, int trialDays, Long free) {
    return new CatalogPlan(
        planKey,
        "Studio",
        40,
        1_000,
        priceCents,
        "CAD",
        "premium",
        true,
        true,
        overage,
        trialDays,
        free,
        null);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  @DisplayName("[#80] the offer fields are stored, read back and audited with the plan")
  void offerFieldsRoundTrip() {
    planCatalog.save(plan(2900, 3, 14, 250L), ADMIN);
    planCatalog.save(plan(2900, 4, 7, null), ADMIN);

    CatalogPlan read = planCatalog.find(planKey).orElseThrow();
    assertEquals(4, read.overageCreditPriceCents());
    assertEquals(7, read.trialDays());
    assertNull(read.freeMonthlyCredits());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PLAN'"
                + " AND entity_key = ?",
            planKey));
  }

  @Test
  @DisplayName("[#80] a new price is a new version; a held subscription keeps the old one")
  void subscriptionKeepsItsPriceVersion() throws InterruptedException {
    planCatalog.save(plan(2900, null, 0, null), ADMIN);
    subscriptions.changePlan("actor-" + planKey, planKey, SubscriptionStatus.ACTIVE);
    Thread.sleep(5);

    CatalogPlan repriced = planCatalog.save(plan(3900, null, 0, null), ADMIN);

    assertEquals(3900, repriced.priceCents(), "the catalogue shows the new amount");
    assertEquals(2900L, subscriptions.billedPriceCents("actor-" + planKey).orElseThrow());
    subscriptions.changePlan("new-" + planKey, planKey, SubscriptionStatus.ACTIVE);
    assertEquals(3900L, subscriptions.billedPriceCents("new-" + planKey).orElseThrow());
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM billing_price WHERE price_key = ?",
            PlanCatalogService.PRICE_KEY_PREFIX + planKey));
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PRICE'"
                + " AND entity_key = ?",
            PlanCatalogService.PRICE_KEY_PREFIX + planKey));
  }

  @Test
  @DisplayName("[#80] saving the same price again adds no version")
  void samePriceAddsNoVersion() {
    planCatalog.save(plan(2900, null, 0, null), ADMIN);
    planCatalog.save(plan(2900, 2, 0, null), ADMIN);
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM billing_price WHERE price_key = ?",
            PlanCatalogService.PRICE_KEY_PREFIX + planKey));
  }

  @Test
  @DisplayName("[#80] a trial lasts the plan's trial length; without one it is a normal period")
  void trialLength() {
    planCatalog.save(plan(2900, null, 14, null), ADMIN);
    var trial = subscriptions.changePlan("t-" + planKey, planKey, SubscriptionStatus.TRIALING);
    assertEquals(14L, java.time.Duration.between(trial.periodStart(), trial.periodEnd()).toDays());

    planCatalog.save(plan(2900, null, 0, null), ADMIN);
    var plain = subscriptions.changePlan("u-" + planKey, planKey, SubscriptionStatus.TRIALING);
    assertEquals(30L, java.time.Duration.between(plain.periodStart(), plain.periodEnd()).toDays());
  }

  @Test
  @DisplayName(
      "[#80] the seeded plans are priced through the catalogue (no price outside the seed)")
  void seededPlansAreInTheCatalogue() {
    assertEquals(
        3,
        count(
            "SELECT count(*) FROM billing_price WHERE price_key IN"
                + " ('plan.free','plan.premium','plan.ultimate')"
                + " AND created_by = 'seed'"));
    assertEquals(2900, planCatalog.find("premium").orElseThrow().priceCents());
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
    PlanCatalogService planCatalogService(
        JdbcTemplate jdbcTemplate,
        BillingVersionHistoryService versionHistoryService,
        BillingConfigurationService configuration) {
      return new PlanCatalogService(jdbcTemplate, versionHistoryService, configuration);
    }

    @Bean
    OutboxService outboxService(JdbcTemplate jdbcTemplate) {
      return new OutboxService(jdbcTemplate, new ObjectMapper(), MessagingExchanges.defaults());
    }

    @Bean
    SubscriptionService subscriptionService(
        JdbcTemplate jdbcTemplate, OutboxService outboxService) {
      return new SubscriptionService(jdbcTemplate, outboxService);
    }
  }
}
