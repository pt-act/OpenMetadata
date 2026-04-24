package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.search.SearchUtil.mapEntityTypesToIndexNames;
import static org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Change Impact Copilot — answers "what breaks if I change X?" in one call.
 *
 * <p>Composes downstream lineage, dashboard/pipeline search, test-case discovery, and policy
 * attachment into a single bounded payload with a severity rubric and Markdown narrative.
 *
 * <p>Spec reference: Expansions Group E2 (R2.1–R2.5).
 */
@Slf4j
public class ChangeImpactTool implements McpTool {

  private static final int DEFAULT_DOWNSTREAM_DEPTH = 3;
  private static final int MAX_DOWNSTREAM_DEPTH = 10;
  private static final int NARRATIVE_MAX_LENGTH = 1200;
  private static final int PAYLOAD_MAX_BYTES = 8192; // 8 KB
  private static final int MAX_ENTITIES_BEFORE_TRUNCATION = 50;

  // Severity rubric thresholds (per R2.4)
  private static final String SEVERITY_CRITICAL = "critical";
  private static final String SEVERITY_HIGH = "high";
  private static final String SEVERITY_MEDIUM = "medium";
  private static final String SEVERITY_LOW = "low";

  /**
   * Production call — creates default bridge interfaces that delegate to {@link
   * org.openmetadata.service.Entity} static methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {
    return execute(
        params,
        securityContext,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultLineageRepositoryProvider(),
        McpEntityBridge.defaultSearchRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link
   * org.openmetadata.service.Entity} static method calls and authorizer delegation, eliminating
   * the need for {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.LineageRepositoryProvider lineageProvider,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider)
      throws IOException {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    // --- Parse parameters ---
    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    ProposedChange proposedChange = parseProposedChange(params);
    int downstreamDepth =
        Math.min(
            Math.max(parseIntParam(params.get("downstreamDepth"), DEFAULT_DOWNSTREAM_DEPTH), 0),
            MAX_DOWNSTREAM_DEPTH);
    boolean includeDashboards = parseBooleanParam(params.get("includeDashboards"), true);
    boolean includeTests = parseBooleanParam(params.get("includeTests"), true);
    boolean includePolicies = parseBooleanParam(params.get("includePolicies"), true);

    LOG.info(
        "Change impact analysis for '{}': kind={}, downstreamDepth={}",
        fqn,
        proposedChange.kind,
        downstreamDepth);

    // --- Fan-out: downstream lineage (upstreamDepth=0 per spec R2.2) ---
    List<Map<String, Object>> downstreamEntities = new ArrayList<>();
    try {
      var lineageRepo = lineageProvider.getLineageRepository();
      if (lineageRepo == null) {
        LOG.warn(
            "Lineage repository not initialized — cannot fetch downstream lineage for '{}'", fqn);
      } else {
        Map<String, Object> lineageData =
            JsonUtils.getMap(lineageRepo.getByName(entityType, fqn, 0, downstreamDepth));
        if (lineageData != null) {
          downstreamEntities = extractDownstreamNodes(lineageData, fqn);
        }
      }
    } catch (Exception e) {
      LOG.warn(
          "Failed to fetch lineage for change impact analysis of '{}': {}", fqn, e.getMessage());
    }

    // --- Fan-out: dashboards & pipelines referencing the entity/column ---
    List<Map<String, Object>> dashboards = new ArrayList<>();
    List<Map<String, Object>> pipelines = new ArrayList<>();
    if (includeDashboards) {
      dashboards =
          searchReferencingAssets(
              securityContext, fqn, proposedChange, "dashboard", searchRepoProvider);
      pipelines =
          searchReferencingAssets(
              securityContext, fqn, proposedChange, "pipeline", searchRepoProvider);
    }

    // --- Fan-out: test cases touching the entity/column ---
    List<Map<String, Object>> tests = new ArrayList<>();
    if (includeTests) {
      tests =
          searchTestCasesForEntity(
              securityContext, entityType, fqn, proposedChange, searchRepoProvider);
    }

    // --- Fan-out: policies attached to affected entities/domains ---
    List<Map<String, Object>> policies = new ArrayList<>();
    if (includePolicies) {
      policies = searchPoliciesForEntity(securityContext, fqn, searchRepoProvider);
    }

    // --- Severity computation (per R2.4 rubric) ---
    String severity = computeSeverity(downstreamEntities, dashboards, pipelines, tests, policies);

    // --- Build response ---
    Map<String, Object> affected = new LinkedHashMap<>();
    affected.put("entities", truncateList(downstreamEntities, MAX_ENTITIES_BEFORE_TRUNCATION));
    affected.put("dashboards", truncateList(dashboards, MAX_ENTITIES_BEFORE_TRUNCATION));
    affected.put("pipelines", truncateList(pipelines, MAX_ENTITIES_BEFORE_TRUNCATION));
    affected.put("tests", truncateList(tests, MAX_ENTITIES_BEFORE_TRUNCATION));
    affected.put("policies", truncateList(policies, MAX_ENTITIES_BEFORE_TRUNCATION));

    Map<String, Object> counts = new LinkedHashMap<>();
    counts.put("entities", downstreamEntities.size());
    counts.put("dashboards", dashboards.size());
    counts.put("pipelines", pipelines.size());
    counts.put("tests", tests.size());
    counts.put("policies", policies.size());

    // Build the core result map
    Map<String, Object> impactResult = new LinkedHashMap<>();
    impactResult.put("severity", severity);
    impactResult.put("affected", affected);
    impactResult.put("counts", counts);
    impactResult.put("proposedChange", proposedChange.toMap());

    // --- Narrative generation (deterministic Markdown, ≤1200 chars per R2.5) ---
    String narrative = generateNarrative(fqn, proposedChange, severity, counts);
    narrative = capNarrative(narrative);

    // --- Envelope with <8KB byte cap (R2.9) ---
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(impactResult)).narrative(narrative);

    Map<String, Object> result = new HashMap<>(envelope.build());
    // Add top-level fields for easy access
    result.put("severity", severity);
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("downstreamDepth", downstreamDepth);

    // Truncate if payload exceeds byte cap
    result = enforceByteCap(result);

    return result;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "ChangeImpactTool does not support limits enforcement.");
  }

  // ====================== proposedChange parsing (E2.2) ======================

  /**
   * Parses the proposedChange parameter. Accepts either a Map (structured) or falls back to
   * individual params (kind, column, fromType, toType, description).
   */
  @VisibleForTesting
  static ProposedChange parseProposedChange(Map<String, Object> params) {
    Object changeObj = params.get("proposedChange");
    if (changeObj instanceof Map<?, ?> changeMap) {
      String kind = stringOrNull(changeMap.get("kind"));
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("proposedChange must include a 'kind' field");
      }
      return new ProposedChange(
          kind,
          stringOrNull(changeMap.get("column")),
          stringOrNull(changeMap.get("fromType")),
          stringOrNull(changeMap.get("toType")),
          stringOrNull(changeMap.get("description")));
    }

