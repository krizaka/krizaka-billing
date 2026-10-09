package com.krizaka.billing.service.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.krizaka.test.architecture.CodeRules;
import com.krizaka.test.architecture.ConfigBindingRules;
import com.krizaka.test.architecture.SourceRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Governance guardrails for krizaka-billing-service.
 *
 * <p>Beyond the shared rules, one billing-specific fitness function: <b>no plan key may appear as a
 * literal in Java</b>. A hardcoded {@code "premium"} means adding a plan requires a deploy —
 * exactly the failure the product brief asks to avoid, and the reason plans are rows (design §15).
 */
class BillingServiceGovernanceTest {

  /**
   * The rules below are repository-wide, not module-scoped: the worst pack coupling lives in the
   * Python media worker, which is in no Maven reactor, so a per-module scan could never see it.
   */
  private static final String BASE_PACKAGE = "com.krizaka.billing.service";

  /** The seeded plan keys. None of these may appear as a literal in production Java. */
  private static final List<String> PLAN_KEYS = List.of("free", "premium", "ultimate");

  private static JavaClasses productionClasses;

  @BeforeAll
  static void importClasses() {
    productionClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-103] one top-level type per file")
  void oneTopLevelClassPerFile() {
    CodeRules.assertOneTopLevelClassPerFile(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[ERR-104] no redundant Orazaka prefix")
  void noRedundantPrefix() {
    CodeRules.assertNoProductPrefix(productionClasses, BASE_PACKAGE, "Krizaka");
  }

  @Test
  @DisplayName("[ERR-129] application/service holds only *Service types")
  void servicePackageHoldsOnlyServices() {
    CodeRules.assertServicePackageOnlyServices(
        productionClasses, BASE_PACKAGE + ".application.service");
  }

  @Test
  @DisplayName("[ERR-130] domain/model carries no transport DTOs")
  void domainHasNoTransportDtos() {
    CodeRules.assertDomainHasNoTransportDtos(productionClasses, BASE_PACKAGE + ".domain");
  }

  @Test
  @DisplayName("[AGENTS §4] no field injection")
  void noFieldInjection() {
    CodeRules.assertNoFieldInjection(productionClasses, BASE_PACKAGE);
  }

  @Test
  @DisplayName("[AGENTS §4] no System.out / System.err")
  void noStandardStreams() {
    CodeRules.assertNoStandardStreams(productionClasses);
  }

  @Test
  @DisplayName("[design §15] no plan key literal in Java — a plan is a row, never a constant")
  void noPlanKeyLiteralInJava() throws IOException {
    Path sourceRoot = Path.of("src", "main", "java");
    List<String> violations = new ArrayList<>();
    try (Stream<Path> sources = Files.walk(sourceRoot)) {
      for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        // Strip comments first: the rule is about code. Prose that *names* a plan key to explain
        // why it must not be hardcoded is exactly the documentation we want, not a violation.
        String body =
            Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        for (String planKey : PLAN_KEYS) {
          if (body.contains('"' + planKey + '"')) {
            violations.add(file.getFileName() + " hardcodes the plan key \"" + planKey + '"');
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "Plan keys are rows in billing_plan, never Java literals — adding a plan must not need"
                + " a deploy (design §15):\n  "
                + String.join("\n  ", violations));
  }

  @Test
  @DisplayName("[AGENTS.md §4] requests run on virtual threads")
  void requestsRunOnVirtualThreads() {
    SourceRules.assertVirtualThreadsEnabled(Path.of(System.getProperty("user.dir")));
  }

  @Test
  @DisplayName(
      "[CFG-001] every type the configuration binder builds has a constructor it can choose")
  void configurationBindsUnambiguously() {
    ConfigBindingRules.assertConfigurationBindsUnambiguously("com.krizaka");
    ConfigBindingRules.assertInjectableComponentsHaveOneConstructor("com.krizaka");
  }

  @Test
  @DisplayName("[ERR-113] No Environment injection in production beans")
  void noEnvironmentInjection() {
    SourceRules.assertNoEnvironmentInjection(Path.of("src", "main", "java"));
  }

  @Test
  @DisplayName("[ERR-102] Billing depends on no product")
  void dependsOnNoProduct() {
    com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
        .that()
        .resideInAPackage("com.krizaka.billing..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.orazaka..", "com.orochia..")
        .because("a Krizaka building block depends on no product — products depend on it")
        .check(productionClasses);
  }
}
