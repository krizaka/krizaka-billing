package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.domain.model.PackPrice;
import com.krizaka.billing.service.domain.model.CatalogPack;
import com.krizaka.billing.service.domain.model.CatalogPlan;
import com.krizaka.billing.service.domain.model.Entitlement;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
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
 * The catalogue against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>The claim under test is the design's own robustness test: creating a fourth plan must require
 * zero deploy (§12). That is only true if a plan and its entitlement matrix are entirely data —
 * which is a property of the schema and the writes, not of any Java object, so it is proved here.
 */
class CatalogIT {

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
  private static PlanCatalogService planCatalog;
  private static PackPricingService packPricing;

  private String planKey;
  private String packKey;

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
    packPricing = context.getBean(PackPricingService.class);
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
    packKey = "pkg-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private CatalogPlan plan(List<Entitlement> entitlements) {
    return new CatalogPlan(
        planKey, "Studio", 40, 250_000, 9900, "EUR", "premium", true, true, entitlements);
  }

  @Test
  @DisplayName("[§12] a fourth plan is created with no deploy — offer and matrix are both data")
  void createsAPlanWithoutADeploy() {
    CatalogPlan saved =
        planCatalog.save(
            plan(
                List.of(
                    new Entitlement("capability.video", "boolean", "true"),
                    new Entitlement("concurrency.jobs", "int", "12"))),
            ADMIN);

    assertEquals(planKey, saved.planKey());
    assertEquals(250_000, saved.monthlyCreditGrant());
    assertEquals(2, saved.entitlements().size());
    assertTrue(planCatalog.list(false).stream().anyMatch(p -> p.planKey().equals(planKey)));
  }

  @Test
  @DisplayName("the entitlement matrix is replaced wholesale, so a capability can be removed")
  void replacesTheMatrixRatherThanMerging() {
    planCatalog.save(
        plan(
            List.of(
                new Entitlement("capability.video", "boolean", "true"),
                new Entitlement("capability.agent", "boolean", "true"))),
        ADMIN);

    CatalogPlan narrowed =
        planCatalog.save(
            plan(List.of(new Entitlement("capability.video", "boolean", "true"))), ADMIN);

    assertEquals(1, narrowed.entitlements().size());
    assertEquals("capability.video", narrowed.entitlements().get(0).key());
  }

  @Test
  @DisplayName("saving twice is idempotent by key, so the console need not know create from edit")
  void saveIsIdempotentByKey() {
    planCatalog.save(plan(List.of()), ADMIN);
    planCatalog.save(plan(List.of()), ADMIN);

    long rows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_plan WHERE plan_key = ?", Long.class, planKey);
    assertEquals(1, rows);
  }

  @Test
  @DisplayName("a plan is retired, never deleted — subscriptions still reference it")
  void retireDeactivatesRatherThanDeletes() {
    planCatalog.save(plan(List.of()), ADMIN);

    assertTrue(planCatalog.retire(planKey, ADMIN));

    assertFalse(planCatalog.find(planKey).orElseThrow().isActive());
    assertTrue(
        planCatalog.list(false).stream().noneMatch(p -> p.planKey().equals(planKey)),
        "hidden from the public catalogue");
    assertTrue(
        planCatalog.list(true).stream().anyMatch(p -> p.planKey().equals(planKey)),
        "still visible to an admin explaining an existing subscription");
  }

  @Test
  @DisplayName("every catalogue change is snapshotted and attributed")
  void changesAreSnapshotted() {
    planCatalog.save(plan(List.of()), ADMIN);
    planCatalog.save(plan(List.of(new Entitlement("capability.video", "boolean", "true"))), ADMIN);

    Long snapshots =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PLAN'"
                + " AND entity_key = ? AND created_by = ?",
            Long.class,
            planKey,
            ADMIN);
    assertEquals(1, snapshots, "the prior state, captured before it was replaced");
  }

  @Test
  @DisplayName("an unattributable catalogue change is refused")
  void refusesAnAnonymousChange() {
    assertThrows(IllegalArgumentException.class, () -> planCatalog.save(plan(List.of()), "  "));
  }

  @Test
  @DisplayName("an int entitlement that does not parse is refused at write time")
  void refusesAMalformedEntitlement() {
    // A limit that fails to parse would fall back to a code default at read time and silently
    // grant something nobody configured (ADR-031).
    assertThrows(
        IllegalArgumentException.class, () -> new Entitlement("concurrency.jobs", "int", "many"));
  }

  private CatalogPack pack(List<Entitlement> entitlements) {
    return new CatalogPack(packKey, 14900, 100_000, true, entitlements);
  }

  @Test
  @DisplayName("packs use the same grammar, so one editor serves both")
  void packsShareThePlanGrammar() {
    CatalogPack saved =
        packPricing.save(
            pack(List.of(new Entitlement("studio.marketing", "boolean", "true"))), ADMIN);

    assertEquals(packKey, saved.packKey());
    assertEquals(1, saved.entitlements().size());
    assertEquals("studio.marketing", saved.entitlements().get(0).key());
  }

  @Test
  @DisplayName("[ADR-036] billing prices a pack and no longer pretends to describe it")
  void billingCarriesThePriceTagOnly() {
    // The label, the shelf and the métier moved to the `pack` row in the studio context. What
    // reads back here is what billing is authoritative for. A record component is the strongest
    // possible statement of that split: there is no `category()` to call any more.
    CatalogPack saved = packPricing.save(pack(List.of()), ADMIN);

    assertEquals(14900, saved.priceCents());
    assertEquals(100_000L, saved.includedCredits());
    assertTrue(saved.isActive());
  }

  @Test
  @DisplayName("the price table prices every pack in one read, withdrawn ones included")
  void pricesTheWholeTableInOneCall() {
    // The marketplace renders N cards from ONE call. Pricing card by card would turn a browse
    // into N service hops, which is why PackPricingClient takes a set and this returns a table.
    packPricing.save(pack(List.of()), ADMIN);
    packPricing.withdraw(packKey, ADMIN);

    PackPrice withdrawn =
        packPricing.prices().stream()
            .filter(price -> price.packKey().equals(packKey))
            .findFirst()
            .orElseThrow();

    // Still priced after withdrawal: the actors who already bought it must see what they paid.
    assertEquals(14900, withdrawn.priceCents());
    assertFalse(withdrawn.active());
  }

  @Test
  @DisplayName("a pack is withdrawn rather than deleted, like a plan")
  void withdrawDeactivates() {
    packPricing.save(pack(List.of()), ADMIN);

    assertTrue(packPricing.withdraw(packKey, ADMIN));

    assertFalse(packPricing.find(packKey).orElseThrow().isActive());
  }

  @Test
  @DisplayName("an unknown key reads as absent rather than as an error")
  void unknownKeysAreEmpty() {
    assertTrue(planCatalog.find("no-such-plan").isEmpty());
    assertTrue(packPricing.find("no-such-pack").isEmpty());
    assertFalse(planCatalog.retire("no-such-plan", ADMIN));
    assertFalse(packPricing.withdraw("no-such-pack", ADMIN));
  }

  @Test
  @DisplayName("the seeded catalogue still reads back, matrix and all")
  void readsTheSeededCatalogue() {
    List<CatalogPlan> plans = planCatalog.list(false);

    assertTrue(plans.size() >= 3, "free, premium, ultimate");
    CatalogPlan cheapest = plans.get(0);
    assertTrue(
        plans.stream().allMatch(p -> p.tierRank() >= cheapest.tierRank()),
        "ordered by tier_rank, which is how the entry plan is resolved");
    assertFalse(cheapest.entitlements().isEmpty(), "the seeded matrix is readable");
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
    PlanCatalogService planCatalogService(
        JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
      return new PlanCatalogService(jdbcTemplate, versionHistoryService);
    }

    @Bean
    PackPricingService packPricingService(
        JdbcTemplate jdbcTemplate, BillingVersionHistoryService versionHistoryService) {
      return new PackPricingService(jdbcTemplate, versionHistoryService);
    }
  }
}
