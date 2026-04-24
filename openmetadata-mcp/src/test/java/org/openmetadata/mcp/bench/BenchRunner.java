package org.openmetadata.mcp.bench;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.LongSummaryStatistics;
import org.openmetadata.mcp.McpUtils;

/**
 * Core benchmark runner. Loads YAML fixtures, drives a {@link BenchLlmClient}, and collects
 * per-fixture and aggregate metrics.
 *
 * <p>Usage: {@code BenchRunner runner = new BenchRunner(client); List&lt;BenchResult&gt; results =
 * runner.run();}
 */
public class BenchRunner {

  private final BenchLlmClient client;
  private final List<BenchFixture> fixtures;
  private final List<String> availableTools;
  private final List<String> availablePrompts;

  public BenchRunner(BenchLlmClient client) {
    this(client, loadFixtures(), defaultAvailableTools(), defaultAvailablePrompts());
  }

  public BenchRunner(
      BenchLlmClient client, List<BenchFixture> fixtures, List<String> availableTools) {
    this(client, fixtures, availableTools, defaultAvailablePrompts());
  }

  public BenchRunner(
      BenchLlmClient client,
      List<BenchFixture> fixtures,
      List<String> availableTools,
      List<String> availablePrompts) {
    this.client = client;
    this.fixtures = fixtures;
    this.availableTools = availableTools;
    this.availablePrompts = availablePrompts;
  }

  /** Run all fixtures and return results. */
  public List<BenchResult> run() {
    List<BenchResult> results = new ArrayList<>();
    for (BenchFixture fixture : fixtures) {
      results.add(evaluate(fixture));
    }
    return results;
  }

  /** Evaluate a single fixture. */
  public BenchResult evaluate(BenchFixture fixture) {
    BenchResult result = new BenchResult(fixture.getId());
    long start = System.currentTimeMillis();

    // Step 1: Select tools
    List<String> selectedTools = client.selectTools(fixture.getPrompt(), availableTools);
    result.setToolCalls(selectedTools);
    result.setToolCallCount(selectedTools.size());

    // Step 1b: Select prompts (if the client supports prompt selection)
    List<String> selectedPrompts = client.selectPrompts(fixture.getPrompt(), availablePrompts);
    result.setPromptCalls(selectedPrompts);

    // Step 2: Generate answer (for deterministic client, this is synthetic)
    List<BenchLlmClient.ToolCallResult> callResults =
        selectedTools.stream()
            .map(t -> new BenchLlmClient.ToolCallResult(t, "result from " + t))
            .toList();
    String answer = client.generateAnswer(fixture.getPrompt(), callResults);

    long elapsed = System.currentTimeMillis() - start;
    result.setLatencyMs(elapsed);

    // Step 3: Check tool selection correctness
    boolean correctTool = true;
    for (String expected : fixture.getExpectedTools()) {
      if (!selectedTools.contains(expected)) {
        correctTool = false;
        result.addFailure("Missing expected tool: " + expected);
      }
    }
    result.setCorrectTool(correctTool);

    // Step 4: Check no forbidden tools
    boolean noForbidden = true;
    for (String forbidden : fixture.getForbiddenTools()) {
      if (selectedTools.contains(forbidden)) {
        noForbidden = false;
        result.addFailure("Used forbidden tool: " + forbidden);
      }
    }
    result.setNoForbiddenTools(noForbidden);

    // Step 4b: Check prompt selection correctness
    boolean correctPrompts = true;
    for (String expected : fixture.getExpectedPrompts()) {
      if (!selectedPrompts.contains(expected)) {
        correctPrompts = false;
        result.addFailure("Missing expected prompt: " + expected);
      }
    }
    result.setCorrectPrompts(correctPrompts);

    // Step 4c: Check no forbidden prompts
    boolean noForbiddenPrompts = true;
    for (String forbidden : fixture.getForbiddenPrompts()) {
      if (selectedPrompts.contains(forbidden)) {
        noForbiddenPrompts = false;
        result.addFailure("Used forbidden prompt: " + forbidden);
      }
    }
    result.setNoForbiddenPrompts(noForbiddenPrompts);

    // Step 5: Check tool call count
    boolean countOk = selectedTools.size() <= fixture.getMaxToolCalls();
    result.setToolCallCountOk(countOk);
    if (!countOk) {
      result.addFailure(
          "Tool call count " + selectedTools.size() + " exceeds max " + fixture.getMaxToolCalls());
    }

    // Step 6: Check answer contains gold substrings
    boolean answerOk = true;
    for (String gold : fixture.getGoldAnswerContains()) {
      if (!answer.toLowerCase().contains(gold.toLowerCase())) {
        // For deterministic client, answer correctness is best-effort:
        // check if any selected tool name or prompt name contains the gold substring
        boolean toolMatch =
            selectedTools.stream().anyMatch(t -> t.toLowerCase().contains(gold.toLowerCase()));
        boolean promptMatch =
            selectedPrompts.stream().anyMatch(p -> p.toLowerCase().contains(gold.toLowerCase()));
        if (!toolMatch && !promptMatch) {
          answerOk = false;
          result.addFailure("Answer missing gold substring: " + gold);
        }
      }
    }
    result.setAnswerCorrect(answerOk);

    return result;
  }

