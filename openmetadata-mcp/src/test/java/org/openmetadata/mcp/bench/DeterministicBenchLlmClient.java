package org.openmetadata.mcp.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deterministic {@link BenchLlmClient} for CI hermetic testing.
 *
 * <p>Uses keyword-to-tool mapping to select tools based on prompt content, ensuring
 * reproducible results without requiring a live LLM. The mapping is derived from
 * the tool descriptions in {@code tools.json}.
 */
public class DeterministicBenchLlmClient implements BenchLlmClient {

  /** Keyword → tool mapping. First match wins; order matters. */
  private static final LinkedHashMap<Set<String>, String> KEYWORD_TOOL_MAP = new LinkedHashMap<>();

  /** Keyword → prompt mapping for MCP prompt selection. First match wins; order matters. */
  private static final LinkedHashMap<Set<String>, String> KEYWORD_PROMPT_MAP =
      new LinkedHashMap<>();

  static {
    // Composite tools first (more specific keywords)
    KEYWORD_TOOL_MAP.put(Set.of("break", "change", "drop", "impact"), "change_impact");
    KEYWORD_TOOL_MAP.put(Set.of("incident", "timeline", "walk me through"), "incident_timeline");
    KEYWORD_TOOL_MAP.put(Set.of("root cause", "upstream failure", "why is"), "root_cause_analysis");

    // Stewardship Copilot tools (E5) — must come before generic search/find
    KEYWORD_TOOL_MAP.put(
        Set.of("unowned", "no owner", "without owner", "ownership gap"), "find_unowned_assets");
    KEYWORD_TOOL_MAP.put(
        Set.of("suggest owner", "best owner", "who should own", "recommend owner"),
        "suggest_owner_for");
    KEYWORD_TOOL_MAP.put(
        Set.of("draft ownership", "draft patch", "assign owner", "ownership patch"),
        "draft_ownership_patch");

    // Governance Coverage Scanner (E6) — must come before generic search/find
    KEYWORD_TOOL_MAP.put(
        Set.of(
            "governance coverage",
            "coverage report",
            "compliance coverage",
            "governance gap report"),
        "scan_governance_coverage");
    KEYWORD_TOOL_MAP.put(
        Set.of("pii candidate", "pii detection", "untagged pii", "pii scan"),
        "scan_governance_coverage");

    // Dry-run Patch Validator (E9) — must come before patch_entity to avoid subsumption
    KEYWORD_TOOL_MAP.put(
        Set.of(
            "validate patch",
            "preview patch",
            "dry-run patch",
            "dry run patch",
            "preview change",
            "before applying",
            "check patch"),
        "validate_patch");

    // Data Contract Round-trip (E7) — must come before patch_entity
    KEYWORD_TOOL_MAP.put(
        Set.of("data contract", "export contract", "generate contract"), "generate_data_contract");
    KEYWORD_TOOL_MAP.put(
        Set.of("apply contract", "reapply contract", "import contract"), "apply_data_contract");

    // SQL → Lineage (E8) — must come before get_entity_lineage
    KEYWORD_TOOL_MAP.put(
        Set.of(
            "extract lineage from sql",
            "lineage from sql",
            "parse sql",
            "sql lineage",
            "extract lineage from this sql"),
        "lineage_from_sql");

    // Cost × Freshness Ranking (E10) — must come before generic search/find
    KEYWORD_TOOL_MAP.put(
        Set.of(
            "cost ranking",
            "cost freshness",
            "rank assets by cost",
            "wasting money",
            "expensive stale",
            "stale expensive",
            "most expensive tables",
            "costly tables",
            "priority score"),
        "rank_assets_by_cost");

    // Agentic Test Author (E11) — must come before get_test_definitions/create_test_case
    KEYWORD_TOOL_MAP.put(
        Set.of(
            "suggest test",
            "propose test",
            "recommend test",
            "test case proposals",
            "what tests should",
            "suggest_test"),
        "suggest_test_cases");

    // Primitive tools
    KEYWORD_TOOL_MAP.put(Set.of("semantic", "meaning", "concept", "vector"), "semantic_search");
    KEYWORD_TOOL_MAP.put(Set.of("search", "find", "look", "where"), "search_metadata");
    KEYWORD_TOOL_MAP.put(
        Set.of("details", "information about", "read", "lookup"), "get_entity_details");
    KEYWORD_TOOL_MAP.put(
        Set.of("lineage", "upstream", "downstream", "dependency", "depends"), "get_entity_lineage");
    KEYWORD_TOOL_MAP.put(Set.of("patch", "modify", "update", "edit"), "patch_entity");
    KEYWORD_TOOL_MAP.put(Set.of("glossary term", "create term"), "create_glossary_term");
    KEYWORD_TOOL_MAP.put(Set.of("glossary", "vocabulary"), "create_glossary");
    KEYWORD_TOOL_MAP.put(Set.of("test definition", "available test"), "get_test_definitions");
    KEYWORD_TOOL_MAP.put(Set.of("test case", "add test", "create test"), "create_test_case");
    KEYWORD_TOOL_MAP.put(Set.of("lineage edge", "create lineage"), "create_lineage");
    KEYWORD_TOOL_MAP.put(Set.of("metric", "kpi"), "create_metric");

    // ── Prompt mappings ──
    // ownership_stewardship first — it's a composite workflow prompt that subsumes
    // the individual stewardship tools and generic search
    KEYWORD_PROMPT_MAP.put(
        Set.of(
            "ownership stewardship",
            "stewardship workflow",
            "ownership workflow",
            "assign owners",
            "governance gap",
            "ownership review"),
        "ownership_stewardship");

    // search_metadata prompt — generic search assistance
    KEYWORD_PROMPT_MAP.put(
        Set.of("search assistant", "help me search", "search guide"), "search_metadata");
  }

