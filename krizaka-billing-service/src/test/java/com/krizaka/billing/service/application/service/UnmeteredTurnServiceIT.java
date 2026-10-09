package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.UnmeteredTurn;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The record of an unmetered turn, against the <b>real</b> {@code infra/initdb/70-billing.sql}
 * (ADR-064): the insert and the table are written in two files, and only a database can say they
 * agree.
 */
class UnmeteredTurnServiceIT {

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
              MountableFile.forHostPath(
                  InitDb.locate(Path.of(System.getProperty("user.dir"))).resolve("70-billing.sql")),
              "/docker-entrypoint-initdb.d/70-billing.sql");

  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void startContainer() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, BILLING_ROLE, BILLING_PASSWORD);
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + BILLING_DB);
    dataSource.setUsername(BILLING_ROLE);
    dataSource.setPassword(BILLING_PASSWORD);
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @AfterAll
  static void stopContainer() {
    if (dataSource != null) {
      dataSource.close();
    }
    POSTGRES.stop();
  }

  @Test
  @DisplayName("an unmetered turn lands in the reconciliation table as the serving service saw it")
  void recordsTheTurnForReconciliation() {
    String actor = UUID.randomUUID().toString();
    Instant attempted = Instant.parse("2026-09-15T12:00:00Z");

    new UnmeteredTurnService(jdbcTemplate)
        .record(
            new UnmeteredTurn(
                actor,
                BillableCapability.CHAT,
                "conv-64",
                "billing unreachable: RestClientException",
                attempted));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT capability, correlation_id, reason, occurred_at FROM unmetered_turn"
                + " WHERE actor_id = ?",
            actor);
    assertEquals("CHAT", row.get("capability"));
    assertEquals("conv-64", row.get("correlation_id"));
    assertEquals("billing unreachable: RestClientException", row.get("reason"));
    assertEquals(
        attempted, ((java.sql.Timestamp) row.get("occurred_at")).toInstant(), "when it was served");
  }
}
