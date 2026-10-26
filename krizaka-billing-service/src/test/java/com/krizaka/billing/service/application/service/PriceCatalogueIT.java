package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.service.domain.model.AdminPriceView;
import com.krizaka.billing.service.domain.model.PriceAvailability;
import com.krizaka.billing.service.domain.model.PriceChange;
import com.krizaka.billing.service.domain.model.PriceUnit;
import com.krizaka.billing.service.domain.model.PriceView;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * The price catalogue against the <b>real</b> {@code infra/initdb/70-billing.sql}
 * (krizaka/orazaka#417).
 */
class PriceCatalogueIT {

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
  private static PriceCatalogueService prices;
  private static PackPricingService packPricing;
  private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

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
    prices = context.getBean(PriceCatalogueService.class);
    packPricing = context.getBean(PackPricingService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @Test
  @DisplayName(
      "[#417] the seed holds the defaults: Community 0, Business 2 900 $ a year, preparing")
  void seedHoldsTheDefaults() {
    assertEquals(0L, prices.entry("orazaka.community", NOW).orElseThrow().amountCents());
    var business = prices.entry("orazaka.business", NOW).orElseThrow();
    assertEquals(290_000L, business.amountCents());
    assertEquals(PriceAvailability.PREPARING, business.availability());
    assertEquals(
        PriceUnit.MONTH, prices.entry("orazaka.businessMonthly", NOW).orElseThrow().unit());
    assertEquals(
        Map.of("includedHours", 8L, "overageCents", 15_500L),
        prices.entry("orazaka.croissance", NOW).orElseThrow().limits());
    assertEquals(
        Map.of("credits", 5000L), prices.entry("orazaka.creditsPro", NOW).orElseThrow().limits());
  }

  @Test
  @DisplayName("the public view never shows internal entries and hides a preparing figure")
  void publicViewHidesInternalAndFigures() {
    List<PriceView> view = prices.publicCatalogue();
    assertTrue(view.stream().noneMatch(v -> v.key().startsWith("orazaka.credits")));
    assertTrue(view.stream().noneMatch(v -> v.key().equals("orazaka.sentinelle")));
    PriceView business =
        view.stream().filter(v -> v.key().equals("orazaka.business")).findFirst().orElseThrow();
    assertNull(business.amountCents());
    assertEquals(
        0L,
        view.stream()
            .filter(v -> v.key().equals("orazaka.community"))
            .findFirst()
            .orElseThrow()
            .amountCents());
  }

  @Test
  @DisplayName(
      "a change is dated: the old version applies until it starts, history keeps both, one audit line")
  void scheduledChange() {
    Instant start = NOW.plusSeconds(3600);
    String key = "orazaka.extractionMatrices";
    int audits =
        count(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PRICE' AND entity_key = '"
                + key
                + "'");
    AdminPriceView next =
        prices.schedule(
            ADMIN,
            key,
            new PriceChange(PriceUnit.MONTH, 9900L, PriceAvailability.PREPARING, Map.of()),
            start);
    assertEquals(9900L, next.amountCents());
    assertEquals(8500L, prices.entry(key, NOW).orElseThrow().amountCents());
    assertEquals(9900L, prices.entry(key, start.plusSeconds(1)).orElseThrow().amountCents());
    assertEquals(next.versionId(), prices.entry(key, start).orElseThrow().versionId());
    assertEquals(2, prices.history(key).size());
    assertEquals(1, prices.upcoming(key, NOW).size());
    assertEquals(
        audits + 1,
        count(
            "SELECT count(*) FROM billing_version_history WHERE entity_type = 'PRICE' AND entity_key = '"
                + key
                + "'"));
  }

  @Test
  @DisplayName("a change in the past, on an unknown key or without actor is refused")
  void refusesBadChanges() {
    var change = new PriceChange(PriceUnit.MONTH, 1L, PriceAvailability.PUBLIC, Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> prices.schedule(ADMIN, "orazaka.business", change, NOW.minusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> prices.schedule(ADMIN, "orazaka.nope", change, NOW.plusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> prices.schedule(" ", "orazaka.business", change, NOW.plusSeconds(60)));
  }

  @Test
  @DisplayName(
      "a pack reads its amount through the catalogue by key; the column only backs unpriced packs")
  void packReadsThroughCatalogue() {
    jdbcTemplate.update(
        "INSERT INTO billing_pack (pack_key, price_cents, included_credits, is_active) VALUES ('creditsPro', 1, 5000, true) ON CONFLICT DO NOTHING");
    jdbcTemplate.update(
        "INSERT INTO billing_pack (pack_key, price_cents, included_credits, is_active) VALUES ('unpriced-pack', 777, 1, true) ON CONFLICT DO NOTHING");
    assertEquals(4500, packPricing.find("creditsPro").orElseThrow().priceCents());
    assertEquals(777, packPricing.find("unpriced-pack").orElseThrow().priceCents());
  }

  private static int count(String sql) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

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
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    BillingVersionHistoryService billingVersionHistoryService(
        JdbcTemplate jdbcTemplate, ObjectMapper mapper) {
      return new BillingVersionHistoryService(jdbcTemplate, mapper);
    }

    @Bean
    PriceCatalogueService priceCatalogueService(
        JdbcTemplate jdbc, BillingVersionHistoryService history, ObjectMapper mapper, Clock clock) {
      return new PriceCatalogueService(jdbc, history, mapper, clock);
    }

    @Bean
    PackPricingService packPricingService(JdbcTemplate jdbc, BillingVersionHistoryService history) {
      return new PackPricingService(jdbc, history);
    }
  }
}