  /** Compute aggregate statistics across all results. */
  public static AggregateStats aggregate(List<BenchResult> results) {
    long passCount = results.stream().filter(BenchResult::isPass).count();
    double passRate = results.isEmpty() ? 0.0 : (double) passCount / results.size();

    LongSummaryStatistics latencyStats =
        results.stream().mapToLong(BenchResult::getLatencyMs).summaryStatistics();

    IntSummaryStatistics toolCountStats =
        results.stream().mapToInt(BenchResult::getToolCallCount).summaryStatistics();

    long correctToolCount = results.stream().filter(BenchResult::isCorrectTool).count();
    long answerCorrectCount = results.stream().filter(BenchResult::isAnswerCorrect).count();

    return new AggregateStats(
        results.size(),
        passCount,
        passRate,
        correctToolCount,
        results.isEmpty() ? 0.0 : (double) correctToolCount / results.size(),
        answerCorrectCount,
        results.isEmpty() ? 0 : (double) answerCorrectCount / results.size(),
        latencyStats.getMin(),
        latencyStats.getMax(),
        latencyStats.getAverage(),
        latencyStats.getCount() > 0 ? percentile(results, 95) : 0,
        toolCountStats.getMin(),
        toolCountStats.getMax(),
        toolCountStats.getAverage());
  }

  private static long percentile(List<BenchResult> results, int pct) {
    List<Long> sorted = results.stream().map(BenchResult::getLatencyMs).sorted().toList();
    int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
    return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, idx));
  }

  /** Load fixtures from classpath YAML files. */
  public static List<BenchFixture> loadFixtures() {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper(new YAMLFactory());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    List<BenchFixture> all = new ArrayList<>();
    try {
      // Load the consolidated fixtures file
      InputStream is =
          McpUtils.class.getClassLoader().getResourceAsStream("bench/fixtures/all-fixtures.yaml");
      if (is != null) {
        BenchFixtureList list = mapper.readValue(is, BenchFixtureList.class);
        all.addAll(list.getFixtures());
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to load bench fixtures", e);
    }
    return all;
  }

  /** Default list of available tool names from tools.json. */
  public static List<String> defaultAvailableTools() {
    return List.of(
        "search_metadata",
        "semantic_search",
        "get_entity_details",
        "get_entity_lineage",
        "create_lineage",
        "patch_entity",
        "root_cause_analysis",
        "change_impact",
        "incident_timeline",
        "get_test_definitions",
        "create_test_case",
        "create_glossary",
        "create_glossary_term",
        "create_metric",
        "find_unowned_assets",
        "suggest_owner_for",
        "draft_ownership_patch",
        "scan_governance_coverage",
        "validate_patch",
        "generate_data_contract",
        "apply_data_contract",
        "lineage_from_sql",
        "rank_assets_by_cost",
        "suggest_test_cases");
  }

  /** Default list of available prompt names from prompts.json. */
  public static List<String> defaultAvailablePrompts() {
    return List.of("search_metadata", "ownership_stewardship");
  }

  /** Wrapper for YAML list of fixtures. */
  public static class BenchFixtureList {
    private List<BenchFixture> fixtures = new ArrayList<>();

    public List<BenchFixture> getFixtures() {
      return fixtures;
    }

    public void setFixtures(List<BenchFixture> fixtures) {
      this.fixtures = fixtures;
    }
  }

  /** Aggregate statistics across all bench results. */
  public record AggregateStats(
      int totalFixtures,
      long passCount,
      double passRate,
      long correctToolCount,
      double correctToolRate,
      long answerCorrectCount,
      double answerCorrectRate,
      long minLatencyMs,
      long maxLatencyMs,
      double avgLatencyMs,
      long p95LatencyMs,
      int minToolCalls,
      int maxToolCalls,
      double avgToolCalls) {}
}
