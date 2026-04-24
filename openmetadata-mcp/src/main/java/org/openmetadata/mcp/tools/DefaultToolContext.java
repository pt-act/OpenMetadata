package org.openmetadata.mcp.tools;

import static org.openmetadata.mcp.McpUtils.getToolProperties;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class DefaultToolContext {
  public DefaultToolContext() {}

  /**
   * Loads tool definitions from a JSON file located at the specified path. The JSON file should
   * contain an array of tool definitions under the "tools" key.
   *
   * @return List of McpSchema.Tool objects loaded from the JSON file.
   */
  public List<McpSchema.Tool> loadToolsDefinitionsFromJson(String toolFilePath) {
    return getToolProperties(toolFilePath);
  }

  public McpSchema.CallToolResult callTool(
      Authorizer authorizer,
      Limits limits,
      String toolName,
      CatalogSecurityContext securityContext,
      McpSchema.CallToolRequest request) {
    Map<String, Object> params = request.arguments();

    // Use ToolObserver.observe() for structured logging (replaces F7 ad-hoc logging).
    // ToolObserver emits exactly one JSON line per call with mcp.tool_call tag,
    // logging paramKeys (never values), outcome, durationMs, userId, and errorClass on failure.
    try {
      Object result =
          ToolObserver.observe(
              toolName,
              params,
              securityContext,
              () -> {
                try {
                  McpTool tool = resolveTool(toolName);
                  if (tool == null) {
                    return Map.of("error", "Unknown function: " + toolName);
                  }
                  // Tools that enforce limits use the 4-arg execute overload
                  if (usesLimits(toolName)) {
                    return tool.execute(authorizer, limits, securityContext, params);
                  }
                  return tool.execute(authorizer, securityContext, params);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });

      return McpSchema.CallToolResult.builder()
          .content(List.of(new McpSchema.TextContent(JsonUtils.pojoToJson(result))))
          .isError(false)
          .build();
    } catch (AuthorizationException ex) {
      return McpSchema.CallToolResult.builder()
          .content(
              List.of(
                  new McpSchema.TextContent(
                      JsonUtils.pojoToJson(
                          Map.of(
                              "error",
                              String.format("Authorization error: %s", ex.getMessage()),
                              "statusCode",
                              403)))))
          .isError(true)
          .build();
    } catch (Exception ex) {
      return McpSchema.CallToolResult.builder()
          .content(
              List.of(
                  new McpSchema.TextContent(
                      JsonUtils.pojoToJson(
                          Map.of(
                              "error",
                              String.format("Error executing tool: %s", ex.getMessage()),
                              "statusCode",
                              500)))))
          .isError(true)
          .build();
    }
  }

  /** Returns true for tools that enforce limits (use the 4-arg execute overload). */
  private boolean usesLimits(String toolName) {
    return switch (toolName) {
      case "create_glossary", "create_glossary_term", "create_test_case", "create_metric" -> true;
      default -> false;
    };
  }

  /** Resolves a tool name to its McpTool implementation. */
  private McpTool resolveTool(String toolName) {
    return switch (toolName) {
      case "search_metadata" -> new SearchMetadataTool();
      case "semantic_search" -> new SemanticSearchTool();
      case "get_entity_details" -> new GetEntityTool();
      case "create_glossary" -> new GlossaryTool();
      case "create_glossary_term" -> new GlossaryTermTool();
      case "patch_entity" -> new PatchEntityTool();
      case "get_entity_lineage" -> new GetLineageTool();
      case "create_lineage" -> new LineageTool();
      case "get_test_definitions" -> new TestDefinitionsTool();
      case "create_test_case" -> new CreateTestCaseTool();
      case "root_cause_analysis" -> new RootCauseAnalysisTool();
      case "change_impact" -> new ChangeImpactTool();
      case "incident_timeline" -> new IncidentTimelineTool();
      case "create_metric" -> new CreateMetricTool();
      case "find_unowned_assets" -> new FindUnownedAssetsTool();
      case "suggest_owner_for" -> new SuggestOwnerForTool();
      case "draft_ownership_patch" -> new DraftOwnershipPatchTool();
      case "scan_governance_coverage" -> new ScanGovernanceCoverageTool();
      case "validate_patch" -> new ValidatePatchTool();
      case "generate_data_contract" -> new GenerateDataContractTool();
      case "apply_data_contract" -> new ApplyDataContractTool();
      case "lineage_from_sql" -> new LineageFromSqlTool();
      case "suggest_test_cases" -> new SuggestTestCasesTool();
      case "rank_assets_by_cost" -> new RankAssetsByCostTool();
      default -> null;
    };
  }
}
