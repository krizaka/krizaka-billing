package com.krizaka.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configuration contract of the client: every {@code krizaka.billing.*} property is in the
 * metadata Spring Boot's configuration processor generates (so an IDE completes it), described for
 * the reader, with its default — and the reference java.krizaka.com renders, {@code
 * docs/configuration-metadata.json}, is that metadata. After a change to a property, regenerate it
 * with {@code ./mvnw verify -Dkrizaka.reference.write=true} and commit it.
 */
class ConfigurationReferenceTest {

  static final String ARTIFACT = "krizaka-billing-client";

  /** No default on purpose: a secret is never defaulted. */
  static final Set<String> WITHOUT_DEFAULT = Set.of("krizaka.billing.service-secret");

  static final Path METADATA =
      Path.of("target/classes/META-INF/spring-configuration-metadata.json");
  static final Path REFERENCE = Path.of("../docs/configuration-metadata.json");
  static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  @Test
  void everyPropertyIsDescribedForTheReaderWithItsDefault() {
    Map<String, String> seen = new HashMap<>();
    List<Map<String, Object>> properties = properties();
    assertThat(properties).isNotEmpty();
    for (Map<String, Object> property : properties) {
      String name = (String) property.get("name");
      String description = (String) property.get("description");
      assertThat(description).as(name + ": description").isNotBlank().endsWith(".");
      assertThat(Character.isUpperCase(description.charAt(0))).as(name + ": capital").isTrue();
      assertThat(description).as(name + ": Javadoc tags").doesNotContain("{@");
      assertThat(seen.put(description, name)).as(name + ": duplicated description").isNull();
      assertThat(property.containsKey("defaultValue"))
          .as(name + ": default shown in the metadata")
          .isEqualTo(!WITHOUT_DEFAULT.contains(name));
    }
  }

  @Test
  void theReferenceIsTheGeneratedMetadata() throws IOException {
    Map<String, Object> module = new LinkedHashMap<>();
    module.put("artifact", ARTIFACT);
    module.put("properties", properties());
    module.put("hints", List.of());
    Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("repository", "krizaka-billing");
    reference.put("modules", List.of(module));
    String generated = JSON.writeValueAsString(reference) + "\n";
    if (Boolean.getBoolean("krizaka.reference.write")) {
      Files.createDirectories(REFERENCE.getParent());
      Files.writeString(REFERENCE, generated);
    }
    assertThat(REFERENCE)
        .as("docs/configuration-metadata.json — run ./mvnw verify -Dkrizaka.reference.write=true")
        .isRegularFile();
    assertThat(Files.readString(REFERENCE)).isEqualTo(generated);
  }

  private static List<Map<String, Object>> properties() {
    assertThat(METADATA).as("the configuration processor did not run").isRegularFile();
    List<Map<String, Object>> properties = new ArrayList<>();
    for (JsonNode node : JSON.readTree(METADATA.toFile()).path("properties")) {
      Map<String, Object> property = new LinkedHashMap<>();
      property.put("name", node.path("name").asString());
      property.put("type", node.path("type").asString());
      property.put("description", node.path("description").asString(""));
      if (node.has("defaultValue")) {
        property.put("defaultValue", node.get("defaultValue"));
      }
      properties.add(property);
    }
    properties.sort(Comparator.comparing(p -> (String) p.get("name")));
    return properties;
  }
}
