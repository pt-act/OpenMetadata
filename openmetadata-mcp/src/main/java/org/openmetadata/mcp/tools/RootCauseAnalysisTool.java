package org.openmetadata.mcp.tools;

import static org.openmetadata.mcp.tools.SearchMetadataTool.cleanSearchResponseObject;
import static org.openmetadata.service.search.SearchUtils.isConnectedVia;

import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.lineage.LineageDirection;
import org.openmetadata.schema.api.lineage.SearchLineageRequest;
import org.openmetadata.schema.api.lineage.SearchLineageResult;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.TestCaseResultRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.search.SearchListFilter;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class RootCauseAnalysisTool implements McpTool {

  private static final int MAX_DEPTH = 10;
  private static final int MAX_TEST_CASE_RESULTS_PER_SUITE = 5;

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      CatalogSecurityContext securityContext,
      Map<String, Object> parameters) {
    return execute(
        parameters,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultSearchRepositoryProvider(),
        McpEntityBridge.defaultTimeSeriesRepositoryProvider(),
        McpEntityBridge.defaultEntityFetcher());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity}
   * static method calls and authorizer delegation, eliminating the need for {@code
   * mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> parameters,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider,
      McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider,
      McpEntityBridge.EntityFetcher entityFetcher) {
    String entityType = (String) parameters.getOrDefault("entityType", "table");

    // Use resolveEntityRef for multi-form entity identification (E1.5)
    // Supports: fqn, fullyQualifiedName, id, entityLink, name+service
    EntityReference entityRef =
        ToolUtils.resolveEntityRef(parameters, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    int upstreamDepth =
        Math.min(Math.max(parseIntParam(parameters.get("upstreamDepth"), 3), 0), MAX_DEPTH);
    int downstreamDepth =
        Math.min(Math.max(parseIntParam(parameters.get("downstreamDepth"), 3), 0), MAX_DEPTH);
    String queryFilter = (String) parameters.get("queryFilter");
    boolean includeDeleted = parseBooleanParam(parameters.get("includeDeleted"), false);

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo == null) {
        LOG.warn(
            "Search repository not initialized — cannot perform root cause analysis for '{}'", fqn);
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", "Search repository not initialized");
        EnvelopeBuilder envelope =
            EnvelopeBuilder.create()
                .results(List.of(errorResult))
                .narrative(
                    "Root cause analysis could not be completed: search repository not initialized.");
        Map<String, Object> envelopeResult = new HashMap<>(envelope.build());
        envelopeResult.put("fqn", fqn);
        envelopeResult.put("entityType", entityType);
        envelopeResult.put("status", "error");
        return envelopeResult;
      }

      // Build the analysis result — domain fields only (upstream/downstream analysis).
      // Top-level metadata (fqn, entityType, status, depths) is added to the envelope below.
      Map<String, Object> result = new HashMap<>();

      Response upstreamResponse =
          searchRepo.searchDataQualityLineage(
              fqn.trim(), upstreamDepth, queryFilter, includeDeleted);

      if (upstreamResponse == null) {
        LOG.warn("Search repository returned null response for data quality lineage of '{}'", fqn);
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put(
            "error",
            "Search repository returned null response for data quality lineage of '" + fqn + "'");
        EnvelopeBuilder envelope =
            EnvelopeBuilder.create()
                .results(List.of(errorResult))
                .narrative(
                    "Root cause analysis could not be completed: search repository unavailable.");
        Map<String, Object> envelopeResult = new HashMap<>(envelope.build());
        envelopeResult.put("fqn", fqn);
        envelopeResult.put("entityType", entityType);
        envelopeResult.put("status", "error");
        return envelopeResult;
      }

      Object upstreamEntity = upstreamResponse.getEntity();
      Map<String, Object> upstreamAnalysis = new HashMap<>();
      boolean hasFailures = false;
      int failureCount = 0;

      if (upstreamEntity instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> upstreamLineageData = (Map<String, Object>) upstreamEntity;

        Set<?> upstreamEdgesList =
            upstreamLineageData.get("edges") instanceof Set<?> s ? s : Collections.emptySet();
        Set<?> upstreamNodesList =
            upstreamLineageData.get("nodes") instanceof Set<?> s ? s : Collections.emptySet();
        List<Map<String, Object>> upstreamNodes =
            upstreamNodesList.stream()
                .filter(node -> node instanceof Map)
                .map(node -> cleanSearchResponseObject((Map<String, Object>) node))
                .toList();

        failureCount = upstreamNodes.size();
        upstreamAnalysis.put("failingUpstreamNodesCount", failureCount);
        hasFailures = !upstreamNodes.isEmpty();
        if (!upstreamNodes.isEmpty()) {
          upstreamAnalysis.put("failingUpstreamNodes", upstreamNodes);
          upstreamNodes.forEach(
              node ->
                  node.put(
                      "failingTestCases",
                      addTestCaseResultForTestSuite(node, timeSeriesRepoProvider)));
        }

        upstreamAnalysis.put("failingUpstreamEdgesCount", upstreamEdgesList.size());
        upstreamAnalysis.put("failingUpstreamEdges", upstreamEdgesList);
        upstreamAnalysis.put(
            "description", "Upstream entities that may be causing data quality failures");
      }
      result.put("upstreamAnalysis", upstreamAnalysis);

      Map<String, Object> downstreamAnalysis = new HashMap<>();
      if (hasFailures) {
        try {
          SearchLineageRequest downstreamRequest =
              new SearchLineageRequest()
                  .withFqn(fqn.trim())
                  .withDirection(LineageDirection.DOWNSTREAM)
                  .withUpstreamDepth(0)
                  .withDownstreamDepth(downstreamDepth)
                  .withQueryFilter(queryFilter)
                  .withIsConnectedVia(isConnectedVia(entityType))
                  .withIncludeDeleted(includeDeleted);

          SearchLineageResult downstreamResult =
              searchRepo.searchLineageWithDirection(downstreamRequest);

          downstreamAnalysis.put(
              "description", "Downstream entities that may be impacted by the identified failures");

          if (downstreamResult.getNodes() != null) {
            List<Map<String, Object>> cleanedDownstreamNodes =
                downstreamResult.getNodes().values().stream()
                    .map(
                        node -> {
                          Map<String, Object> nodeMap = JsonUtils.getMap(node);
                          return cleanSearchResponseObject(
                              nodeMap != null ? new HashMap<>(nodeMap) : new HashMap<>());
                        })
                    .toList();
            downstreamAnalysis.put("downstreamImpactedNodesCount", cleanedDownstreamNodes.size());
            downstreamAnalysis.put("downstreamNodes", cleanedDownstreamNodes);
          }

          if (downstreamResult.getDownstreamEdges() != null) {
            List<Map<String, Object>> cleanedDownstreamEdges =
                downstreamResult.getDownstreamEdges().values().stream()
                    .map(
                        edge -> {
                          Map<String, Object> edgeMap = JsonUtils.getMap(edge);
                          return cleanSearchResponseObject(
                              edgeMap != null ? new HashMap<>(edgeMap) : new HashMap<>());
                        })
                    .toList();
            downstreamAnalysis.put("downstreamImpactedEdgesCount", cleanedDownstreamEdges.size());
            downstreamAnalysis.put("downstreamEdges", cleanedDownstreamEdges);
          }

          LOG.info(
              "Downstream impact analysis completed for entity: {} with {} downstream depth, found {} nodes and {} edges",
              fqn,
              downstreamDepth,
              downstreamResult.getNodes() != null ? downstreamResult.getNodes().size() : 0,
              downstreamResult.getDownstreamEdges() != null
                  ? downstreamResult.getDownstreamEdges().size()
                  : 0);

        } catch (Exception e) {
          LOG.warn("Failed to perform downstream impact analysis for entity: {}", fqn, e);
          downstreamAnalysis.put("error", "Failed to analyze downstream impact: " + e.getMessage());
        }
      } else {
        downstreamAnalysis.put(
            "reason",
            "No failures found in upstream analysis, downstream impact analysis not needed");
      }

      result.put("downstreamAnalysis", downstreamAnalysis);

      String narrative =
          String.format(
              "Analyzed upstream causes and downstream impacts for '%s'. Found %d upstream failure(s).",
              fqn, failureCount);

      // Wrap in envelope for consistency with other MCP tools (E1.8)
      EnvelopeBuilder envelope =
          EnvelopeBuilder.create().results(List.of(result)).narrative(narrative);
      Map<String, Object> envelopeResult = new HashMap<>(envelope.build());
      // Merge top-level fields into envelope for backward compat
      envelopeResult.put("fqn", fqn);
      envelopeResult.put("entityType", entityType);
      envelopeResult.put("status", hasFailures ? "failed" : "success");
      envelopeResult.put("summary", narrative);
      envelopeResult.put("upstreamDepth", upstreamDepth);
      envelopeResult.put("downstreamDepth", downstreamDepth);

      LOG.info(
          "Comprehensive root cause analysis completed for entity: {} - Upstream failures: {}",
          fqn,
          hasFailures);

      return envelopeResult;

    } catch (IOException e) {
      LOG.error("IOException during root cause analysis for entity: {}", fqn, e);
      throw new RuntimeException("Failed to perform root cause analysis: " + e.getMessage(), e);

    } catch (Exception e) {
      LOG.error("Unexpected error during root cause analysis for entity: {}", fqn, e);
      throw new RuntimeException(
          "Unexpected error during root cause analysis: " + e.getMessage(), e);
    }
  }

  private Map<String, Object> addTestCaseResultForTestSuite(
      Map<String, Object> node,
      McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider) {
    Map<String, Object> testCaseResult = new HashMap<>();
    Map<String, Object> testSuiteMap = JsonUtils.getMap(node.get("testSuite"));
    if (testSuiteMap == null || testSuiteMap.get("id") == null) {
      return testCaseResult;
    }
    String testSuiteId = (String) testSuiteMap.get("id");
    SearchListFilter searchListFilter = new SearchListFilter();
    searchListFilter.addQueryParam("testCaseStatus", "Failed");
    searchListFilter.addQueryParam("testSuiteId", testSuiteId);
    TestCaseResultRepository testResultTimeSeriesRepository =
        (TestCaseResultRepository)
            timeSeriesRepoProvider.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESULT);
    try {
      ResultList<TestCaseResult> testCaseResults =
          testResultTimeSeriesRepository.listLatestFromSearch(
              testResultTimeSeriesRepository.getFields("testCaseStatus,result,testResultValue"),
              searchListFilter,
              "testCaseFQN.keyword",
              null,
              null,
              null,
              null,
              null);
      if (testCaseResults.getData() != null && !testCaseResults.getData().isEmpty()) {
        List<TestCaseResult> results = testCaseResults.getData();
        if (results.size() > MAX_TEST_CASE_RESULTS_PER_SUITE) {
          results = results.subList(0, MAX_TEST_CASE_RESULTS_PER_SUITE);
          testCaseResult.put("truncated", true);
          testCaseResult.put(
              "message",
              String.format(
                  "Showing top %d of %d failed test cases per suite.",
                  MAX_TEST_CASE_RESULTS_PER_SUITE, testCaseResults.getData().size()));
        }
        testCaseResult.put("testCaseResults", results);
        testCaseResult.put("testSuiteId", testSuiteId);
      } else {
        LOG.info("No failed test case results found for test suite: {}", testSuiteId);
      }
    } catch (IOException e) {
      LOG.error("Failed to fetch test case results for test suite: {}", testSuiteId, e);
    }
    return testCaseResult;
  }

  private static int parseIntParam(Object value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
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
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      return "true".equalsIgnoreCase(s);
    }
    return defaultValue;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException(
        "RootCauseAnalysisTool does not require limit validation.");
  }
}
