package com.orazaka.billingservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billingservice.domain.model.ActorConsumption;
import com.orazaka.billingservice.domain.model.CapabilityUsage;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Usage analytics against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>The join is what these tests exist for. A capability can have refusals and no successes, or
 * successes and no refusals, and an inner join would silently drop whichever side is empty — losing
 * exactly the case the screen exists to surface, since a capability priced entirely out of reach
 * has no usage rows at all.
 */
class UsageAnalyticsIT {

  private static final String BILLING_DB = "orazaka_billing_db";
  private static final String BILLING_ROLE = "orazaka_billing";
  private static final String BILLING_PASSWORD = "orazaka_billing_pass";
  private static final Duration WINDOW = Duration.ofDays(30);

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
  private static UsageAnalyticsService analyticsService;

  private String model;

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
    analyticsService = context.getBean(UsageAnalyticsService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshModel() {
    // A unique model per test isolates each one's rows without truncating shared tables.
    model = "m-" + UUID.randomUUID();
  }

  private void recordUsage(String actorId, long credits) {
    recordUsage(actorId, credits, null);
  }

  private void recordUsage(String actorId, long credits, java.math.BigDecimal gpuSeconds) {
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, correlation_id, occurred_at, gpu_seconds)"
            + " VALUES (?, 'VIDEO', ?, 'OUTPUT_SECOND', 4, ?, ?, now(), ?)",
        actorId,
        model,
        credits,
        UUID.randomUUID().toString(),
        gpuSeconds);
  }

  private void recordRefusal(boolean enforced) {
    jdbcTemplate.update(
        "INSERT INTO credit_refusal (actor_id, capability, model_name, required, available,"
            + " enforced, correlation_id) VALUES (?, 'VIDEO', ?, 360, 10, ?, ?)",
        UUID.randomUUID().toString(),
        model,
        enforced,
        UUID.randomUUID().toString());
  }

  private CapabilityUsage mine() {
    Optional<CapabilityUsage> row =
        analyticsService.byCapability(WINDOW).stream()
            .filter(u -> model.equals(u.modelName()))
            .findFirst();
    return row.orElseThrow(() -> new AssertionError("no analytics row for " + model));
  }

  @Test
  @DisplayName("consumption and refusals are reported together for one capability × model")
  void reportsUsageAndRefusalsTogether() {
    recordUsage(UUID.randomUUID().toString(), 360);
    recordUsage(UUID.randomUUID().toString(), 720);
    recordRefusal(true);

    CapabilityUsage usage = mine();

    assertEquals(2, usage.events());
    assertEquals(1080, usage.creditsCharged());
    assertEquals(1, usage.refusals());
    assertEquals(1, usage.enforcedRefusals());
  }

  @Test
  @DisplayName("the refusal rate is the share of everything attempted, not of what succeeded")
  void refusalRateCountsAttempts() {
    recordUsage(UUID.randomUUID().toString(), 100);
    recordUsage(UUID.randomUUID().toString(), 100);
    recordUsage(UUID.randomUUID().toString(), 100);
    recordRefusal(true);

    // 1 refusal out of 4 attempts, not 1 out of 3 successes.
    assertEquals(0, mine().refusalRatePercent().compareTo(new BigDecimal("25.00")));
  }

  @Test
  @DisplayName("a capability priced out of reach still appears, though it has no usage at all")
  void surfacesACapabilityWithOnlyRefusals() {
    recordRefusal(true);
    recordRefusal(true);

    CapabilityUsage usage = mine();

    assertEquals(0, usage.events());
    assertEquals(2, usage.refusals());
    assertEquals(0, usage.refusalRatePercent().compareTo(new BigDecimal("100.00")));
  }

  @Test
  @DisplayName("a comfortably priced capability appears with a zero refusal rate")
  void surfacesACapabilityWithNoRefusals() {
    recordUsage(UUID.randomUUID().toString(), 500);

    CapabilityUsage usage = mine();

    assertEquals(1, usage.events());
    assertEquals(0, usage.refusals());
    assertEquals(0, usage.refusalRatePercent().compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("shadow refusals count, which is what makes the metric readable during DRY_RUN")
  void countsShadowRefusalsSeparately() {
    recordUsage(UUID.randomUUID().toString(), 100);
    recordRefusal(false);
    recordRefusal(false);
    recordRefusal(true);

    CapabilityUsage usage = mine();

    assertEquals(3, usage.refusals(), "everything that could not be covered");
    assertEquals(1, usage.enforcedRefusals(), "only one user was actually turned away");
    assertEquals(0, usage.refusalRatePercent().compareTo(new BigDecimal("75.00")));
  }

  @Test
  @DisplayName("the average cost per event is what a user actually feels per action")
  void reportsAverageCostPerEvent() {
    recordUsage(UUID.randomUUID().toString(), 300);
    recordUsage(UUID.randomUUID().toString(), 500);

    assertEquals(0, mine().averageCreditsPerEvent().compareTo(new BigDecimal("400.00")));
  }

  @Test
  @DisplayName("top consumers rank by spend, not by request count")
  void ranksConsumersBySpend() {
    String heavy = UUID.randomUUID().toString();
    String chatty = UUID.randomUUID().toString();
    recordUsage(heavy, 5000);
    for (int i = 0; i < 4; i++) {
      recordUsage(chatty, 100);
    }

    List<ActorConsumption> top = analyticsService.topConsumers(WINDOW, 50);
    ActorConsumption first =
        top.stream().filter(c -> c.actorId().equals(heavy)).findFirst().orElseThrow();
    ActorConsumption second =
        top.stream().filter(c -> c.actorId().equals(chatty)).findFirst().orElseThrow();

    assertEquals(5000, first.creditsCharged());
    assertEquals(4, second.events());
    assertTrue(
        top.indexOf(first) < top.indexOf(second), "one expensive render outranks four cheap ones");
  }

  @Test
  @DisplayName("traffic outside the window is not part of today's picture")
  void excludesTrafficOutsideTheWindow() {
    jdbcTemplate.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, correlation_id, occurred_at)"
            + " VALUES (?, 'VIDEO', ?, 'OUTPUT_SECOND', 4, 999, ?, now() - INTERVAL '31 days')",
        UUID.randomUUID().toString(),
        model,
        UUID.randomUUID().toString());
    recordUsage(UUID.randomUUID().toString(), 100);

    assertEquals(1, mine().events());
    assertEquals(100, mine().creditsCharged());
  }

  @Test
  @DisplayName("margin reads as credits per GPU-second, the only cost owned hardware emits")
  void reportsMargin() {
    recordUsage(UUID.randomUUID().toString(), 360, new BigDecimal("30.000"));
    recordUsage(UUID.randomUUID().toString(), 360, new BigDecimal("30.000"));

    // 720 credits over 60 GPU-seconds.
    assertEquals(0, mine().creditsPerGpuSecond().orElseThrow().compareTo(new BigDecimal("12.00")));
  }

  @Test
  @DisplayName("traffic with no occupancy reads as blank, not as free")
  void marginIsBlankWithoutOccupancy() {
    recordUsage(UUID.randomUUID().toString(), 500);

    assertTrue(
        mine().creditsPerGpuSecond().isEmpty(),
        "unmeasured and zero-cost are different claims and must not look alike");
  }

  /** Read-only analytics: no transaction manager needed, unlike the write-path ITs. */
  @Configuration
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
    UsageAnalyticsService usageAnalyticsService(JdbcTemplate jdbcTemplate) {
      return new UsageAnalyticsService(jdbcTemplate);
    }
  }
}
