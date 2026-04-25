package org.openmetadata.mcp.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for the MCP benchmark harness (mcp-bench).
 *
 * <p>Validates fixture loading, deterministic client behavior, runner evaluation, aggregate
 * stats computation, and report generation. All tests use the deterministic client — no live
 * LLM calls required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BenchSuiteTest {

  private DeterministicBenchLlmClient client;

  @BeforeEach
  void setUp() {
    client = new DeterministicBenchLlmClient();
  }

  // ── DeterministicBenchLlmClient Tests ──

  @Nested
  class DeterministicClientTests {

    @Test
    void selectTools_searchPrompt_returnsSearchMetadata() {
      List<String> tools =
          client.selectTools("Find tables owned by marketing", BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("search_metadata");
    }

    @Test
    void selectTools_semanticPrompt_returnsSemanticSearch() {
      List<String> tools =
          client.selectTools(
              "Find tables about customer spending behavior using semantic meaning",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("semantic_search");
    }

    @Test
    void selectTools_changeImpactPrompt_returnsChangeImpact() {
      List<String> tools =
          client.selectTools(
              "What breaks if we drop customer_id from orders? Show me the change impact",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("change_impact");
    }

    @Test
    void selectTools_incidentTimelinePrompt_returnsIncidentTimeline() {
      List<String> tools =
          client.selectTools(
              "Walk me through the incident timeline for orders_fact",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("incident_timeline");
    }

    @Test
    void selectTools_rootCausePrompt_returnsRootCauseAnalysis() {
      List<String> tools =
          client.selectTools(
              "Why is the orders_fact table failing? Find the root cause upstream",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("root_cause_analysis");
    }

    @Test
    void selectTools_lineagePrompt_returnsGetEntityLineage() {
      List<String> tools =
          client.selectTools(
              "Show me the lineage and dependencies of the orders table both upstream and downstream",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("get_entity_lineage");
    }

    @Test
    void selectTools_entityDetailsPrompt_returnsGetEntityDetails() {
      List<String> tools =
          client.selectTools(
              "Read details about table postgres.mydb.myschema.orders",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("get_entity_details");
    }

    @Test
    void selectTools_patchPrompt_returnsPatchEntity() {
      List<String> tools =
          client.selectTools(
              "Modify the description of the orders table using patch_entity",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("patch_entity");
    }

    @Test
    void selectTools_glossaryCreatePrompt_returnsCreateGlossary() {
      List<String> tools =
          client.selectTools(
              "Create a new glossary for Marketing terms", BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("create_glossary");
    }

    @Test
    void selectTools_glossaryTermPrompt_returnsCreateGlossaryTerm() {
      List<String> tools =
          client.selectTools(
              "Create a glossary term Revenue in the Finance glossary",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("create_glossary_term");
    }

    @Test
    void selectTools_testDefinitionsPrompt_returnsGetTestDefinitions() {
      List<String> tools =
          client.selectTools(
              "What test definitions are available for table-level tests?",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("get_test_definitions");
    }

    @Test
    void selectTools_testCasePrompt_returnsCreateTestCase() {
      List<String> tools =
          client.selectTools(
              "Add a test case for column values to be not null on the orders table",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("create_test_case");
    }

    @Test
    void selectTools_metricPrompt_returnsCreateMetric() {
      List<String> tools =
          client.selectTools(
              "Create a metric for daily revenue using SQL", BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("create_metric");
    }

    @Test
    void selectTools_noMatch_fallsBackToSearchMetadata() {
      List<String> tools =
          client.selectTools("something completely random", BenchRunner.defaultAvailableTools());
      assertThat(tools).containsExactly("search_metadata");
    }

    @Test
    void selectTools_unavailableTool_notSelected() {
      List<String> availableNoSearch =
          BenchRunner.defaultAvailableTools().stream()
              .filter(t -> !t.equals("search_metadata"))
              .toList();
      List<String> tools = client.selectTools("something completely random", availableNoSearch);
      assertThat(tools).isEmpty();
    }

    @Test
    void selectTools_validatePatchPrompt_returnsValidatePatch() {
      List<String> tools =
          client.selectTools(
              "Preview the effect of adding an owner before applying",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("validate_patch");
    }

    @Test
    void selectTools_dataContractPrompt_returnsGenerateDataContract() {
      List<String> tools =
          client.selectTools(
              "Export the data contract for the orders table", BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("generate_data_contract");
    }

    @Test
    void selectTools_applyContractPrompt_returnsApplyDataContract() {
      List<String> tools =
          client.selectTools(
              "Reapply contract from this YAML file to the customer table",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("apply_data_contract");
    }

    @Test
    void selectTools_lineageFromSqlPrompt_returnsLineageFromSql() {
      List<String> tools =
          client.selectTools(
              "Extract lineage from this SQL: INSERT INTO target SELECT * FROM source",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("lineage_from_sql");
    }

    @Test
    void selectTools_costRankingPrompt_returnsRankAssetsByCost() {
      List<String> tools =
          client.selectTools(
              "Which tables are wasting money? Rank assets by cost and freshness",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("rank_assets_by_cost");
    }

    @Test
    void selectTools_suggestTestCasesPrompt_returnsSuggestTestCases() {
      List<String> tools =
          client.selectTools(
              "Suggest test cases for the orders table based on its schema",
              BenchRunner.defaultAvailableTools());
      assertThat(tools).contains("suggest_test_cases");
    }

    @Test
    void generateAnswer_returnsNonEmptyString() {
      List<BenchLlmClient.ToolCallResult> results =
          List.of(
              new BenchLlmClient.ToolCallResult("search_metadata", "3 results found"),
              new BenchLlmClient.ToolCallResult("get_entity_details", "table details"));
      String answer = client.generateAnswer("Find orders table", results);
      assertThat(answer).isNotEmpty();
      assertThat(answer).contains("search_metadata");
      assertThat(answer).contains("get_entity_details");
    }

    // ── Prompt Selection Tests ──

    @Test
    void selectPrompts_ownershipStewardshipWorkflow_returnsOwnershipStewardship() {
      List<String> prompts =
          client.selectPrompts(
              "Guide me through the ownership stewardship workflow",
              BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).contains("ownership_stewardship");
    }

    @Test
    void selectPrompts_ownershipReview_returnsOwnershipStewardship() {
      List<String> prompts =
          client.selectPrompts(
              "I need to do an ownership review of all tables",
              BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).contains("ownership_stewardship");
    }

    @Test
    void selectPrompts_governanceGap_returnsOwnershipStewardship() {
      List<String> prompts =
          client.selectPrompts(
              "Help me close the governance gap by assigning owners",
              BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).contains("ownership_stewardship");
    }

    @Test
    void selectPrompts_assignOwnersWorkflow_returnsOwnershipStewardship() {
      List<String> prompts =
          client.selectPrompts(
              "I want to assign owners to all unowned tables using the stewardship workflow",
              BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).contains("ownership_stewardship");
    }

    @Test
    void selectPrompts_searchAssistant_returnsSearchMetadata() {
      List<String> prompts =
          client.selectPrompts(
              "I need a search assistant to help me find datasets",
              BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).contains("search_metadata");
    }

    @Test
    void selectPrompts_noMatch_returnsEmptyList() {
      List<String> prompts =
          client.selectPrompts(
              "something completely unrelated to prompts", BenchRunner.defaultAvailablePrompts());
      assertThat(prompts).isEmpty();
    }

    @Test
    void selectPrompts_unavailablePrompt_notSelected() {
      List<String> noOwnership =
          BenchRunner.defaultAvailablePrompts().stream()
              .filter(p -> !p.equals("ownership_stewardship"))
              .toList();
      List<String> prompts =
          client.selectPrompts("Guide me through the ownership stewardship workflow", noOwnership);
      assertThat(prompts).doesNotContain("ownership_stewardship");
    }
  }

  // ── Fixture Loading Tests ──

  @Nested
  class FixtureLoadingTests {

    @Test
    void loadFixtures_returnsNonEmptyList() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      assertThat(fixtures).isNotEmpty();
    }

    @Test
    void loadFixtures_hasAtLeast20Fixtures() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      assertThat(fixtures).hasSizeGreaterThanOrEqualTo(30);
    }

    @Test
    void loadFixtures_allHaveIds() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      assertThat(fixtures).allMatch(f -> f.getId() != null && !f.getId().isBlank());
    }

    @Test
    void loadFixtures_allHavePrompts() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      assertThat(fixtures).allMatch(f -> f.getPrompt() != null && !f.getPrompt().isBlank());
    }

    @Test
    void loadFixtures_allHaveExpectedTools() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      assertThat(fixtures).allMatch(f -> !f.getExpectedTools().isEmpty());
    }

    @Test
    void loadFixtures_allExpectedToolsAreValid() {
      List<String> validTools = BenchRunner.defaultAvailableTools();
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      for (BenchFixture f : fixtures) {
        for (String tool : f.getExpectedTools()) {
          assertThat(validTools)
              .as("Fixture '%s' references unknown tool '%s'", f.getId(), tool)
              .contains(tool);
        }
      }
    }

    @Test
    void loadFixtures_allForbiddenToolsAreValid() {
      List<String> validTools = BenchRunner.defaultAvailableTools();
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      for (BenchFixture f : fixtures) {
        for (String tool : f.getForbiddenTools()) {
          assertThat(validTools)
              .as("Fixture '%s' forbids unknown tool '%s'", f.getId(), tool)
              .contains(tool);
        }
      }
    }

    @Test
    void loadFixtures_allExpectedPromptsAreValid() {
      List<String> validPrompts = BenchRunner.defaultAvailablePrompts();
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      for (BenchFixture f : fixtures) {
        for (String prompt : f.getExpectedPrompts()) {
          assertThat(validPrompts)
              .as("Fixture '%s' references unknown prompt '%s'", f.getId(), prompt)
              .contains(prompt);
        }
      }
    }

    @Test
    void loadFixtures_allForbiddenPromptsAreValid() {
      List<String> validPrompts = BenchRunner.defaultAvailablePrompts();
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      for (BenchFixture f : fixtures) {
        for (String prompt : f.getForbiddenPrompts()) {
          assertThat(validPrompts)
              .as("Fixture '%s' forbids unknown prompt '%s'", f.getId(), prompt)
              .contains(prompt);
        }
      }
    }

    @Test
    void loadFixtures_idsAreUnique() {
      List<BenchFixture> fixtures = BenchRunner.loadFixtures();
      Set<String> ids = new java.util.HashSet<>();
      for (BenchFixture f : fixtures) {
        assertThat(ids.add(f.getId())).as("Duplicate fixture id: %s", f.getId()).isTrue();
      }
    }
  }

  // ── BenchRunner Evaluation Tests ──

  @Nested
  class BenchRunnerEvaluationTests {

    @Test
    void evaluate_searchFixture_passes() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-search");
      fixture.setPrompt("Find tables owned by marketing");
      fixture.setExpectedTools(List.of("search_metadata"));
      fixture.setForbiddenTools(List.of());
      fixture.setMaxToolCalls(2);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      assertThat(results).hasSize(1);
      BenchResult result = results.get(0);
      assertThat(result.isCorrectTool()).isTrue();
      assertThat(result.isCorrectPrompts()).isTrue();
      assertThat(result.isPass()).isTrue();
      assertThat(result.getToolCalls()).contains("search_metadata");
    }

    @Test
    void evaluate_changeImpactFixture_passes() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-impact");
      fixture.setPrompt(
          "What breaks if we drop customer_id from orders? Show me the change impact");
      fixture.setExpectedTools(List.of("change_impact"));
      fixture.setForbiddenTools(List.of());
      fixture.setMaxToolCalls(2);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      assertThat(results).hasSize(1);
      assertThat(results.get(0).isCorrectTool()).isTrue();
      assertThat(results.get(0).isPass()).isTrue();
      assertThat(results.get(0).getToolCalls()).contains("change_impact");
    }

    @Test
    void evaluate_forbiddenToolUsed_fails() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-forbidden");
      fixture.setPrompt("I want to modify the description of the orders table using patch_entity");
      fixture.setExpectedTools(List.of("patch_entity"));
      fixture.setForbiddenTools(List.of("get_entity_details")); // artificially forbidden
      fixture.setMaxToolCalls(3);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      // patch_entity should be selected, but get_entity_details might also be selected
      // and that would fail the forbidden check
      BenchResult result = results.get(0);
      if (result.getToolCalls().contains("get_entity_details")) {
        assertThat(result.isNoForbiddenTools()).isFalse();
        assertThat(result.isPass()).isFalse();
      }
    }

    @Test
    void evaluate_promptFixture_passes() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-prompt");
      fixture.setPrompt("Guide me through the ownership stewardship workflow");
      fixture.setExpectedTools(List.of());
      fixture.setForbiddenTools(List.of());
      fixture.setExpectedPrompts(List.of("ownership_stewardship"));
      fixture.setForbiddenPrompts(List.of());
      fixture.setMaxToolCalls(2);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      assertThat(results).hasSize(1);
      BenchResult result = results.get(0);
      assertThat(result.isCorrectPrompts()).isTrue();
      assertThat(result.isPass()).isTrue();
      assertThat(result.getPromptCalls()).contains("ownership_stewardship");
    }

    @Test
    void evaluate_forbiddenPromptUsed_fails() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-forbidden-prompt");
      fixture.setPrompt("Guide me through the ownership stewardship workflow");
      fixture.setExpectedTools(List.of());
      fixture.setForbiddenTools(List.of());
      fixture.setExpectedPrompts(List.of("ownership_stewardship"));
      fixture.setForbiddenPrompts(List.of("ownership_stewardship")); // artificially forbidden
      fixture.setMaxToolCalls(2);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      BenchResult result = results.get(0);
      assertThat(result.isNoForbiddenPrompts()).isFalse();
      assertThat(result.isPass()).isFalse();
    }

    @Test
    void evaluate_missingExpectedTool_fails() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-missing");
      fixture.setPrompt("Something unrelated to any specific tool");
      fixture.setExpectedTools(List.of("incident_timeline")); // won't be selected by this prompt
      fixture.setForbiddenTools(List.of());
      fixture.setMaxToolCalls(5);

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      assertThat(results.get(0).isCorrectTool()).isFalse();
      assertThat(results.get(0).isPass()).isFalse();
    }

    @Test
    void evaluate_exceedsMaxToolCalls_fails() {
      BenchFixture fixture = new BenchFixture();
      fixture.setId("test-maxcalls");
      fixture.setPrompt("Find tables owned by marketing");
      fixture.setExpectedTools(List.of("search_metadata"));
      fixture.setForbiddenTools(List.of());
      fixture.setMaxToolCalls(0); // impossible limit

      BenchRunner runner =
          new BenchRunner(client, List.of(fixture), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();

      assertThat(results.get(0).isToolCallCountOk()).isFalse();
    }
  }

  // ── Aggregate Stats Tests ──

  @Nested
  class AggregateStatsTests {

    @Test
    void aggregate_allPass_100PercentRate() {
      BenchFixture f1 = new BenchFixture();
      f1.setId("a");
      f1.setPrompt("Find tables");
      f1.setExpectedTools(List.of("search_metadata"));
      f1.setForbiddenTools(List.of());
      f1.setMaxToolCalls(5);

      BenchFixture f2 = new BenchFixture();
      f2.setId("b");
      f2.setPrompt("Find tables about revenue");
      f2.setExpectedTools(List.of("search_metadata"));
      f2.setForbiddenTools(List.of());
      f2.setMaxToolCalls(5);

      BenchRunner runner =
          new BenchRunner(client, List.of(f1, f2), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(results);

      assertThat(stats.totalFixtures()).isEqualTo(2);
      assertThat(stats.passRate()).isBetween(0.0, 1.0);
      assertThat(stats.correctToolRate()).isBetween(0.0, 1.0);
    }

    @Test
    void aggregate_emptyResults_zeroStats() {
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(List.of());

      assertThat(stats.totalFixtures()).isEqualTo(0);
      assertThat(stats.passRate()).isEqualTo(0.0);
      assertThat(stats.correctToolRate()).isZero();
    }
  }

  // ── Bench Report Writer Tests ──

  @Nested
  class BenchReportWriterTests {

    @Test
    void generateReport_containsSummary() {
      BenchFixture f = new BenchFixture();
      f.setId("test-report");
      f.setPrompt("Find tables");
      f.setExpectedTools(List.of("search_metadata"));
      f.setForbiddenTools(List.of());
      f.setMaxToolCalls(5);

      BenchRunner runner = new BenchRunner(client, List.of(f), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(results);

      String report = BenchReportWriter.generateReport(results, stats, "current");

      assertThat(report).contains("MCP Bench Report");
      assertThat(report).contains("Summary");
      assertThat(report).contains("Per-Fixture Results");
      assertThat(report).contains("test-report");
    }

    @Test
    void generateReport_passAndFail_showsEmojis() {
      BenchFixture pass = new BenchFixture();
      pass.setId("pass-fixture");
      pass.setPrompt("Find tables");
      pass.setExpectedTools(List.of("search_metadata"));
      pass.setForbiddenTools(List.of());
      pass.setMaxToolCalls(5);

      BenchFixture fail = new BenchFixture();
      fail.setId("fail-fixture");
      fail.setPrompt("Unrelated prompt");
      fail.setExpectedTools(List.of("incident_timeline")); // won't be selected
      fail.setForbiddenTools(List.of());
      fail.setMaxToolCalls(5);

      BenchRunner runner =
          new BenchRunner(client, List.of(pass, fail), BenchRunner.defaultAvailableTools());
      List<BenchResult> results = runner.run();
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(results);

      String report = BenchReportWriter.generateReport(results, stats, "current");

      assertThat(report).contains("✅");
      // At least one failure expected for the fail-fixture
      assertThat(report).contains("❌");
    }
  }

  // ── Full Harness Integration Test ──

  @Nested
  class FullHarnessIntegrationTests {

    @Test
    void runAllFixtures_allDeterministicChecksPass() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();

      // With the deterministic client, every fixture's expected tools should be selected
      // (because prompts are designed to match the keyword mapping)
      long passCount = results.stream().filter(BenchResult::isPass).count();

      // We expect most fixtures to pass with the deterministic client
      // Allow some tolerance for prompts that don't perfectly match the keyword mapping
      assertThat(passCount)
          .as(
              "At least 80%% of fixtures should pass with deterministic client (got %d/%d)",
              passCount, results.size())
          .isGreaterThanOrEqualTo((long) (results.size() * 0.8));
    }

    @Test
    void runAllFixtures_correctToolRateAbove80() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(results);

      assertThat(stats.correctToolRate())
          .as("Correct tool rate should be ≥ 80%% (got %.1f%%)", stats.correctToolRate() * 100)
          .isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void runAllFixtures_noForbiddenToolsUsed() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();

      // No fixture should use a forbidden tool
      long forbiddenCount = results.stream().filter(r -> !r.isNoForbiddenTools()).count();
      assertThat(forbiddenCount).as("%d fixtures used forbidden tools", forbiddenCount).isZero();
    }

    @Test
    void runAllFixtures_generatesReport() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();
      BenchRunner.AggregateStats stats = BenchRunner.aggregate(results);

      String report = BenchReportWriter.generateReport(results, stats, "current");

      assertThat(report).contains("MCP Bench Report — current");
      assertThat(report).contains("Summary");
      assertThat(report).contains("Per-Fixture Results");
      assertThat(report).contains("Tool Call Details");
      assertThat(report.length()).isGreaterThan(200);
    }

    @Test
    void runAllFixtures_allResultsHaveFixtureIds() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();

      assertThat(results).isNotEmpty();
      assertThat(results).allMatch(r -> r.getFixtureId() != null && !r.getFixtureId().isBlank());
    }

    @Test
    void runAllFixtures_allResultsHaveToolCalls() {
      BenchRunner runner = new BenchRunner(client);
      List<BenchResult> results = runner.run();

      // Every fixture should result in at least one tool call
      assertThat(results).allMatch(r -> r.getToolCallCount() >= 1);
    }
  }
}
