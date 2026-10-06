package com.orazaka.billingservice.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orazaka.billingservice.application.service.SubscriptionService.SubscriptionView;
import com.orazaka.billingservice.domain.model.SubscriptionStatus;
import com.orazaka.test.architecture.SqlBoundaryRules;
import com.orazaka.test.container.ServiceRoles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * Subscription lifecycle against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>Two properties only the real schema can prove: the partial unique index really does reject a
 * second live subscription (so plan changes cannot silently double-enrol an actor), and every
 * mutation really does leave an {@code evt.subscription.*} row in the outbox — the event a cached
 * entitlement snapshot depends on to learn that a plan changed.
 */
class SubscriptionLifecycleIT {

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
  private static SubscriptionService subscriptionService;

  private String actorId;

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
    subscriptionService = context.getBean(SubscriptionService.class);
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

  private long liveRows() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM billing_subscription WHERE actor_id = ?"
            + " AND status IN ('TRIALING','ACTIVE','PAST_DUE')",
        Long.class,
        actorId);
  }

  private List<String> emittedRoutingKeys() {
    return jdbcTemplate.query(
        "SELECT routing_key FROM billing_outbox WHERE aggregate_id = ? ORDER BY created_at",
        (rs, rowNum) -> rs.getString(1),
        actorId);
  }

  @Test
  @DisplayName("changing plan leaves exactly one live subscription, whatever the actor was on")
  void changePlanSupersedesTheLiveOne() {
    subscriptionService.changePlan(actorId, "free", SubscriptionStatus.ACTIVE);
    subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.ACTIVE);
    SubscriptionView current =
        subscriptionService.changePlan(actorId, "ultimate", SubscriptionStatus.ACTIVE);

    assertEquals("ultimate", current.planKey());
    assertEquals(1, liveRows(), "the partial unique index permits exactly one live row");
  }

  @Test
  @DisplayName("every change is announced, so a cached entitlement snapshot learns about it")
  void everyChangeEmitsAnEvent() {
    subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.TRIALING);
    subscriptionService.changePlan(actorId, "ultimate", SubscriptionStatus.ACTIVE);

    assertEquals(
        List.of("evt.subscription.changed", "evt.subscription.changed"), emittedRoutingKeys());
  }

  @Test
  @DisplayName("a trial is the same operation as an upgrade, differing only in status")
  void trialIsAStatusNotASeparatePath() {
    SubscriptionView trial =
        subscriptionService.changePlan(actorId, "ultimate", SubscriptionStatus.TRIALING);

    assertEquals(SubscriptionStatus.TRIALING, trial.status());
    assertTrue(trial.status().isLive(), "a trialing actor holds their plan's entitlements");
    assertEquals(1, liveRows());
  }

  @Test
  @DisplayName("cancelling at period end keeps the access the user already paid for")
  void cancelAtPeriodEndKeepsAccess() {
    subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.ACTIVE);

    Optional<SubscriptionView> canceled = subscriptionService.cancel(actorId, false);

    assertTrue(canceled.orElseThrow().cancelAtPeriodEnd());
    assertEquals(1, liveRows(), "still live until the period ends");
    assertTrue(subscriptionService.forActor(actorId).orElseThrow().cancelAtPeriodEnd());
  }

  @Test
  @DisplayName("cancelling immediately ends access now and says so")
  void cancelImmediatelyEndsAccess() {
    subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.ACTIVE);

    SubscriptionView canceled = subscriptionService.cancel(actorId, true).orElseThrow();

    assertEquals(SubscriptionStatus.CANCELED, canceled.status());
    assertFalse(canceled.status().isLive());
    assertEquals(0, liveRows());
    assertTrue(emittedRoutingKeys().contains("evt.subscription.canceled"));
  }

  @Test
  @DisplayName("rollover honours a pending cancellation rather than silently renewing it")
  void rollOverRespectsPendingCancellation() {
    subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.ACTIVE);
    subscriptionService.cancel(actorId, false);

    SubscriptionView rolled = subscriptionService.rollOver(actorId).orElseThrow();

    assertEquals(SubscriptionStatus.EXPIRED, rolled.status());
    assertEquals(0, liveRows());
  }

  @Test
  @DisplayName("rollover opens a fresh period for a subscription that is still renewing")
  void rollOverRenews() {
    SubscriptionView before =
        subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.TRIALING);

    SubscriptionView renewed = subscriptionService.rollOver(actorId).orElseThrow();

    assertEquals(SubscriptionStatus.ACTIVE, renewed.status(), "a trial converts on rollover");
    assertTrue(renewed.periodEnd().isAfter(before.periodEnd()));
    assertEquals(1, liveRows());
    assertTrue(emittedRoutingKeys().contains("evt.subscription.renewed"));
  }

  @Test
  @DisplayName("an actor with no subscription is not an error — they are on the entry plan")
  void absentSubscriptionIsEmptyNotAnError() {
    assertTrue(subscriptionService.forActor(actorId).isEmpty());
    assertTrue(subscriptionService.cancel(actorId, true).isEmpty());
    assertTrue(subscriptionService.rollOver(actorId).isEmpty());
    assertTrue(emittedRoutingKeys().isEmpty(), "nothing changed, so nothing is announced");
  }

  @Test
  @DisplayName("a subscription cannot be opened already dead")
  void refusesToOpenInATerminalStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> subscriptionService.changePlan(actorId, "premium", SubscriptionStatus.CANCELED));
  }

  @Test
  void refusesABlankActor() {
    assertThrows(
        IllegalArgumentException.class,
        () -> subscriptionService.changePlan("  ", "premium", SubscriptionStatus.ACTIVE));
  }

  /** Minimal transactional wiring: real {@code @Transactional} proxies, no web/security/AMQP. */
  @Configuration
  @EnableTransactionManagement
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
    OutboxService outboxService(JdbcTemplate jdbcTemplate) {
      return new OutboxService(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    SubscriptionService subscriptionService(
        JdbcTemplate jdbcTemplate, OutboxService outboxService) {
      return new SubscriptionService(jdbcTemplate, outboxService);
    }
  }
}