    // Fallback: individual params
    String kind = (String) params.get("kind");
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException(
          "Parameter 'proposedChange' (or 'kind') is required. "
              + "Supported kinds: dropColumn, changeColumnType, deprecateEntity, custom");
    }
    return new ProposedChange(
        kind,
        (String) params.get("column"),
        (String) params.get("fromType"),
        (String) params.get("toType"),
        (String) params.get("description"));
  }

  /** Parsed proposed change descriptor. */
  @VisibleForTesting
  static class ProposedChange {
    final String kind;
    final String column;
    final String fromType;
    final String toType;
    final String description;

    ProposedChange(String kind, String column, String fromType, String toType, String description) {
      this.kind = kind;
      this.column = column;
      this.fromType = fromType;
      this.toType = toType;
      this.description = description;

      // Validate kind
      if (!List.of("dropColumn", "changeColumnType", "deprecateEntity", "custom").contains(kind)) {
        throw new IllegalArgumentException(
            "Unsupported proposedChange kind: '"
                + kind
                + "'. Supported: dropColumn, changeColumnType, deprecateEntity, custom");
      }

      // Kind-specific validation
      if ("dropColumn".equals(kind) && (column == null || column.isBlank())) {
        throw new IllegalArgumentException(
            "proposedChange kind 'dropColumn' requires a 'column' field");
      }
      if ("changeColumnType".equals(kind)) {
        if (column == null || column.isBlank()) {
          throw new IllegalArgumentException(
              "proposedChange kind 'changeColumnType' requires a 'column' field");
        }
        if (fromType == null || fromType.isBlank() || toType == null || toType.isBlank()) {
          throw new IllegalArgumentException(
              "proposedChange kind 'changeColumnType' requires 'fromType' and 'toType' fields");
        }
      }
      if ("custom".equals(kind) && (description == null || description.isBlank())) {
        throw new IllegalArgumentException(
            "proposedChange kind 'custom' requires a 'description' field");
      }
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("kind", kind);
      if (column != null) map.put("column", column);
      if (fromType != null) map.put("fromType", fromType);
      if (toType != null) map.put("toType", toType);
      if (description != null) map.put("description", description);
      return map;
    }

    /** Human-readable description of the change for narratives. */
    String describe() {
      return switch (kind) {
        case "dropColumn" -> "dropping column `" + column + "`";
        case "changeColumnType" -> "changing column `"
            + column
            + "` from "
            + fromType
            + " to "
            + toType;
        case "deprecateEntity" -> "deprecating entity";
        case "custom" -> description;
        default -> kind;
      };
    }
  }

  // ====================== Fan-out: downstream lineage extraction ======================

  /** Extracts downstream entity nodes from lineage response, excluding the source entity. */
  @VisibleForTesting
  static List<Map<String, Object>> extractDownstreamNodes(
      Map<String, Object> lineageData, String sourceFqn) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    Object nodesObj = lineageData.get("nodes");
    if (nodesObj instanceof Map<?, ?> nodesMap) {
      for (Map.Entry<?, ?> entry : nodesMap.entrySet()) {
        Object nodeObj = entry.getValue();
        if (nodeObj instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> node = new HashMap<>((Map<String, Object>) nodeObj);
          // Clean and exclude the source entity itself
          SearchMetadataTool.cleanSearchResponseObject(node);
          String nodeFqn = (String) node.get("fullyQualifiedName");
          if (nodeFqn != null && !nodeFqn.equals(sourceFqn)) {
            node.put("hitReason", "downstream");
            nodes.add(node);
          }
        }
      }
    }
    return nodes;
  }

  // ====================== Shared search helper ======================

  /**
   * Executes a search request and extracts results with a hitReason annotation. Shared by all
   * fan-out search methods (dashboards, pipelines, test cases, policies).
   */
  private List<Map<String, Object>> searchAndExtract(
      CatalogSecurityContext securityContext,
      String fqn,
      String queryFilter,
      String indexType,
      int size,
      String hitReason,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    List<Map<String, Object>> results = new ArrayList<>();
    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo == null) {
        LOG.warn("Search repository not initialized — cannot search '{}' assets", indexType);
        return results;
      }

      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(indexType)))
              .withQueryFilter(queryFilter)
              .withSize(size)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(false);

      SubjectContext subjectContext = getSubjectContext(securityContext);
      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) {
        LOG.warn("Search repository returned null response for '{}' ({})", fqn, hitReason);
        return results;
      }

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      Map<String, Object> envelopeMap =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, fqn, 0, size, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> resultList = (List<Object>) envelopeMap.getOrDefault("results", List.of());
      for (Object item : resultList) {
        if (item instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> itemMap = (Map<String, Object>) item;
          itemMap.put("hitReason", hitReason);
          results.add(itemMap);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to search {} assets for '{}': {}", indexType, fqn, e.getMessage());
    }
    return results;
  }

  // ====================== Fan-out: dashboard/pipeline search (E2.4) ======================

  private List<Map<String, Object>> searchReferencingAssets(
      CatalogSecurityContext securityContext,
      String fqn,
      ProposedChange proposedChange,
      String assetType,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    String queryFilter = buildReferenceQueryFilter(fqn, proposedChange, assetType);
    if (queryFilter == null) return new ArrayList<>();
    String hitReason =
        "references:" + (proposedChange.column != null ? proposedChange.column : fqn);
    return searchAndExtract(
        securityContext, fqn, queryFilter, assetType, 25, hitReason, searchRepoProvider);
  }

  /** Builds an OpenSearch query filter for dashboards/pipelines referencing the entity or column. */
  @VisibleForTesting
  static String buildReferenceQueryFilter(
      String fqn, ProposedChange proposedChange, String assetType) {
    // Match assets that reference the entity FQN
    StringBuilder mustClauses = new StringBuilder();
    mustClauses.append("{\"term\":{\"entityType\":\"").append(assetType).append("\"}}");

    // For column-level changes, search for the column name in the asset
    if (proposedChange.column != null && !proposedChange.column.isBlank()) {
      mustClauses
          .append(",{\"match\":{\"columns.name\":\"")
          .append(escapeJson(proposedChange.column))
          .append("\"}}");
    } else {
      // Entity-level reference: search for the FQN in relevant fields
      mustClauses
          .append(",{\"multi_match\":{\"query\":\"")
          .append(escapeJson(fqn))
          .append("\",\"fields\":[\"name\",\"fullyQualifiedName\",\"description\"]}}");
    }

    return "{\"bool\":{\"must\":[" + mustClauses + "]}}";
  }

  // ====================== Fan-out: test cases (E2.5) ======================

  private List<Map<String, Object>> searchTestCasesForEntity(
      CatalogSecurityContext securityContext,
      String entityType,
      String fqn,
      ProposedChange proposedChange,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    // Search for test cases whose entityLink references this entity (and optionally column)
    String entityLinkPattern = "<#E::" + entityType + "::" + fqn;
    if (proposedChange.column != null) {
      entityLinkPattern += "::columns::" + proposedChange.column;
    }

    String queryFilter =
        "{\"bool\":{\"must\":["
            + "{\"term\":{\"entityType\":\"testCase\"}},"
            + "{\"wildcard\":{\"entityLink\":\""
            + escapeJson(entityLinkPattern)
            + "*\"}}"
            + "]}}";

    String hitReason =
        "testTouches:" + (proposedChange.column != null ? proposedChange.column : fqn);
    return searchAndExtract(
        securityContext, fqn, queryFilter, "testCase", 25, hitReason, searchRepoProvider);
  }

  // ====================== Fan-out: policies (E2.6) ======================

  private List<Map<String, Object>> searchPoliciesForEntity(
      CatalogSecurityContext securityContext,
      String fqn,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    String queryFilter =
        "{\"bool\":{\"must\":["
            + "{\"term\":{\"entityType\":\"policy\"}},"
            + "{\"multi_match\":{\"query\":\""
            + escapeJson(fqn)
            + "\",\"fields\":[\"name\",\"description\",\"rules\"]}}"
            + "]}}";

    return searchAndExtract(
        securityContext, fqn, queryFilter, "policy", 10, "policyCovers:" + fqn, searchRepoProvider);
  }

  // ====================== Severity rubric (E2.7 / R2.4) ======================

  /**
   * Computes severity per R2.4 rubric:
   *
   * <ul>
   *   <li>{@code critical} if ≥1 Tier-1 asset affected
   *   <li>{@code high} if ≥5 downstream entities
   *   <li>{@code medium} if 1–4 downstream entities
   *   <li>{@code low} otherwise
   * </ul>
   */
  @VisibleForTesting
  static String computeSeverity(
      List<Map<String, Object>> downstreamEntities,
      List<Map<String, Object>> dashboards,
      List<Map<String, Object>> pipelines,
      List<Map<String, Object>> tests,
      List<Map<String, Object>> policies) {

    // Check for Tier-1 assets (critical)
    for (Map<String, Object> entity : downstreamEntities) {
      if (isTier1(entity)) {
        return SEVERITY_CRITICAL;
      }
    }

    // Check total downstream count
    int totalDownstream =
        downstreamEntities.size()
            + dashboards.size()
            + pipelines.size()
            + tests.size()
            + policies.size();

    if (totalDownstream >= 5) {
      return SEVERITY_HIGH;
    }
    if (totalDownstream >= 1) {
      return SEVERITY_MEDIUM;
    }
    return SEVERITY_LOW;
  }

  /** Checks if an entity has Tier.Tier1 tag. */
  private static boolean isTier1(Map<String, Object> entity) {
    Object tier = entity.get("tier");
    if (tier instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> tierMap = (Map<String, Object>) tier;
      Object tagFqn = tierMap.get("tagFQN");
      return tagFqn != null && tagFqn.toString().equals("Tier.Tier1");
    }
    if (tier instanceof String) {
      return "Tier.Tier1".equals(tier);
    }
    return false;
  }

  // ====================== Narrative generation (E2.8 / R2.5) ======================

  /** Generates a deterministic Markdown narrative capped at 1200 chars. */
  @VisibleForTesting
  static String generateNarrative(
      String fqn, ProposedChange proposedChange, String severity, Map<String, Object> counts) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Change Impact: `").append(fqn).append("`\n\n");
    sb.append("**Proposed change:** ").append(proposedChange.describe()).append("\n\n");
    sb.append("**Severity: ").append(severity.toUpperCase()).append("**\n\n");

    int entities = (int) counts.getOrDefault("entities", 0);
    int dashboards = (int) counts.getOrDefault("dashboards", 0);
    int pipelines = (int) counts.getOrDefault("pipelines", 0);
    int tests = (int) counts.getOrDefault("tests", 0);
    int policies = (int) counts.getOrDefault("policies", 0);

    sb.append("### Affected assets\n");
    if (entities > 0) {
      sb.append("- **")
          .append(entities)
          .append("** downstream entit")
          .append(entities == 1 ? "y" : "ies")
          .append("\n");
    }
    if (dashboards > 0) {
      sb.append("- **")
          .append(dashboards)
          .append("** dashboard")
          .append(dashboards == 1 ? "" : "s")
          .append("\n");
    }
    if (pipelines > 0) {
      sb.append("- **")
          .append(pipelines)
          .append("** pipeline")
          .append(pipelines == 1 ? "" : "s")
          .append("\n");
    }
    if (tests > 0) {
      sb.append("- **")
          .append(tests)
          .append("** test case")
          .append(tests == 1 ? "" : "s")
          .append("\n");
    }
    if (policies > 0) {
      sb.append("- **")
          .append(policies)
          .append("** polic")
          .append(policies == 1 ? "y" : "ies")
          .append("\n");
    }

    if (entities == 0 && dashboards == 0 && pipelines == 0 && tests == 0 && policies == 0) {
      sb.append("- No downstream impact detected.\n");
    }

    sb.append("\n### Recommendation\n");
    if (SEVERITY_CRITICAL.equals(severity)) {
      sb.append(
          "⚠️ Tier-1 asset affected. Consider coordinated rollout and stakeholder notification.");
    } else if (SEVERITY_HIGH.equals(severity)) {
      sb.append("Significant blast radius. Review all affected assets before proceeding.");
    } else if (SEVERITY_MEDIUM.equals(severity)) {
      sb.append("Moderate impact. Verify affected downstream assets can adapt to the change.");
    } else {
      sb.append("Low impact. Safe to proceed, but verify no undocumented dependencies exist.");
    }

    return sb.toString();
  }

  /** Caps narrative to NARRATIVE_MAX_LENGTH (1200 chars), appending ellipsis if truncated. */
  @VisibleForTesting
  static String capNarrative(String narrative) {
    if (narrative.length() <= NARRATIVE_MAX_LENGTH) {
      return narrative;
    }
    return narrative.substring(0, NARRATIVE_MAX_LENGTH - 3) + "...";
  }

  // ====================== Byte cap enforcement (E2.9 / R2.9) ======================

  /**
   * Enforces the <8KB byte cap on the serialized response. If the payload exceeds the cap,
   * truncates the affected.entities list first, then other lists, adding warnings.
   *
   * <p><b>Note:</b> This method mutates the input map in place for efficiency. The caller should
   * not rely on the original map remaining unchanged.
   */
  @VisibleForTesting
  static Map<String, Object> enforceByteCap(Map<String, Object> result) {
    String json = JsonUtils.pojoToJson(result);
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length <= PAYLOAD_MAX_BYTES) {
      return result;
    }

    // Strategy: truncate affected lists from largest to smallest until under cap
    List<String> listKeys = List.of("entities", "pipelines", "dashboards", "tests", "policies");
    List<String> warnings = new ArrayList<>();

    // Navigate into envelope: result -> results[0] -> affected
    Object resultsObj = result.get("results");
    if (!(resultsObj instanceof List<?> resultsList) || resultsList.isEmpty()) {
      return result;
    }
    Object firstResult = resultsList.get(0);
    if (!(firstResult instanceof Map<?, ?>)) {
      return result;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> impactResult = (Map<String, Object>) firstResult;

    @SuppressWarnings("unchecked")
    Map<String, Object> affected = (Map<String, Object>) impactResult.get("affected");
    if (affected == null) {
      return result;
    }

    for (String key : listKeys) {
      Object listObj = affected.get(key);
      if (listObj instanceof List<?> list && list.size() > 3) {
        int originalSize = list.size();
        affected.put(key, list.subList(0, 3));
        warnings.add(
            String.format(
                "truncated:%s list from %d to 3 entries (payload >8KB)", key, originalSize));

        // Re-serialize to check if we're now under cap
        String updatedJson = JsonUtils.pojoToJson(result);
        if (updatedJson != null
            && updatedJson.getBytes(StandardCharsets.UTF_8).length <= PAYLOAD_MAX_BYTES) {
          break;
        }
      }
    }

    if (!warnings.isEmpty()) {
      // Add warnings to the envelope
      @SuppressWarnings("unchecked")
      List<String> existingWarnings =
          result.get("warnings") instanceof List
              ? new ArrayList<>((List<String>) result.get("warnings"))
              : new ArrayList<>();
      existingWarnings.addAll(warnings);
      result.put("warnings", existingWarnings);
    }

    return result;
  }

  // ====================== Utility methods ======================

  private static int parseIntParam(Object value, int defaultValue) {
    if (value == null) return defaultValue;
    if (value instanceof Number number) return number.intValue();
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  private static boolean parseBooleanParam(Object value, boolean defaultValue) {
    if (value == null) return defaultValue;
    if (value instanceof Boolean b) return b;
    if (value instanceof String s) return "true".equalsIgnoreCase(s);
    return defaultValue;
  }

  private static String stringOrNull(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  /** Escapes special characters for JSON string values. */
  private static String escapeJson(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** Truncates a list to maxSize, adding a hitReason with truncation info if truncated. */
  private static List<Map<String, Object>> truncateList(
      List<Map<String, Object>> list, int maxSize) {
    if (list.size() <= maxSize) return list;
    return list.subList(0, maxSize);
  }
}
