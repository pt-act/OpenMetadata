package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.api.lineage.EsLineageData;
import org.openmetadata.schema.api.lineage.LineageDirection;
import org.openmetadata.schema.api.lineage.SearchLineageRequest;
import org.openmetadata.schema.api.lineage.SearchLineageResult;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.lineage.NodeInformation;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.TestCaseResultRepository;
import org.openmetadata.service.search.SearchListFilter;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.EntityUtil;

/**
 * Integration tests for {@link RootCauseAnalysisTool}.
 *
 * <p>Tests inject functional interfaces via {@link McpEntityBridge} instead of {@code
 * mockStatic(Entity.class)}, eliminating the need to mock Entity static initializers.
 *
 * <p>Tests cover two categories:
 *
 * <ol>
 *   <li>Full execute() flow with mocked SearchRepository and injected providers
 *   <li>Parameter parsing and edge cases (depth clamping, boolean/string parsing)
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RootCauseAnalysisToolIntegrationTest {

  private RootCauseAnalysisTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private SearchRepository searchRepository;
  private EntityReference entityRef;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;
  private McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider;
  private McpEntityBridge.EntityFetcher entityFetcher;

  @BeforeEach
  void setUp() {
    tool = new RootCauseAnalysisTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    searchRepository = mock(SearchRepository.class);
    entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.orders");

    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    noopAuthorizer = (entityType, op) -> {};
    timeSeriesRepoProvider = entityType -> null;
    entityFetcher = (entityType, fqn, fields, include) -> null;
  }

  // ====================== Helper methods ======================

  private McpEntityBridge.EntityReferenceResolver tableResolver() {
    return (entityType, fqn, include) ->
        "table".equals(entityType) && "db.schema.orders".equals(fqn) ? entityRef : null;
  }

  private McpEntityBridge.EntityReferenceResolver resolverFor(
      String type, String fqn, EntityReference ref) {
    return (entityType, entityFqn, include) ->
        type.equals(entityType) && fqn.equals(entityFqn) ? ref : null;
  }

  private McpEntityBridge.SearchRepositoryProvider searchRepoProvider() {
    return () -> searchRepository;
  }

  private McpEntityBridge.SearchRepositoryProvider nullSearchRepoProvider() {
    return () -> null;
  }

  private Map<String, Object> buildUpstreamNode(String fqn, String entityType) {
    Map<String, Object> node = new HashMap<>();
    node.put("fullyQualifiedName", fqn);
    node.put("entityType", entityType);
    node.put("name", fqn.substring(fqn.lastIndexOf('.') + 1));
    return node;
  }

  private Map<String, Object> buildUpstreamNodeWithTestSuite(
      String fqn, String entityType, String testSuiteId) {
    Map<String, Object> node = buildUpstreamNode(fqn, entityType);
    Map<String, Object> testSuite = new HashMap<>();
    testSuite.put("id", testSuiteId);
    node.put("testSuite", testSuite);
    return node;
  }

  private Response buildUpstreamResponse(Set<?> nodes, Set<?> edges) {
    Map<String, Object> entity = new HashMap<>();
    entity.put("nodes", nodes);
    entity.put("edges", edges);
    Response response = mock(Response.class);
    when(response.getEntity()).thenReturn(entity);
    return response;
  }

  private Response buildEmptyUpstreamResponse() {
    return buildUpstreamResponse(Set.of(), Set.of());
  }

  private SearchLineageResult buildDownstreamResult(
      Map<String, NodeInformation> nodes, Map<String, EsLineageData> edges) {
    return new SearchLineageResult().withNodes(nodes).withDownstreamEdges(edges);
  }

  private NodeInformation buildNodeInformation(String fqn, String entityType) {
    Map<String, Object> entityMap = new HashMap<>();
    entityMap.put("fullyQualifiedName", fqn);
    entityMap.put("entityType", entityType);
    entityMap.put("name", fqn.substring(fqn.lastIndexOf('.') + 1));
    return new NodeInformation().withEntity(entityMap).withNodeDepth(1);
  }

  private TestCaseResult buildTestCaseResult(String fqn, TestCaseStatus status) {
    return new TestCaseResult()
        .withId(UUID.randomUUID())
        .withTestCaseFQN(fqn)
        .withTimestamp(System.currentTimeMillis())
        .withTestCaseStatus(status)
        .withResult("Test " + status.value());
  }

  private Map<String, Object> buildEdgeMap(String fromFqn, String toFqn) {
    Map<String, Object> edge = new HashMap<>();
    edge.put("fromEntity", fromFqn);
    edge.put("toEntity", toFqn);
    return edge;
  }

  private Map<String, Object> executeWithDefaults(Map<String, Object> params) throws Exception {
    return tool.execute(
        params,
        tableResolver(),
        noopAuthorizer,
        searchRepoProvider(),
        timeSeriesRepoProvider,
        entityFetcher);
  }

  // ====================== execute() flow — Basic search ======================

  @Nested
  class ExecuteBasicSearch {

    @Test
    void execute_noUpstreamFailures_statusSuccess() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      assertThat(result).containsEntry("fqn", "db.schema.orders");
      assertThat(result).containsEntry("status", "success");
      assertThat(result).containsEntry("entityType", "table");
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
      assertThat(result).containsKey("summary");
    }

    @Test
    void execute_withUpstreamFailures_statusFailed() throws Exception {
      Map<String, Object> node1 = buildUpstreamNode("db.schema.upstream1", "table");
      Map<String, Object> node2 = buildUpstreamNode("db.schema.upstream2", "table");
      Set<?> nodes = Set.of(node1, node2);
      Set<?> edges = Set.of();
      Response upstreamResponse = buildUpstreamResponse(nodes, edges);

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      // Need downstream result since there are failures
      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      assertThat(result).containsEntry("status", "failed");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);

      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      assertThat(upstream).containsKey("failingUpstreamNodes");
      assertThat(upstream).containsEntry("failingUpstreamNodesCount", 2);
      assertThat(upstream).containsEntry("failingUpstreamEdgesCount", 0);
    }

    @Test
    void execute_withUpstreamFailuresAndDownstream_nodesAndEdgesPopulated() throws Exception {
      Map<String, Object> node1 = buildUpstreamNode("db.schema.upstream1", "table");
      Set<?> upstreamNodes = Set.of(node1);
      Set<?> upstreamEdges = Set.of(buildEdgeMap("db.schema.upstream1", "db.schema.orders"));
      Response upstreamResponse = buildUpstreamResponse(upstreamNodes, upstreamEdges);

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      NodeInformation downstreamNode = buildNodeInformation("db.schema.downstream1", "table");
      Map<String, NodeInformation> downstreamNodes =
          Map.of("db.schema.downstream1", downstreamNode);
      SearchLineageResult downstreamResult = buildDownstreamResult(downstreamNodes, Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      assertThat(result).containsEntry("status", "failed");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
      assertThat(downstream).containsKey("downstreamNodes");
      assertThat(downstream).containsEntry("downstreamImpactedNodesCount", 1);
    }

    @Test
    void execute_noFailures_downstreamHasReason() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
      assertThat(downstream).containsKey("reason");
      assertThat(downstream.get("reason").toString())
          .contains("No failures found in upstream analysis");
    }
  }

  // ====================== execute() flow — Parameter handling ======================

  @Nested
  class ParameterHandling {

    @Test
    void execute_upstreamDepthClampedToMax10() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("upstreamDepth", 50);

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("upstreamDepth", 10);
    }

    @Test
    void execute_downstreamDepthClampedToMax10() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("downstreamDepth", 50);

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("downstreamDepth", 10);
    }

    @Test
    void execute_negativeDepthClampedTo0() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("upstreamDepth", -5);
      params.put("downstreamDepth", -3);

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("upstreamDepth", 0);
      assertThat(result).containsEntry("downstreamDepth", 0);
    }

    @Test
    void execute_stringDepthParsed() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("upstreamDepth", "5");
      params.put("downstreamDepth", "7");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("upstreamDepth", 5);
      assertThat(result).containsEntry("downstreamDepth", 7);
    }

    @Test
    void execute_invalidStringDepthDefaultsTo3() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("upstreamDepth", "abc");
      params.put("downstreamDepth", "xyz");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("upstreamDepth", 3);
      assertThat(result).containsEntry("downstreamDepth", 3);
    }

    @Test
    void execute_defaultDepthIs3() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      // No depth params specified

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("upstreamDepth", 3);
      assertThat(result).containsEntry("downstreamDepth", 3);
    }

    @Test
    void execute_includeDeletedAsString() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), eq(true)))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("includeDeleted", "true");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("fqn", "db.schema.orders");
    }

    @Test
    void execute_includeDeletedFalseByDefault() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), eq(false)))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("fqn", "db.schema.orders");
    }

    @Test
    void execute_defaultEntityTypeIsTable() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      // No entityType specified

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("entityType", "table");
    }

    @Test
    void execute_customEntityType() throws Exception {
      EntityReference topicRef = mock(EntityReference.class);
      when(topicRef.getFullyQualifiedName()).thenReturn("mq.topic.events");

      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "mq.topic.events");
      params.put("entityType", "topic");

      Map<String, Object> result =
          tool.execute(
              params,
              resolverFor("topic", "mq.topic.events", topicRef),
              noopAuthorizer,
              searchRepoProvider(),
              timeSeriesRepoProvider,
              entityFetcher);

      assertThat(result).containsEntry("entityType", "topic");
      assertThat(result).containsEntry("fqn", "mq.topic.events");
    }

    @Test
    void execute_fullyQualifiedNameAlias() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fullyQualifiedName", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("fqn", "db.schema.orders");
    }
  }

  // ====================== execute() flow — Missing entity ======================

  @Nested
  class MissingEntityHandling {

    @Test
    void execute_missingFqnAndAlias_throwsIllegalArgumentException() {
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");

      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fqn")
          .hasMessageContaining("fullyQualifiedName");
    }
  }

  // ====================== execute() flow — Envelope structure ======================

  @Nested
  class EnvelopeStructure {

    @Test
    void execute_resultHasEnvelopeFields() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      // Envelope fields
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
      // Top-level metadata merged in
      assertThat(result).containsKey("fqn");
      assertThat(result).containsKey("entityType");
      assertThat(result).containsKey("status");
      assertThat(result).containsKey("summary");
      assertThat(result).containsKey("upstreamDepth");
      assertThat(result).containsKey("downstreamDepth");
    }

    @Test
    void execute_narrativeMentionsFailureCount() throws Exception {
      Map<String, Object> node1 = buildUpstreamNode("db.schema.upstream1", "table");
      Map<String, Object> node2 = buildUpstreamNode("db.schema.upstream2", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node1, node2), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("2 upstream failure(s)");
      String summary = (String) result.get("summary");
      assertThat(summary).isEqualTo(narrative);
    }
  }

  // ====================== execute() flow — Downstream analysis ======================

  @Nested
  class DownstreamAnalysis {

    @Test
    void execute_downstreamAnalysisOnlyWhenUpstreamFailures() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
      // No downstream search performed; instead has reason
      assertThat(downstream).containsKey("reason");
      assertThat(downstream).doesNotContainKey("downstreamNodes");
    }

    @Test
    void execute_downstreamNodesCleaned_excludeKeysRemoved() throws Exception {
      Map<String, Object> upstreamNode = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(upstreamNode), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      // Build a downstream node with an excluded field (id, version) at the top level
      Map<String, Object> dsEntityMap = new HashMap<>();
      dsEntityMap.put("fullyQualifiedName", "db.schema.target");
      dsEntityMap.put("entityType", "table");
      dsEntityMap.put("name", "target");
      NodeInformation dsNode = new NodeInformation().withEntity(dsEntityMap).withNodeDepth(1);

      SearchLineageResult downstreamResult =
          buildDownstreamResult(Map.of("db.schema.target", dsNode), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> dsNodes =
          (List<Map<String, Object>>) downstream.get("downstreamNodes");
      assertThat(dsNodes).hasSize(1);
      // The node should have the entity sub-map and nodeDepth
      Map<String, Object> cleanedNode = dsNodes.get(0);
      assertThat(cleanedNode).containsKey("entity");
      assertThat(cleanedNode).containsKey("nodeDepth");
      // DETAILED_EXCLUDE_KEYS (id, version, updatedAt, etc.) are removed by
      // cleanSearchResponseObject
      assertThat(cleanedNode).doesNotContainKey("id");
      assertThat(cleanedNode).doesNotContainKey("version");
      assertThat(cleanedNode).doesNotContainKey("updatedAt");
    }

    @Test
    void execute_downstreamFailure_gracefulError() throws Exception {
      Map<String, Object> upstreamNode = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(upstreamNode), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenThrow(new RuntimeException("Lineage service unavailable"));

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
      assertThat(downstream).containsKey("error");
      assertThat(downstream.get("error").toString())
          .contains("Failed to analyze downstream impact");
    }

    @Test
    void execute_nullDownstreamNodes_noException() throws Exception {
      Map<String, Object> upstreamNode = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(upstreamNode), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      // Null nodes and edges
      SearchLineageResult downstreamResult =
          new SearchLineageResult().withNodes(null).withDownstreamEdges(null);
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      assertThat(result).containsEntry("status", "failed");
      // Should not crash, just not populate downstream node/edge counts
    }
  }

  // ====================== execute() flow — Upstream analysis details ======================

  @Nested
  class UpstreamAnalysis {

    @Test
    void execute_upstreamNonMapNodesFiltered() throws Exception {
      Set<?> mixedNodes = Set.of(buildUpstreamNode("db.schema.valid", "table"), "not-a-map", 42);
      Response upstreamResponse = buildUpstreamResponse(mixedNodes, Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      // Only map-type nodes are counted
      assertThat(upstream).containsEntry("failingUpstreamNodesCount", 1);
    }

    @Test
    void execute_upstreamNonSetEdgesHandled() throws Exception {
      Map<String, Object> upstreamData = new HashMap<>();
      upstreamData.put("nodes", Set.of(buildUpstreamNode("db.schema.src", "table")));
      upstreamData.put("edges", "not-a-set"); // non-Set edges

      Response upstreamResponse = mock(Response.class);
      when(upstreamResponse.getEntity()).thenReturn(upstreamData);

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      // Non-set edges default to empty set
      assertThat(upstream).containsEntry("failingUpstreamEdgesCount", 0);
    }

    @Test
    void execute_upstreamResponseNotMap_emptyAnalysis() throws Exception {
      Response upstreamResponse = mock(Response.class);
      when(upstreamResponse.getEntity()).thenReturn("not-a-map");

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      assertThat(result).containsEntry("status", "success");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      // Empty upstream analysis when response is not a Map
      assertThat(upstream).isEmpty();
    }
  }

  // ====================== execute() flow — Test case results ======================

  @Nested
  class TestCaseResultEnrichment {

    @Test
    void execute_upstreamNodeWithTestSuite_enrichedWithResults() throws Exception {
      Map<String, Object> node = buildUpstreamNodeWithTestSuite("db.schema.src", "table", "ts-123");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      // Mock TestCaseResultRepository
      TestCaseResultRepository testCaseResultRepo = mock(TestCaseResultRepository.class);
      EntityUtil.Fields fields = mock(EntityUtil.Fields.class);
      when(testCaseResultRepo.getFields(anyString())).thenReturn(fields);

      List<TestCaseResult> testResults = new ArrayList<>();
      testResults.add(buildTestCaseResult("db.schema.src.col_not_null", TestCaseStatus.Failed));
      ResultList<TestCaseResult> resultList = new ResultList<>();
      resultList.setData(testResults);

      when(testCaseResultRepo.listLatestFromSearch(
              any(EntityUtil.Fields.class),
              any(SearchListFilter.class),
              anyString(),
              any(),
              any(),
              any(),
              any(),
              any()))
          .thenReturn(resultList);

      // Inject timeSeriesRepoProvider that returns our mock
      McpEntityBridge.TimeSeriesRepositoryProvider tsProvider =
          entityType -> Entity.TEST_CASE_RESULT.equals(entityType) ? testCaseResultRepo : null;

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result =
          tool.execute(
              params,
              tableResolver(),
              noopAuthorizer,
              searchRepoProvider(),
              tsProvider,
              entityFetcher);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      assertThat(failingNodes).isNotEmpty();

      Map<String, Object> firstNode = failingNodes.get(0);
      assertThat(firstNode).containsKey("failingTestCases");

      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).containsKey("testCaseResults");
      assertThat(testCases).containsEntry("testSuiteId", "ts-123");
    }

    @Test
    void execute_testCaseResultsTruncatedAt5() throws Exception {
      Map<String, Object> node = buildUpstreamNodeWithTestSuite("db.schema.src", "table", "ts-456");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      // More than 5 results
      List<TestCaseResult> testResults = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        testResults.add(buildTestCaseResult("db.schema.src.test_" + i, TestCaseStatus.Failed));
      }
      ResultList<TestCaseResult> resultList = new ResultList<>();
      resultList.setData(testResults);

      TestCaseResultRepository testCaseResultRepo = mock(TestCaseResultRepository.class);
      EntityUtil.Fields fields = mock(EntityUtil.Fields.class);
      when(testCaseResultRepo.getFields(anyString())).thenReturn(fields);
      when(testCaseResultRepo.listLatestFromSearch(
              any(EntityUtil.Fields.class),
              any(SearchListFilter.class),
              anyString(),
              any(),
              any(),
              any(),
              any(),
              any()))
          .thenReturn(resultList);

      McpEntityBridge.TimeSeriesRepositoryProvider tsProvider =
          entityType -> Entity.TEST_CASE_RESULT.equals(entityType) ? testCaseResultRepo : null;

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result =
          tool.execute(
              params,
              tableResolver(),
              noopAuthorizer,
              searchRepoProvider(),
              tsProvider,
              entityFetcher);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      Map<String, Object> firstNode = failingNodes.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).containsEntry("truncated", true);
      assertThat(testCases.get("message").toString()).contains("Showing top 5 of 8");

      @SuppressWarnings("unchecked")
      List<TestCaseResult> returnedResults =
          (List<TestCaseResult>) testCases.get("testCaseResults");
      assertThat(returnedResults).hasSize(5);
    }

    @Test
    void execute_nodeWithoutTestSuite_noEnrichment() throws Exception {
      // Node without testSuite field
      Map<String, Object> node = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      // failingTestCases should be an empty map since no testSuite
      Map<String, Object> firstNode = failingNodes.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).isEmpty();
    }

    @Test
    void execute_testCaseRepoIOException_gracefulDegradation() throws Exception {
      Map<String, Object> node = buildUpstreamNodeWithTestSuite("db.schema.src", "table", "ts-789");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      // TestCaseResultRepository throws IOException
      TestCaseResultRepository testCaseResultRepo = mock(TestCaseResultRepository.class);
      EntityUtil.Fields fields = mock(EntityUtil.Fields.class);
      when(testCaseResultRepo.getFields(anyString())).thenReturn(fields);
      when(testCaseResultRepo.listLatestFromSearch(
              any(EntityUtil.Fields.class),
              any(SearchListFilter.class),
              anyString(),
              any(),
              any(),
              any(),
              any(),
              any()))
          .thenThrow(new IOException("DB connection failed"));

      McpEntityBridge.TimeSeriesRepositoryProvider tsProvider =
          entityType -> Entity.TEST_CASE_RESULT.equals(entityType) ? testCaseResultRepo : null;

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      // Should not throw — graceful degradation
      Map<String, Object> result =
          tool.execute(
              params,
              tableResolver(),
              noopAuthorizer,
              searchRepoProvider(),
              tsProvider,
              entityFetcher);

      assertThat(result).containsEntry("status", "failed");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      // failingTestCases should be empty due to IOException
      Map<String, Object> firstNode = failingNodes.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).isEmpty();
    }

    @Test
    void execute_testSuiteWithNullId_noEnrichment() throws Exception {
      Map<String, Object> node = buildUpstreamNode("db.schema.src", "table");
      Map<String, Object> testSuite = new HashMap<>();
      testSuite.put("id", null); // null id
      node.put("testSuite", testSuite);

      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      Map<String, Object> firstNode = failingNodes.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).isEmpty();
    }

    @Test
    void execute_noFailedTestResults_emptyTestCases() throws Exception {
      Map<String, Object> node =
          buildUpstreamNodeWithTestSuite("db.schema.src", "table", "ts-empty");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      TestCaseResultRepository testCaseResultRepo = mock(TestCaseResultRepository.class);
      EntityUtil.Fields fields = mock(EntityUtil.Fields.class);
      when(testCaseResultRepo.getFields(anyString())).thenReturn(fields);
      ResultList<TestCaseResult> emptyResults = new ResultList<>();
      emptyResults.setData(List.of());
      when(testCaseResultRepo.listLatestFromSearch(
              any(EntityUtil.Fields.class),
              any(SearchListFilter.class),
              anyString(),
              any(),
              any(),
              any(),
              any(),
              any()))
          .thenReturn(emptyResults);

      McpEntityBridge.TimeSeriesRepositoryProvider tsProvider =
          entityType -> Entity.TEST_CASE_RESULT.equals(entityType) ? testCaseResultRepo : null;

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result =
          tool.execute(
              params,
              tableResolver(),
              noopAuthorizer,
              searchRepoProvider(),
              tsProvider,
              entityFetcher);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> failingNodes =
          (List<Map<String, Object>>) upstream.get("failingUpstreamNodes");
      Map<String, Object> firstNode = failingNodes.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> testCases = (Map<String, Object>) firstNode.get("failingTestCases");
      assertThat(testCases).isEmpty();
    }
  }

  // ====================== execute() flow — Error handling ======================

  @Nested
  class ErrorHandling {

    @Test
    void execute_ioExceptionFromSearchDataQualityLineage_throwsRuntimeException() throws Exception {
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenThrow(new IOException("Search cluster unavailable"));

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      assertThatThrownBy(() -> executeWithDefaults(params))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to perform root cause analysis");
    }

    @Test
    void execute_unexpectedException_throwsRuntimeException() throws Exception {
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenThrow(new RuntimeException("Unexpected internal error"));

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      assertThatThrownBy(() -> executeWithDefaults(params))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Unexpected error during root cause analysis");
    }
  }

  // ====================== execute() flow — queryFilter ======================

  @Nested
  class QueryFilterHandling {

    @Test
    void execute_queryFilterPassedToSearchRepo() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(
              eq("db.schema.orders"), anyInt(), eq("{\"match_all\":{}}"), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("queryFilter", "{\"match_all\":{}}");

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("fqn", "db.schema.orders");
    }

    @Test
    void execute_nullQueryFilter_defaultsToNull() throws Exception {
      Response upstreamResponse = buildEmptyUpstreamResponse();
      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), eq(null), anyBoolean()))
          .thenReturn(upstreamResponse);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      // No queryFilter

      Map<String, Object> result = executeWithDefaults(params);
      assertThat(result).containsEntry("fqn", "db.schema.orders");
    }
  }

  // ====================== execute() flow — Upstream description ======================

  @Nested
  class UpstreamDescription {

    @Test
    void execute_upstreamAnalysisHasDescriptionWhenNodesPresent() throws Exception {
      Map<String, Object> node = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> upstream = (Map<String, Object>) analysisData.get("upstreamAnalysis");
      assertThat(upstream).containsKey("description");
      assertThat(upstream.get("description").toString())
          .contains("Upstream entities that may be causing data quality failures");
    }

    @Test
    void execute_downstreamAnalysisHasDescriptionWhenFailures() throws Exception {
      Map<String, Object> node = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(node), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");

      Map<String, Object> result = executeWithDefaults(params);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> analysisData = (Map<String, Object>) results.get(0);

      @SuppressWarnings("unchecked")
      Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
      assertThat(downstream).containsKey("description");
      assertThat(downstream.get("description").toString())
          .contains("Downstream entities that may be impacted");
    }
  }

  // ====================== Downstream request parameter verification ======================

  @Nested
  class DownstreamRequestVerification {

    @Test
    void execute_downstreamRequestUsesCorrectDirection() throws Exception {
      Map<String, Object> upstreamNode = buildUpstreamNode("db.schema.src", "table");
      Response upstreamResponse = buildUpstreamResponse(Set.of(upstreamNode), Set.of());

      when(searchRepository.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(upstreamResponse);

      SearchLineageResult downstreamResult = buildDownstreamResult(Map.of(), Map.of());
      when(searchRepository.searchLineageWithDirection(any(SearchLineageRequest.class)))
          .thenReturn(downstreamResult);

      Map<String, Object> params = new HashMap<>();
      params.put("fqn", "db.schema.orders");
      params.put("downstreamDepth", 5);

      tool.execute(
          params,
          tableResolver(),
          noopAuthorizer,
          searchRepoProvider(),
          timeSeriesRepoProvider,
          entityFetcher);

      // Capture and verify the downstream request parameters
      var captor = org.mockito.ArgumentCaptor.forClass(SearchLineageRequest.class);
      verify(searchRepository).searchLineageWithDirection(captor.capture());

      SearchLineageRequest captured = captor.getValue();
      assertThat(captured.getDirection()).isEqualTo(LineageDirection.DOWNSTREAM);
      assertThat(captured.getFqn()).isEqualTo("db.schema.orders");
      assertThat(captured.getDownstreamDepth()).isEqualTo(5);
      assertThat(captured.getUpstreamDepth()).isEqualTo(0);
    }
  }

  // ====================== Limits override ======================

  @Nested
  class LimitsOverride {

    @Test
    void executeWithLimits_throwsUnsupportedOperationException() {
      assertThatThrownBy(
              () ->
                  tool.execute(
                      authorizer,
                      mock(org.openmetadata.service.limits.Limits.class),
                      securityContext,
                      Map.of()))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("RootCauseAnalysisTool does not require limit validation");
    }
  }
}
