package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.messaging.outbox.OutboxMessage;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The outbox drain against the <b>real</b> {@code infra/initdb/70-billing.sql}.
 *
 * <p>What only the real schema proves: the partial index's {@code published_at IS NULL} predicate
 * and the {@code next_attempt_at} back-off actually govern which rows a poll returns. A relay that
 * re-published delivered events, or ignored a back-off, would double-charge downstream consumers —
 * and a mocked store would happily agree with either mistake.
 */
class OutboxDrainIT {

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
  private static OutboxService outboxService;

  private String aggregateId;

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
    outboxService = context.getBean(OutboxService.class);
  }

  @AfterAll
  static void stopContainer() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  @BeforeEach
  void freshAggregate() {
    aggregateId = UUID.randomUUID().toString();
    // Isolate this test's view of the queue from rows other tests appended.
    jdbcTemplate.update(
        "UPDATE billing_outbox SET published_at = now() WHERE published_at IS NULL");
  }

  private List<OutboxMessage> mine(List<OutboxMessage> batch) {
    List<String> ids =
        jdbcTemplate.queryForList(
            "SELECT id::text FROM billing_outbox WHERE aggregate_id = ?",
            String.class,
            aggregateId);
    return batch.stream().filter(e -> ids.contains(e.id().toString())).toList();
  }

  @Test
  @DisplayName("a recorded event is pending until it is relayed, then never returned again")
  void drainsThenStopsReturningTheRow() {
    outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.changed", "{\"a\":1}");

    List<OutboxMessage> first = mine(outboxService.lockPendingBatch(100));
    assertEquals(1, first.size());
    assertEquals("orazaka.events", first.get(0).exchange());
    assertEquals("evt.subscription.changed", first.get(0).routingKey());

    outboxService.markPublished(first.get(0).id());

    assertTrue(
        mine(outboxService.lockPendingBatch(100)).isEmpty(),
        "a delivered event must not be published twice");
  }

  @Test
  @DisplayName("the payload leaves as the database rendered it, not re-serialised by a Java mapper")
  void relaysThePayloadAsStored() {
    outboxService.append("WALLET", aggregateId, "evt.usage.recorded", new Sample("video", 360));

    OutboxMessage event = mine(outboxService.lockPendingBatch(100)).get(0);

    // JSONB normalises key order and spacing, so the assertion is on content rather than layout —
    // what matters is that the relay never re-runs the event through a mapper whose shape may have
    // moved on since the event was recorded.
    String payload = new String(event.body(), StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"capability\""));
    assertTrue(payload.contains("video"));
    assertTrue(payload.contains("360"));
  }

  @Test
  @DisplayName("a failed publish backs the row off instead of spinning on it")
  void failureBacksOff() {
    outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.changed", "{}");
    OutboxMessage event = mine(outboxService.lockPendingBatch(100)).get(0);

    outboxService.recordFailure(event.id(), event.attempts());

    assertTrue(
        mine(outboxService.lockPendingBatch(100)).isEmpty(),
        "a backed-off row is not due yet, so the next poll skips it");
    Integer attempts =
        jdbcTemplate.queryForObject(
            "SELECT attempts FROM billing_outbox WHERE id = ?", Integer.class, event.id());
    assertEquals(1, attempts);
  }

  @Test
  @DisplayName("a due row returns after its back-off elapses")
  void returnsAfterBackoffElapses() {
    outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.changed", "{}");
    OutboxMessage event = mine(outboxService.lockPendingBatch(100)).get(0);
    outboxService.recordFailure(event.id(), event.attempts());

    jdbcTemplate.update(
        "UPDATE billing_outbox SET next_attempt_at = now() - INTERVAL '1 minute' WHERE id = ?",
        event.id());

    assertEquals(1, mine(outboxService.lockPendingBatch(100)).size());
  }

  @Test
  @DisplayName("housekeeping drops delivered rows and keeps undelivered ones as evidence")
  void purgeKeepsPendingRows() {
    outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.changed", "{}");
    OutboxMessage delivered = mine(outboxService.lockPendingBatch(100)).get(0);
    outboxService.markPublished(delivered.id());
    jdbcTemplate.update(
        "UPDATE billing_outbox SET published_at = now() - INTERVAL '30 days' WHERE id = ?",
        delivered.id());

    outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.canceled", "{}");

    long purged = outboxService.purgePublishedBefore(Instant.now().minus(7, ChronoUnit.DAYS));

    assertTrue(purged >= 1);
    assertFalse(
        mine(outboxService.lockPendingBatch(100)).isEmpty(),
        "an undelivered event survives housekeeping — it is evidence of a problem");
  }

  @Test
  @DisplayName("the batch size is honoured, so one poll cannot claim an unbounded queue")
  void respectsTheBatchSize() {
    for (int i = 0; i < 5; i++) {
      outboxService.append("SUBSCRIPTION", aggregateId, "evt.subscription.changed", "{}");
    }

    assertEquals(2, outboxService.lockPendingBatch(2).size());
  }

  /** A payload with stable field names, so the verbatim-relay assertion is readable. */
  record Sample(String capability, int credits) {}

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
    OutboxService outboxService(JdbcTemplate jdbcTemplate) {
      return new OutboxService(jdbcTemplate, new ObjectMapper());
    }
  }
}