  /** Composite tools that subsume primitive search/lookup. If any of these are selected, skip redundant primitives. */
  private static final Set<String> COMPOSITE_TOOLS =
      Set.of(
          "change_impact",
          "incident_timeline",
          "root_cause_analysis",
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

  /** Primitives that are subsumed by composite tools. */
  private static final Set<String> SUBSUMED_PRIMITIVES =
      Set.of("search_metadata", "get_entity_details", "get_entity_lineage", "patch_entity");

  @Override
  public List<String> selectTools(String prompt, List<String> availableTools) {
    List<String> selected = new ArrayList<>();
    String lowerPrompt = prompt.toLowerCase();

    for (Map.Entry<Set<String>, String> entry : KEYWORD_TOOL_MAP.entrySet()) {
      String tool = entry.getValue();
      if (!availableTools.contains(tool)) {
        continue;
      }
      // Any keyword in the set triggers the tool (OR logic — keywords are alternatives)
      boolean anyMatch = entry.getKey().stream().anyMatch(kw -> lowerPrompt.contains(kw));
      if (anyMatch) {
        selected.add(tool);
      }
    }

    // If composite tools matched, remove subsumed primitives to avoid over-matching
    // (e.g., "Find unowned tables" should not also select search_metadata via "find")
    boolean hasComposite = selected.stream().anyMatch(COMPOSITE_TOOLS::contains);
    if (hasComposite) {
      selected.removeIf(SUBSUMED_PRIMITIVES::contains);
    }

    // If no tools matched, fall back to search_metadata as the most generic tool
    if (selected.isEmpty() && availableTools.contains("search_metadata")) {
      selected.add("search_metadata");
    }

    return selected;
  }

  @Override
  public List<String> selectPrompts(String prompt, List<String> availablePrompts) {
    List<String> selected = new ArrayList<>();
    String lowerPrompt = prompt.toLowerCase();

    for (Map.Entry<Set<String>, String> entry : KEYWORD_PROMPT_MAP.entrySet()) {
      String mcpPrompt = entry.getValue();
      if (!availablePrompts.contains(mcpPrompt)) {
        continue;
      }
      boolean anyMatch = entry.getKey().stream().anyMatch(kw -> lowerPrompt.contains(kw));
      if (anyMatch) {
        selected.add(mcpPrompt);
      }
    }

    return selected;
  }

  @Override
  public String generateAnswer(String prompt, List<ToolCallResult> toolCallResults) {
    // Deterministic answer: just concatenate tool names + prompt keywords
    StringBuilder sb = new StringBuilder();
    sb.append("Based on analysis");
    for (ToolCallResult result : toolCallResults) {
      sb.append(" using ").append(result.toolName());
    }
    sb.append(": ");
    for (ToolCallResult result : toolCallResults) {
      sb.append(result.responseSummary()).append(" ");
    }
    return sb.toString().trim();
  }
}
