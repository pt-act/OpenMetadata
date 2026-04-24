package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.search.vector.OpenSearchVectorService;
import org.openmetadata.service.search.vector.utils.DTOs.VectorSearchResponse;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class SemanticSearchTool implements McpTool {

  private static final int DEFAULT_SIZE = 10;
  private static final int MAX_SIZE = 50;
  private static final int DEFAULT_K = 100;
  private static final int MAX_K = 10_000;
  private static final double DEFAULT_THRESHOLD = 0.0;
  private static final int DESCRIPTION_MAX_LENGTH = 500;
  private static final int DESCRIPTION_TRUNCATE_LENGTH = 450;

  /** Known filter keys that are recognized by semantic search. Unknown keys are tracked
   * in ignoredFilters for transparency. Zero-coupling: unknown keys are not rejected,
   * just reported — forward-compatible with new filter keys being added to the backend. */
  private static final Set<String> KNOWN_FILTER_KEYS = Set.of("entityType", "service", "tags");

  /**
   * Production call — creates default bridge interfaces that delegate to {@link
   * org.openmetadata.service.Entity} static methods.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {
    return execute(
        params,
        McpEntityBridge.defaultSearchRepositoryProvider(),
        McpEntityBridge.defaultVectorServiceProvider());
  }

  /**
   * Test-friendly overload — accepts injected {@link McpEntityBridge.SearchRepositoryProvider}
   * and {@link McpEntityBridge.VectorServiceProvider} to eliminate the need for {@code
   * mockStatic(Entity.class)} and {@code mockStatic(OpenSearchVectorService.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider,
      McpEntityBridge.VectorServiceProvider vectorServiceProvider)
      throws IOException {
    LOG.info("Executing semanticSearch with params: {}", params);

    String query = (String) params.get("query");
    if (query == null || query.isBlank()) {
      return errorResponse("'query' parameter is required");
    }

    var searchRepo = searchRepoProvider.getSearchRepository();
    if (searchRepo == null) {
      return errorResponse(
          "Search repository is not initialized. Please check OpenMetadata server configuration.");
    }

    if (!searchRepo.isVectorEmbeddingEnabled()) {
      return errorResponse(
          "Semantic search is not enabled. Configure vector embeddings in the OpenMetadata server settings.");
    }

    OpenSearchVectorService vectorService = vectorServiceProvider.getVectorService();
    if (vectorService == null) {
      return errorResponse("Vector search service is not initialized");
    }

    int size = parseIntParam(params, "size", DEFAULT_SIZE);
    size = Math.min(Math.max(size, 1), MAX_SIZE);

    int from = parseIntParam(params, "from", 0);
    from = Math.max(from, 0);

    int k = parseIntParam(params, "k", DEFAULT_K);
    k = Math.min(Math.max(k, 1), MAX_K);

    double threshold = parseDoubleParam(params, "threshold", DEFAULT_THRESHOLD);
    threshold = Math.min(Math.max(threshold, 0.0), 1.0);

    Map<String, List<String>> filters = parseFilters(params);
    List<String> ignoredFilters = computeIgnoredFilters(params);

    try {
      VectorSearchResponse response =
          vectorService.search(query, filters, size, from, k, threshold);
      return buildResponse(query, response, size, from, ignoredFilters);
    } catch (Exception e) {
      LOG.error("Semantic search failed: {}", e.getMessage(), e);
      return errorResponse("Semantic search failed: " + e.getMessage());
    }
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException(
        "SemanticSearchTool does not support limits enforcement.");
  }

  private Map<String, Object> buildResponse(
      String query,
      VectorSearchResponse response,
      int requestedSize,
      int from,
      List<String> ignoredFilters) {

    if (response.getHits() == null || response.getHits().isEmpty()) {
      Map<String, Object> result =
          new HashMap<>(
              EnvelopeBuilder.create()
                  .results(Collections.emptyList())
                  .pagination(from, requestedSize, 0)
                  .build());
      result.put("query", query);
      result.put("tookMillis", response.getTookMillis());
      result.put("totalFound", 0);
      result.put("returnedCount", 0);
      result.put("message", "No results found for semantic search");
      return result;
    }

    List<Map<String, Object>> cleanedResults = new ArrayList<>();
    for (Map<String, Object> hit : response.getHits()) {
      cleanedResults.add(cleanHit(hit));
    }

    // Build envelope response (E1.8 — Expansions spec R1.4/R1.5)
    // Convert ignoredFilters to warnings for the envelope, keeping top-level ignoredFilters
    // for backward compatibility (compatibility shim in EnvelopeBuilder handles this)
    List<String> warningList = new ArrayList<>();
    for (String ignored : ignoredFilters) {
      warningList.add("ignoredFilter: " + ignored);
    }

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(cleanedResults)
            .pagination(from, requestedSize, cleanedResults.size())
            .warnings(warningList);

    // Backward-compat fields kept for existing consumers
    Map<String, Object> result = new HashMap<>(envelope.build());
    result.put("query", query);
    result.put("tookMillis", response.getTookMillis());
    result.put("totalFound", cleanedResults.size());
    result.put("returnedCount", cleanedResults.size());
    result.put(
        "usage",
        "To get full details for any result, call get_entity_details with the result's exact 'entityType' and 'fullyQualifiedName' values.");

    if (cleanedResults.size() == requestedSize) {
      result.put(
          "message",
          String.format(
              "Showing %d results. There may be more available — increase 'size' to see more.",
              requestedSize));
    }

    if (!ignoredFilters.isEmpty()) {
      result.put(
          "ignoredFiltersMessage",
          String.format(
              "The following filter keys are not recognized by semantic search and were ignored: %s. "
                  + "Supported keys: entityType, service, tags.",
              String.join(", ", ignoredFilters)));
    }

    return result;
  }

  private Map<String, Object> cleanHit(Map<String, Object> hit) {
    Map<String, Object> cleaned = new HashMap<>();

    copyIfPresent(hit, cleaned, "parentId");
    copyIfPresent(hit, cleaned, "entityType");
    copyIfPresent(hit, cleaned, "fullyQualifiedName");
    copyIfPresent(hit, cleaned, "name");
    copyIfPresent(hit, cleaned, "displayName");
    copyIfPresent(hit, cleaned, "serviceType");
    copyIfPresent(hit, cleaned, "service");
    copyIfPresent(hit, cleaned, "database");
    copyIfPresent(hit, cleaned, "databaseSchema");
    copyIfPresent(hit, cleaned, "owners");
    copyIfPresent(hit, cleaned, "tier");
    copyIfPresent(hit, cleaned, "tags");
    copyIfPresent(hit, cleaned, "domains");
    copyIfPresent(hit, cleaned, "columns");
    copyIfPresent(hit, cleaned, "certification");

    if (hit.containsKey("_score")) {
      cleaned.put("similarityScore", hit.get("_score"));
    }

    if (hit.containsKey("description")) {
      Object descObj = hit.get("description");
      if (descObj instanceof String desc && desc.length() > DESCRIPTION_MAX_LENGTH) {
        cleaned.put("description", desc.substring(0, DESCRIPTION_TRUNCATE_LENGTH) + "...");
      } else {
        cleaned.put("description", descObj);
      }
    }

    return cleaned;
  }

  private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
    if (source.containsKey(key)) {
      target.put(key, source.get(key));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<String>> parseFilters(Map<String, Object> params) {
    if (!params.containsKey("filters")) {
      return Collections.emptyMap();
    }

    Object filtersObj = params.get("filters");
    if (filtersObj instanceof Map) {
      Map<String, Object> rawFilters = (Map<String, Object>) filtersObj;
      Map<String, List<String>> filters = new HashMap<>();
      for (Map.Entry<String, Object> entry : rawFilters.entrySet()) {
        if (entry.getValue() instanceof List) {
          filters.put(entry.getKey(), (List<String>) entry.getValue());
        } else if (entry.getValue() instanceof String) {
          filters.put(entry.getKey(), List.of((String) entry.getValue()));
        }
      }
      return filters;
    }

    if (filtersObj instanceof String filterStr) {
      try {
        Map<String, Object> parsed = JsonUtils.readValue(filterStr, Map.class);
        Map<String, List<String>> filters = new HashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
          if (entry.getValue() instanceof List) {
            filters.put(entry.getKey(), (List<String>) entry.getValue());
          } else if (entry.getValue() instanceof String) {
            filters.put(entry.getKey(), List.of((String) entry.getValue()));
          }
        }
        return filters;
      } catch (Exception e) {
        LOG.warn("Failed to parse filters string: {}", filterStr, e);
      }
    }

    return Collections.emptyMap();
  }

  private int parseIntParam(Map<String, Object> params, String key, int defaultValue) {
    if (!params.containsKey(key)) {
      return defaultValue;
    }
    Object val = params.get(key);
    if (val instanceof Number number) {
      return number.intValue();
    }
    if (val instanceof String string) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  private double parseDoubleParam(Map<String, Object> params, String key, double defaultValue) {
    if (!params.containsKey(key)) {
      return defaultValue;
    }
    Object val = params.get(key);
    if (val instanceof Number number) {
      return number.doubleValue();
    }
    if (val instanceof String string) {
      try {
        return Double.parseDouble(string);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  /**
   * Computes filter keys provided by the caller that are not in the known set.
   * These are reported in the response as ignoredFilters for transparency,
   * without rejecting the request (zero-coupling approach).
   */
  @SuppressWarnings("unchecked")
  private List<String> computeIgnoredFilters(Map<String, Object> params) {
    if (!params.containsKey("filters")) {
      return Collections.emptyList();
    }
    Object filtersObj = params.get("filters");
    Map<String, Object> rawFilters = null;
    if (filtersObj instanceof Map) {
      rawFilters = (Map<String, Object>) filtersObj;
    } else if (filtersObj instanceof String filterStr) {
      try {
        rawFilters = JsonUtils.readValue(filterStr, Map.class);
      } catch (Exception e) {
        return Collections.emptyList();
      }
    }
    if (rawFilters == null) {
      return Collections.emptyList();
    }
    List<String> ignored = new ArrayList<>();
    for (String key : rawFilters.keySet()) {
      if (!KNOWN_FILTER_KEYS.contains(key)) {
        ignored.add(key);
      }
    }
    return ignored;
  }

  private Map<String, Object> errorResponse(String message) {
    Map<String, Object> result = new HashMap<>();
    result.put("results", Collections.emptyList());
    result.put("totalFound", 0);
    result.put("returnedCount", 0);
    result.put("error", message);
    return result;
  }
}
