package com.krizaka.billing.service.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.krizaka.billing.domain.model.BillableCapability;
import com.krizaka.billing.domain.model.BillableUnit;
import com.krizaka.test.sql.InitDb;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capability set is mirrored by hand in the schema (the {@code credit_hold.capability} comment)
 * and in the pricebook seed. These checks read {@code infra/initdb/70-billing.sql} and the dev
 * migration as text, so a capability added on one side and not the other fails here, with no
 * container.
 */
class BillingSchemaCapabilityTest {

  /** {@code (version, 'CAPABILITY', NULL | 'model', 'UNIT', credits, minimum, estimate)}. */
  private static final Pattern PRICEBOOK_ROW =
      Pattern.compile(
          "\\((\\d+),\\s*'([A-Z_]+)',\\s*(NULL|'[^']*'),\\s*'([A-Z_]+)',\\s*([\\d.]+),\\s*(\\d+),\\s*(\\d+)\\)");

  private static final String MIGRATION = "2026-10-25-pricebook-external-check-research.sql";

  private record Row(
      int version,
      String capability,
      String model,
      String unit,
      String credits,
      long minimum,
      long estimate) {}

  private static Path initDb() {
    return InitDb.locate(Path.of(System.getProperty("user.dir")));
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file);
  }

  private static List<Row> rows(String sql) {
    List<Row> rows = new ArrayList<>();
    Matcher matcher = PRICEBOOK_ROW.matcher(sql);
    while (matcher.find()) {
      String model = matcher.group(3);
      rows.add(
          new Row(
              Integer.parseInt(matcher.group(1)),
              matcher.group(2),
              "NULL".equals(model) ? null : model.substring(1, model.length() - 1),
              matcher.group(4),
              matcher.group(5),
              Long.parseLong(matcher.group(6)),
              Long.parseLong(matcher.group(7))));
    }
    return rows;
  }

  private static List<String> capabilityNames() {
    return Arrays.stream(BillableCapability.values()).map(Enum::name).toList();
  }

  @Test
  @DisplayName("the credit_hold capability comment lists every billable capability")
  void holdCommentListsEveryCapability() throws IOException {
    Matcher comment =
        Pattern.compile("capability\\s+VARCHAR\\(50\\)\\s+NOT NULL,\\s+--\\s+([A-Z_|]+)")
            .matcher(read(initDb().resolve("70-billing.sql")));

    assertThat(comment.find()).as("the credit_hold capability column keeps its comment").isTrue();
    assertThat(comment.group(1).split("\\|")).containsExactlyElementsOf(capabilityNames());
  }

  @Test
  @DisplayName("every seeded pricebook row names a billable capability and a billable unit")
  void everySeededRowIsBillable() throws IOException {
    List<Row> seeded = rows(read(initDb().resolve("70-billing.sql")));
    List<String> units = Arrays.stream(BillableUnit.values()).map(Enum::name).toList();

    assertThat(seeded).isNotEmpty();
    assertThat(seeded).allSatisfy(row -> assertThat(capabilityNames()).contains(row.capability()));
    assertThat(seeded).allSatisfy(row -> assertThat(units).contains(row.unit()));
  }

  @Test
  @DisplayName("an external check and a law-corpus read are priced per CALL in the live version")
  void externalCheckAndResearchAreSeeded() throws IOException {
    List<Row> seeded = rows(read(initDb().resolve("70-billing.sql")));

    assertThat(seeded)
        .contains(
            new Row(2, "EXTERNAL_CHECK", "mock", "CALL", "50.0000", 5, 50),
            new Row(2, "RESEARCH", "law-corpus", "CALL", "10.0000", 5, 10));
  }

  @Test
  @DisplayName("the dev migration seeds the same rows as a fresh database, idempotently")
  void migrationMatchesTheSeed() throws IOException {
    String migration = read(initDb().resolveSibling("migrations").resolve(MIGRATION));
    List<Row> fresh = rows(read(initDb().resolve("70-billing.sql")));

    List<Row> migrated = rows(migration);
    assertThat(migrated).isNotEmpty();
    assertThat(fresh).containsAll(migrated);
    assertThat(migration).contains("ON CONFLICT DO NOTHING");
  }
}
