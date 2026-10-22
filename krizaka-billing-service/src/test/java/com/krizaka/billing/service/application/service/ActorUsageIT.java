package com.krizaka.billing.service.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.billing.service.domain.model.ActorUsage;
import com.krizaka.test.container.ServiceRoles;
import com.krizaka.test.sql.InitDb;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.YearMonth;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

/**
 * A product's month, read against the <b>real</b> {@code infra/initdb/70-billing.sql}: only its own
 * actor, only the asked month, priced with the money anchor, on the plan in force.
 */
class ActorUsageIT {

  private static final String BILLING_DB = "krizaka_billing_db";
  private static final String BILLING_ROLE = "krizaka_billing";
  private static final String BILLING_PASSWORD = "krizaka_billing_pass";

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

  private static AnnotationConfigApplicationContext context;
  private static JdbcTemplate jdbc;
  private static ActorUsageService service;

  @BeforeAll
  static void start() {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, BILLING_ROLE, BILLING_PASSWORD);
    context = new AnnotationConfigApplicationContext(Wiring.class);
    jdbc = context.getBean(JdbcTemplate.class);
    service = context.getBean(ActorUsageService.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  private void usage(String actor, String capability, long credits, String when) {
    jdbc.update(
        "INSERT INTO usage_event (actor_id, capability, model_name, unit, quantity,"
            + " credits_charged, correlation_id, occurred_at)"
            + " VALUES (?, ?, 'm', 'OUTPUT_SECOND', 1, ?, ?, ?::timestamptz)",
        actor,
        capability,
        credits,
        UUID.randomUUID().toString(),
        when);
  }

  @Test
  @DisplayName("sums the actor's month by capability, priced, and ignores other actors and months")
  void monthOfOneActor() {
    String me = "me-" + UUID.randomUUID();
    String other = "other-" + UUID.randomUUID();
    usage(me, "CHAT", 100, "2026-10-03T10:00:00Z");
    usage(me, "CHAT", 50, "2026-10-31T23:59:59Z");
    usage(me, "VIDEO", 400, "2026-10-15T00:00:00Z");
    usage(me, "CHAT", 999, "2026-11-01T00:00:00Z"); // next month
    usage(me, "CHAT", 888, "2026-09-30T23:59:59Z"); // previous month
    usage(other, "CHAT", 777, "2026-10-10T00:00:00Z"); // another product

    ActorUsage u = service.forMonth(me, YearMonth.of(2026, 10));

    assertEquals(3, u.events());
    assertEquals(550, u.creditsCharged());
    assertEquals(2, u.byCapability().size());
    assertEquals("VIDEO", u.byCapability().get(0).capability()); // heaviest first
    assertEquals(400, u.byCapability().get(0).creditsCharged());
    assertEquals(550L * u.unitPriceMillicents(), u.costMillicents());
    assertTrue(u.unitPriceMillicents() > 0);
    assertEquals("CAD", u.currency());
  }

  @Test
  @DisplayName("an actor with no usage gets zeros, and the entry plan")
  void emptyMonth() {
    ActorUsage u = service.forMonth("nobody-" + UUID.randomUUID(), YearMonth.of(2026, 10));
    assertEquals(0, u.events());
    assertEquals(0, u.costCents());
    assertTrue(u.byCapability().isEmpty());
    assertTrue(!"unknown".equals(u.planKey()), "the seeded entry plan is resolved");
  }

  @Test
  @DisplayName("the subscribed plan wins over the entry plan")
  void subscribedPlan() {
    String me = "sub-" + UUID.randomUUID();
    String top =
        jdbc.queryForObject(
            "SELECT plan_key FROM billing_plan ORDER BY tier_rank DESC LIMIT 1", String.class);
    jdbc.update(
        "INSERT INTO billing_subscription (actor_id, plan_key, status, period_start, period_end)"
            + " VALUES (?, ?, 'ACTIVE', now(), now() + interval '30 days')",
        me,
        top);
    assertEquals(top, service.forMonth(me, YearMonth.of(2026, 10)).planKey());
  }

  @Configuration
  static class Wiring {
    @Bean
    DataSource dataSource() {
      HikariDataSource ds = new HikariDataSource();
      ds.setJdbcUrl(
          "jdbc:postgresql://"
              + POSTGRES.getHost()
              + ":"
              + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
              + "/"
              + BILLING_DB);
      ds.setUsername(BILLING_ROLE);
      ds.setPassword(BILLING_PASSWORD);
      ds.setDriverClassName("org.postgresql.Driver");
      return ds;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    ActorUsageService actorUsageService(JdbcTemplate jdbcTemplate) {
      BillingConfigurationService config =
          new BillingConfigurationService(
              jdbcTemplate, new BillingVersionHistoryService(jdbcTemplate, new ObjectMapper()));
      return new ActorUsageService(jdbcTemplate, config);
    }
  }
}
