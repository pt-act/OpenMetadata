package org.openmetadata.mcp.tools;

import static org.openmetadata.service.search.SearchUtils.mapEntityTypesToIndexNames;
import static org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Stewardship Copilot — finds assets without owners, ranked by downstream entity count.
 *
 * <p>Uses OpenSearch aggregation on {@code owners.size == 0} to identify unowned assets, then
 * ranks them by the number of downstream entities (impact-based prioritization).
 *
 * <p>Spec reference: Expansions Group E5 (R5.1–R5.2).
 */
@Slf4j
public class FindUnownedAssetsTool implements McpTool {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_PAYLOAD_BYTES = 8 * 1024; // 8 KB

  /** Per-user rate limit: minimum milliseconds between consecutive calls. 5 minutes. */
  @VisibleForTesting static final long RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L;

  /** Tracks the last call timestamp per user for rate limiting. */
  @VisibleForTesting
  static final ConcurrentHashMap<String, Long> USER_LAST_CALL_MS = new ConcurrentHashMap<>();

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
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultSearchRepositoryProvider(),
        McpEntityBridge.defaultLineageRepositoryProvider());
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
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider,
      McpEntityBridge.LineageRepositoryProvider lineageProvider)
      throws IOException {

    // Parse scope — optional domain/service filter
    String scopeType = null;
    String scopeValue = null;
    Object scopeObj = params.get("scope");
    if (scopeObj instanceof Map<?, ?> scopeMap) {
      scopeType = (String) scopeMap.get("type");
      scopeValue = (String) scopeMap.get("value");
    } else if (scopeObj instanceof String scopeStr && !scopeStr.isBlank()) {
      // Shorthand: treat as domain scope
      scopeType = "domain";
      scopeValue = scopeStr;
    }

    int limit = Math.min(Math.max(parseIntParam(params.get("limit"), DEFAULT_LIMIT), 1), MAX_LIMIT);

    // Parse entityType filter (default: table)
    String entityType = (String) params.getOrDefault("entityType", "table");

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    // Rate limit: one call per user per 5-minute window
    String userId = securityContext.getUserPrincipal().getName();
    Long blockedAt = tryAcquireRateLimit(userId);
    if (blockedAt != null) {
      long remainingSeconds =
          Math.max(1, (RATE_LIMIT_COOLDOWN_MS - (System.currentTimeMillis() - blockedAt)) / 1000);
      return Map.of(
          "error",
          "Rate limit exceeded: please wait "
              + remainingSeconds
              + "s before calling find_unowned_assets again",
          "retryAfterSeconds",
          remainingSeconds,
          "statusCode",
          429);
    }

    // Evict stale entries to prevent unbounded memory growth
    evictStaleEntries(System.currentTimeMillis());

    LOG.info(
        "Finding unowned assets: entityType={}, scope={}/{}, limit={}",
        entityType,
        scopeType,
        scopeValue,
        limit);

    // Step 1: Search for entities with no owners via OpenSearch query
    List<Map<String, Object>> unownedAssets =
        searchUnownedAssets(
            securityContext, entityType, scopeType, scopeValue, limit, searchRepoProvider);

    // Step 2: Rank by downstream count (fetch lineage for each)
    List<Map<String, Object>> rankedAssets =
        rankByDownstreamCount(unownedAssets, entityType, lineageProvider);

    // Step 3: Truncate to limit
    if (rankedAssets.size() > limit) {
      rankedAssets = rankedAssets.subList(0, limit);
    }

    // Step 4: Build response
    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("totalFound", unownedAssets.size());
    resultData.put("returnedCount", rankedAssets.size());
    resultData.put("entityType", entityType);
    if (scopeType != null && scopeValue != null) {
      Map<String, String> scopeMap = new LinkedHashMap<>();
      scopeMap.put("type", scopeType);
      scopeMap.put("value", scopeValue);
      resultData.put("scope", scopeMap);
    }

    // Narrative
    String narrative =
        generateNarrative(entityType, scopeType, scopeValue, unownedAssets.size(), rankedAssets);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(rankedAssets)
            .pagination(0, limit, unownedAssets.size())
            .narrative(narrative);

    Map<String, Object> result = new HashMap<>(envelope.build());
    result.put("totalFound", unownedAssets.size());
    result.put("entityType", entityType);
    if (scopeType != null && scopeValue != null) {
      Map<String, String> scopeMap = new LinkedHashMap<>();
      scopeMap.put("type", scopeType);
      scopeMap.put("value", scopeValue);
      result.put("scope", scopeMap);
    }

    // Enforce byte cap
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
        "FindUnownedAssetsTool does not support limits enforcement.");
  }

  // ====================== Rate limiting (R5.9) ======================

  /**
   * Atomically checks and acquires the per-user rate limit.
   *
   * <p>Returns {@code null} if this call is allowed (first call or cooldown has elapsed), or the
   * timestamp of the original call if the user is within the cooldown window. Uses {@code
   * ConcurrentHashMap.compute()} to guarantee that exactly one concurrent caller per user gets
   * {@code null}.
   */
  @VisibleForTesting
  static Long tryAcquireRateLimit(String userId) {
    long now = System.currentTimeMillis();
    Long[] blockedAt = {null};
    USER_LAST_CALL_MS.compute(
        userId,
        (k, lastCall) -> {
          if (lastCall != null && (now - lastCall) < RATE_LIMIT_COOLDOWN_MS) {
            blockedAt[0] = lastCall; // signal rejection
            return lastCall; // don't update timestamp
          }
          return now; // record this call
        });
    return blockedAt[0]; // null = allowed, non-null = blocked (value = original call timestamp)
  }

  /** Evicts stale entries from the rate limit map to prevent unbounded memory growth. */
  @VisibleForTesting
  static void evictStaleEntries(long now) {
    USER_LAST_CALL_MS.entrySet().removeIf(e -> (now - e.getValue()) > RATE_LIMIT_COOLDOWN_MS * 2);
  }

  // ====================== Search for unowned assets (R5.2) ======================

  /**
   * Searches for entities with no owners using OpenSearch query.
   *
   * <p>Query strategy: Use a {@code bool} query with a {@code must_not} clause for the nested
   * {@code owners} field, which matches entities where the owners array is empty or missing.
   */
  @VisibleForTesting
  List<Map<String, Object>> searchUnownedAssets(
      CatalogSecurityContext securityContext,
      String entityType,
      String scopeType,
      String scopeValue,
      int size,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    List<Map<String, Object>> results = new ArrayList<>();
    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo == null) {
        LOG.warn("Search repository not initialized — cannot search unowned assets");
        return results;
      }

      // Build OpenSearch query: entityType + no owners + optional scope filter
      StringBuilder mustClauses = new StringBuilder();
      mustClauses
          .append("{\"term\":{\"entityType\":\"")
          .append(escapeJson(entityType))
          .append("\"}}");

      // Scope filters
      if ("domain".equals(scopeType) && scopeValue != null) {
        mustClauses
            .append(",{\"term\":{\"domains.name\":\"")
            .append(escapeJson(scopeValue))
            .append("\"}}");
      } else if ("service".equals(scopeType) && scopeValue != null) {
        mustClauses
            .append(",{\"term\":{\"service.name\":\"")
            .append(escapeJson(scopeValue))
            .append("\"}}");
      }

      String queryFilter =
          "{\"bool\":{\"must\":["
              + mustClauses
              + "],"
              + "\"must_not\":[{\"nested\":{\"path\":\"owners\",\"query\":{\"match_all\":{}}}}]}}";

      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(entityType)))
              .withQueryFilter(queryFilter)
              .withSize(size)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(false);

      SubjectContext subjectContext = getSubjectContext(securityContext);
      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) {
        LOG.warn("Search repository returned null response for unowned assets query");
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
              searchResponse, "", 0, size, List.of(), false, 10);

      Object resultsObj = envelopeMap.get("results");
      if (resultsObj instanceof List<?> resultList) {
        for (Object item : resultList) {
          if (item instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> itemMap = (Map<String, Object>) item;
            // Add downstreamCount placeholder — will be populated by rankByDownstreamCount
            itemMap.put("downstreamCount", 0);
            results.add(itemMap);
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to search unowned assets: {}", e.getMessage());
    }
    return results;
  }

  // ====================== Rank by downstream count (R5.2) ======================

  /**
   * Ranks unowned assets by the number of downstream entities, using 1-hop lineage lookup.
   * Assets with more downstream dependencies are ranked higher (higher impact if unowned).
   */
  @VisibleForTesting
  List<Map<String, Object>> rankByDownstreamCount(
      List<Map<String, Object>> unownedAssets,
      String entityType,
      McpEntityBridge.LineageRepositoryProvider lineageProvider) {

    for (Map<String, Object> asset : unownedAssets) {
      String fqn = (String) asset.get("fullyQualifiedName");
      if (fqn == null) {
        asset.put("downstreamCount", 0);
        continue;
      }
      try {
        var lineageRepo = lineageProvider.getLineageRepository();
        if (lineageRepo == null) {
          LOG.warn(
              "Lineage repository not initialized — cannot rank '{}' by downstream count", fqn);
          asset.put("downstreamCount", 0);
          continue;
        }
        Map<String, Object> lineageData =
            JsonUtils.getMap(lineageRepo.getByName(entityType, fqn, 0, 1));
        int downstreamCount = countDownstreamNodes(lineageData, fqn);
        asset.put("downstreamCount", downstreamCount);
      } catch (Exception e) {
        LOG.debug("Could not fetch lineage for '{}': {}", fqn, e.getMessage());
        asset.put("downstreamCount", 0);
      }
    }

    // Sort descending by downstreamCount
    unownedAssets.sort(
        (a, b) -> {
          int countA = ((Number) a.getOrDefault("downstreamCount", 0)).intValue();
          int countB = ((Number) b.getOrDefault("downstreamCount", 0)).intValue();
          return Integer.compare(countB, countA);
        });

    return unownedAssets;
  }

  /** Counts downstream nodes in lineage data, excluding the source entity. */
  @VisibleForTesting
  static int countDownstreamNodes(Map<String, Object> lineageData, String sourceFqn) {
    int count = 0;
    Object nodesObj = lineageData.get("nodes");
    if (nodesObj instanceof Map<?, ?> nodesMap) {
      for (Map.Entry<?, ?> entry : nodesMap.entrySet()) {
        Object nodeObj = entry.getValue();
        if (nodeObj instanceof Map<?, ?> node) {
          String nodeFqn = (String) ((Map<?, ?>) node).get("fullyQualifiedName");
          if (nodeFqn != null && !nodeFqn.equals(sourceFqn)) {
            count++;
          }
        }
      }
    } else if (nodesObj instanceof Set<?> nodes) {
      for (Object node : nodes) {
        if (node instanceof Map<?, ?> nodeMap) {
          String nodeFqn = (String) nodeMap.get("fullyQualifiedName");
          if (nodeFqn != null && !nodeFqn.equals(sourceFqn)) {
            count++;
          }
        }
      }
    }
    return count;
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(
      String entityType,
      String scopeType,
      String scopeValue,
      int totalFound,
      List<Map<String, Object>> topAssets) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Unowned Assets Report\n\n");

    if (scopeType != null && scopeValue != null) {
      sb.append("**Scope:** ").append(scopeType).append(": `").append(scopeValue).append("`\n\n");
    }

    sb.append("**Entity type:** ").append(entityType).append("\n\n");
    sb.append("**Total unowned:** ").append(totalFound).append("\n\n");

    if (topAssets.isEmpty()) {
      sb.append("All assets in this scope have owners assigned. 🎉\n");
      return sb.toString();
    }

    sb.append("### Top Priority (by downstream impact)\n\n");
    int shown = Math.min(topAssets.size(), 5);
    for (int i = 0; i < shown; i++) {
      Map<String, Object> asset = topAssets.get(i);
      String fqn = (String) asset.getOrDefault("fullyQualifiedName", "unknown");
      int downstreamCount = ((Number) asset.getOrDefault("downstreamCount", 0)).intValue();
      sb.append(i + 1)
          .append(". `")
          .append(fqn)
          .append("` — ")
          .append(downstreamCount)
          .append(" downstream")
          .append(downstreamCount == 1 ? "" : "s")
          .append("\n");
    }

    if (totalFound > shown) {
      sb.append("\n... and ").append(totalFound - shown).append(" more.\n");
    }

    sb.append("\n### Recommendation\n");
    sb.append("Use `suggest_owner_for` to identify the best owner for each asset, ");
    sb.append("then `draft_ownership_patch` to prepare the ownership assignment for review.");

    return sb.toString();
  }

  // ====================== Byte cap enforcement ======================

  private static Map<String, Object> enforceByteCap(Map<String, Object> result) {
    String json = JsonUtils.pojoToJson(result);
    if (json == null
        || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES) {
      return result;
    }

    // Truncate results list
    Object resultsObj = result.get("results");
    if (resultsObj instanceof List<?> resultsList && resultsList.size() > 5) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> truncated =
          new ArrayList<>((List<Map<String, Object>>) resultsList).subList(0, 5);
      result.put("results", truncated);

      @SuppressWarnings("unchecked")
      List<String> warnings =
          result.get("warnings") instanceof List
              ? new ArrayList<>((List<String>) result.get("warnings"))
              : new ArrayList<>();
      warnings.add("resultsTruncated: payload exceeded 8KB, showing top 5 only");
      result.put("warnings", warnings);
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

  private static String escapeJson(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
