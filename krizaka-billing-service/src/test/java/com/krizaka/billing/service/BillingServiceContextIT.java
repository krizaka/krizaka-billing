package com.krizaka.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.test.container.ServiceRoles;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.krizaka.test.sql.InitDb;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The whole billing service, started the way the dev environment runs it (its own {@code
 * application.yml}, the datasource built from {@code krizaka.billing-service.datasource.*}, the real
 * tables and seed of {@code infra/initdb/70-billing.sql}, the shared HS256 secret) and its HTTP
 * chain: 401 without a token, 403 for a user on the admin surface, the seeded plans for a user, and
 * an admin crediting a wallet that the user then reads. A bean or filter change that stops the
 * service from starting fails here, not in the deployment (krizaka-users#18).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "krizaka.messaging.outbox.enabled=false",
      "krizaka.messaging.consumer.enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "management.health.rabbit.enabled=false",
      "management.tracing.enabled=false"
    })
class BillingServiceContextIT {

  private static final String SECRET = "orazaka-local-dev-identity-jwt-secret-256bit!";
  private static final HttpClient HTTP = HttpClient.newHttpClient();

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

  static {
    if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
      System.setProperty("api.version", "1.43");
    }
    POSTGRES.start();
    ServiceRoles.assignPassword(POSTGRES, "krizaka_billing", "krizaka_billing_pass");
  }

  @LocalServerPort int port;

  @DynamicPropertySource
  static void connection(DynamicPropertyRegistry registry) {
    registry.add(
        "krizaka.billing-service.datasource.url",
        () ->
            "jdbc:postgresql://%s:%d/krizaka_billing_db"
                .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432)));
    registry.add("krizaka.billing-service.datasource.username", () -> "krizaka_billing");
    registry.add("krizaka.billing-service.datasource.password", () -> "krizaka_billing_pass");
    registry.add("krizaka.security.jwt.secret", () -> SECRET);
  }

  private static String token(String subject, List<String> roles) {
    var key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    var claims =
        JwtClaimsSet.builder()
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .claim("roles", roles)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private HttpResponse<String> call(String method, String path, String token, String body)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theServiceStartsAsDeployedAndAnAdminCanCreditAWallet() throws Exception {
    String user = token("qa-user-1", List.of("ROLE_USER"));
    String admin = token("qa-admin-1", List.of("ROLE_USER", "ROLE_ADMIN"));

    assertThat(call("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
    assertThat(call("GET", "/api/v1/billing/plans", null, null).statusCode()).isEqualTo(401);

    var plans = call("GET", "/api/v1/billing/plans", user, null);
    assertThat(plans.statusCode()).isEqualTo(200);
    assertThat(plans.body()).contains("\"free\"");

    var forbidden =
        call(
            "POST",
            "/api/v1/billing/wallets/qa-user-1/adjustments",
            user,
            "{\"bucket\":\"GRANTED\",\"amount\":1000,\"reason\":\"test\"}");
    assertThat(forbidden.statusCode()).isEqualTo(403);

    var credited =
        call(
            "POST",
            "/api/v1/billing/wallets/qa-user-1/adjustments",
            admin,
            "{\"bucket\":\"GRANTED\",\"amount\":1000,\"reason\":\"QA test credits\"}");
    assertThat(credited.statusCode()).isEqualTo(200);

    var wallet = call("GET", "/api/v1/billing/wallets/me", user, null);
    assertThat(wallet.statusCode()).isEqualTo(200);
    assertThat(wallet.body()).contains("1000");
  }
}
