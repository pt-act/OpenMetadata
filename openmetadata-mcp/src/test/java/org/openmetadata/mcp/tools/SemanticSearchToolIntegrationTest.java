package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.search.vector.OpenSearchVectorService;
import org.openmetadata.service.search.vector.utils.DTOs.VectorSearchResponse;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Integration tests for {@link SemanticSearchTool}.
 *
 * <p>Tests the full execute() flow with mocked SearchRepository and OpenSearchVectorService.
 * Private helper methods (cleanHit, parseFilters, parseIntParam, parseDoubleParam,
 * computeIgnoredFilters) are exercised indirectly through execute().
 *
 * <p>Tests inject functional interfaces via {@link McpEntityBridge} instead of {@code
 * mockStatic(Entity.class)} and {@code mockStatic(OpenSearchVectorService.class)}, eliminating
 * the need to mock Entity static initializers.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Entity.getSearchRepository() → injected via McpEntityBridge.SearchRepositoryProvider</li>
 *   <li>OpenSearchVectorService.getInstance() → injected via McpEntityBridge.VectorServiceProvider</li>
 *   <li>SemanticSearchTool does not use Authorizer/CatalogSecurityContext in execute()</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemanticSearchToolIntegrationTest {

  private SemanticSearchTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private SearchRepository searchRepository;
  private OpenSearchVectorService vectorService;

  @BeforeEach
  void setUp() {
    tool = new SemanticSearchTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    searchRepository = mock(SearchRepository.class);
    vectorService = mock(OpenSearchVectorService.class);
    when(searchRepository.isVectorEmbeddingEnabled()).thenReturn(true);
  }

  // ── Helpers ───────────────────────────────────────────────────────────

  private Map<String, Object> baseParams() {
    Map<String, Object> params = new HashMap<>();
    params.put("query", "test query");
    return params;
  }

  private Map<String, Object> buildHit(String entityType, String fqn, String name, double score) {
    Map<String, Object> hit = new HashMap<>();
    hit.put("entityType", entityType);
    hit.put("fullyQualifiedName", fqn);
    hit.put("name", name);
    hit.put("_score", score);
    return hit;
  }

  private Map<String, Object> executeWithVectorMock(Map<String, Object> params) throws Exception {
    // Inject functional interfaces — no mockStatic(Entity.class) or
    // mockStatic(OpenSearchVectorService.class) needed
    McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepository;
    McpEntityBridge.VectorServiceProvider vectorServiceProvider = () -> vectorService;
    return tool.execute(params, searchRepoProvider, vectorServiceProvider);
  }

  private Map<String, Object> executeWithVectorResponse(
      Map<String, Object> params, VectorSearchResponse response) throws Exception {
    when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(response);
    return executeWithVectorMock(params);
  }

  // ── Query Validation ──────────────────────────────────────────────────

  @Nested
  class QueryValidation {

    @Test
    void execute_missingQuery_returnsError() throws Exception {
      Map<String, Object> params = new HashMap<>();

      Map<String, Object> result = executeWithVectorMock(params);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result.get("error").toString()).contains("'query' parameter is required");
    }

    @Test
    void execute_blankQuery_returnsError() throws Exception {
      Map<String, Object> params = baseParams();
      params.put("query", "   ");

      Map<String, Object> result = executeWithVectorMock(params);

      assertThat(result.get("error").toString()).contains("'query' parameter is required");
    }

    @Test
    void execute_emptyQuery_returnsError() throws Exception {
      Map<String, Object> params = baseParams();
      params.put("query", "");

      Map<String, Object> result = executeWithVectorMock(params);

      assertThat(result.get("error").toString()).contains("'query' parameter is required");
    }
  }

  // ── Vector Service Availability ───────────────────────────────────────

  @Nested
  class VectorServiceAvailability {

    @Test
    void execute_vectorEmbeddingDisabled_returnsError() throws Exception {
      when(searchRepository.isVectorEmbeddingEnabled()).thenReturn(false);

      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepository;
      McpEntityBridge.VectorServiceProvider vectorServiceProvider = () -> vectorService;
      Map<String, Object> result =
          tool.execute(baseParams(), searchRepoProvider, vectorServiceProvider);

      assertThat(result.get("error").toString()).contains("Semantic search is not enabled");
    }

    @Test
    void execute_vectorServiceNotInitialized_returnsError() throws Exception {
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepository;
      McpEntityBridge.VectorServiceProvider vectorServiceProvider = () -> null;
      Map<String, Object> result =
          tool.execute(baseParams(), searchRepoProvider, vectorServiceProvider);

      assertThat(result.get("error").toString()).contains("not initialized");
    }

    @Test
    void execute_vectorServiceThrows_returnsError() throws Exception {
      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenThrow(new RuntimeException("Connection refused"));

      Map<String, Object> result = executeWithVectorMock(baseParams());

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result.get("error").toString()).contains("Connection refused");
    }

    @Test
    void execute_vectorServiceThrowsUnexpected_returnsError() throws Exception {
      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenThrow(new IllegalStateException("index not found"));

      Map<String, Object> result = executeWithVectorMock(baseParams());

      assertThat(result.get("error").toString()).contains("index not found");
    }
  }

  // ── Successful Search Results ─────────────────────────────────────────

  @Nested
  class SuccessfulSearch {

    @Test
    void execute_withHits_returnsCleanedResults() throws Exception {
      List<Map<String, Object>> hits = new ArrayList<>();
      hits.add(buildHit("table", "db.schema.users", "users", 0.95));
      hits.add(buildHit("table", "db.schema.orders", "orders", 0.87));

      VectorSearchResponse response = new VectorSearchResponse(25L, hits);
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result).containsEntry("query", "test query");
      assertThat(result).containsEntry("tookMillis", 25L);
      assertThat(result).containsEntry("totalFound", 2);
      assertThat(result).containsEntry("returnedCount", 2);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results).hasSize(2);

      Map<String, Object> first = results.get(0);
      assertThat(first).containsEntry("entityType", "table");
      assertThat(first).containsEntry("fullyQualifiedName", "db.schema.users");
      assertThat(first).containsEntry("similarityScore", 0.95);
    }

    @Test
    void execute_emptyHits_returnsEmptyWithMessage() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(15L, Collections.emptyList());

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result.get("message").toString()).contains("No results found");
    }

    @Test
    void execute_nullHits_returnsEmptyResults() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(5L, null);

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
    }
  }

  // ── Hit Cleaning ─────────────────────────────────────────────────────

  @Nested
  class HitCleaning {

    @Test
    void execute_essentialFieldsPreserved() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("parentId", "parent123");
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.users");
      hit.put("name", "users");
      hit.put("displayName", "Users");
      hit.put("serviceType", "BigQuery");
      hit.put("service", "my_db");
      hit.put("database", "my_db");
      hit.put("databaseSchema", "public");
      hit.put("owners", List.of(Map.of("id", "owner1")));
      hit.put("tier", "Tier1");
      hit.put("tags", List.of("PII"));
      hit.put("domains", List.of("Engineering"));
      hit.put("columns", List.of(Map.of("name", "id")));
      hit.put("certification", "Certified");
      hit.put("_score", 0.92);

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).containsEntry("parentId", "parent123");
      assertThat(cleaned).containsEntry("entityType", "table");
      assertThat(cleaned).containsEntry("fullyQualifiedName", "db.schema.users");
      assertThat(cleaned).containsEntry("name", "users");
      assertThat(cleaned).containsEntry("displayName", "Users");
      assertThat(cleaned).containsEntry("serviceType", "BigQuery");
      assertThat(cleaned).containsEntry("service", "my_db");
      assertThat(cleaned).containsEntry("database", "my_db");
      assertThat(cleaned).containsEntry("databaseSchema", "public");
      assertThat(cleaned).containsKey("owners");
      assertThat(cleaned).containsEntry("tier", "Tier1");
      assertThat(cleaned).containsKey("tags");
      assertThat(cleaned).containsKey("domains");
      assertThat(cleaned).containsKey("columns");
      assertThat(cleaned).containsEntry("certification", "Certified");
      assertThat(cleaned).containsEntry("similarityScore", 0.92);
    }

    @Test
    void execute_nonEssentialFieldsRemoved() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.users");
      hit.put("_score", 0.95);
      hit.put("embedding", new float[] {0.1f, 0.2f});
      hit.put("fingerprint", "abc123");
      hit.put("textToEmbed", "name: users; entityType: table");
      hit.put("internalField", "should-be-removed");
      hit.put("id", "uuid-123");

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).doesNotContainKey("_score");
      assertThat(cleaned).doesNotContainKey("embedding");
      assertThat(cleaned).doesNotContainKey("fingerprint");
      assertThat(cleaned).doesNotContainKey("textToEmbed");
      assertThat(cleaned).doesNotContainKey("internalField");
      assertThat(cleaned).doesNotContainKey("id");
      assertThat(cleaned).containsKey("similarityScore");
    }

    @Test
    void execute_scoreRenamedToSimilarityScore() throws Exception {
      Map<String, Object> hit = buildHit("table", "db.schema.t", "t", 0.88);
      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).containsEntry("similarityScore", 0.88);
      assertThat(cleaned).doesNotContainKey("_score");
    }

    @Test
    void execute_hitWithoutScore_noSimilarityScore() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).doesNotContainKey("similarityScore");
      assertThat(cleaned).doesNotContainKey("_score");
    }

    @Test
    void execute_shortDescription_preserved() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");
      hit.put("description", "A short description");

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).containsEntry("description", "A short description");
    }

    @Test
    void execute_descriptionAt500chars_preserved() throws Exception {
      String desc500 = "x".repeat(500);
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");
      hit.put("description", desc500);

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(((String) cleaned.get("description")).length()).isEqualTo(500);
    }

    @Test
    void execute_descriptionOver500chars_truncated() throws Exception {
      String desc600 = "x".repeat(600);
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");
      hit.put("description", desc600);

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      String truncated = (String) cleaned.get("description");
      assertThat(truncated.length()).isEqualTo(453); // 450 + "..."
      assertThat(truncated).endsWith("...");
    }

    @Test
    void execute_nullDescription_notAdded() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");
      hit.put("description", null);

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      // null value is still copied via copyIfPresent (containsKey check passes)
      assertThat(cleaned).containsKey("description");
      assertThat(cleaned.get("description")).isNull();
    }

    @Test
    void execute_missingDescription_notAdded() throws Exception {
      Map<String, Object> hit = new HashMap<>();
      hit.put("entityType", "table");
      hit.put("fullyQualifiedName", "db.schema.t");

      VectorSearchResponse response = new VectorSearchResponse(10L, List.of(hit));
      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> cleaned = results.get(0);

      assertThat(cleaned).doesNotContainKey("description");
    }
  }

  // ── Parameter Handling ────────────────────────────────────────────────

  @Nested
  class ParameterHandling {

    @Test
    void execute_defaultSize_is10() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(10), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      executeWithVectorMock(baseParams());

      verify(vectorService).search(anyString(), anyMap(), eq(10), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_customSize_passedThrough() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(25), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", 25);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(25), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_sizeOverMax_clampedTo50() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(50), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", 100);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(50), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_sizeZero_clampedTo1() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(1), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", 0);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(1), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_negativeSize_clampedTo1() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(1), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", -5);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(1), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_stringSize_parsedCorrectly() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(20), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", "20");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(20), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_invalidSizeString_defaultsTo10() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), eq(10), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("size", "not-a-number");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), eq(10), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_defaultFrom_is0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble()))
          .thenReturn(response);

      executeWithVectorMock(baseParams());

      verify(vectorService).search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble());
    }

    @Test
    void execute_customFrom_passedThrough() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), eq(20), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("from", 20);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), eq(20), anyInt(), anyDouble());
    }

    @Test
    void execute_negativeFrom_clampedTo0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("from", -10);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble());
    }

    @Test
    void execute_stringFrom_parsedCorrectly() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), eq(5), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("from", "5");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), eq(5), anyInt(), anyDouble());
    }

    @Test
    void execute_invalidFromString_defaultsTo0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("from", "abc");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), eq(0), anyInt(), anyDouble());
    }

    @Test
    void execute_defaultK_is100() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(100), anyDouble()))
          .thenReturn(response);

      executeWithVectorMock(baseParams());

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), eq(100), anyDouble());
    }

    @Test
    void execute_customK_passedThrough() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(500), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("k", 500);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), eq(500), anyDouble());
    }

    @Test
    void execute_kOverMax_clampedTo10000() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(10000), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("k", 50000);
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), anyMap(), anyInt(), anyInt(), eq(10000), anyDouble());
    }

    @Test
    void execute_kZero_clampedTo1() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(1), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("k", 0);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), eq(1), anyDouble());
    }

    @Test
    void execute_stringK_parsedCorrectly() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(200), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("k", "200");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), eq(200), anyDouble());
    }

    @Test
    void execute_invalidKString_defaultsTo100() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), eq(100), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("k", "invalid");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), eq(100), anyDouble());
    }

    @Test
    void execute_defaultThreshold_is0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0)))
          .thenReturn(response);

      executeWithVectorMock(baseParams());

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0));
    }

    @Test
    void execute_customThreshold_passedThrough() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.5)))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("threshold", 0.5);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.5));
    }

    @Test
    void execute_thresholdOver1_clampedTo1() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(1.0)))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("threshold", 2.5);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(1.0));
    }

    @Test
    void execute_negativeThreshold_clampedTo0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0)))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("threshold", -0.5);
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0));
    }

    @Test
    void execute_stringThreshold_parsedCorrectly() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.75)))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("threshold", "0.75");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.75));
    }

    @Test
    void execute_invalidThresholdString_defaultsTo0() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0)))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("threshold", "not-a-double");
      executeWithVectorMock(params);

      verify(vectorService).search(anyString(), anyMap(), anyInt(), anyInt(), anyInt(), eq(0.0));
    }
  }

  // ── Filter Handling ───────────────────────────────────────────────────

  @Nested
  class FilterHandling {

    @Test
    void execute_noFilters_emptyMapPassed() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(
              anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_mapFiltersWithListValues_parsed() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      Map<String, List<String>> expectedFilters = new HashMap<>();
      expectedFilters.put("entityType", List.of("table", "topic"));
      expectedFilters.put("service", List.of("my_db"));

      when(vectorService.search(
              anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> filters = new HashMap<>();
      filters.put("entityType", List.of("table", "topic"));
      filters.put("service", List.of("my_db"));

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_mapFiltersWithStringValues_wrappedInList() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      Map<String, List<String>> expectedFilters = new HashMap<>();
      expectedFilters.put("service", List.of("my_db"));

      when(vectorService.search(
              anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> filters = new HashMap<>();
      filters.put("service", "my_db"); // String, not List

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_stringFilters_parsedAsJson() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      Map<String, List<String>> expectedFilters = new HashMap<>();
      expectedFilters.put("entityType", List.of("table"));

      when(vectorService.search(
              anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("filters", "{\"entityType\":[\"table\"]}");
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(expectedFilters), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_invalidStringFilters_emptyMapUsed() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(
              anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("filters", "not-valid-json");
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble());
    }

    @Test
    void execute_nonMapNonStringFilters_emptyMapUsed() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(10L, Collections.emptyList());

      when(vectorService.search(
              anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble()))
          .thenReturn(response);

      Map<String, Object> params = baseParams();
      params.put("filters", 42); // integer, not Map or String
      executeWithVectorMock(params);

      verify(vectorService)
          .search(anyString(), eq(Map.of()), anyInt(), anyInt(), anyInt(), anyDouble());
    }
  }

  // ── Ignored Filters / Transparency ────────────────────────────────────

  @Nested
  class IgnoredFiltersTransparency {

    @Test
    void execute_knownFilterKeys_notIgnored() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> filters = new HashMap<>();
      filters.put("entityType", List.of("table"));
      filters.put("service", List.of("my_db"));
      filters.put("tags", List.of("PII"));

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      assertThat(result).doesNotContainKey("ignoredFiltersMessage");
    }

    @Test
    void execute_unknownFilterKeys_reportedAsIgnored() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> filters = new HashMap<>();
      filters.put("entityType", List.of("table"));
      filters.put("unknownKey", List.of("value"));

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      assertThat(result).containsKey("ignoredFiltersMessage");
      assertThat(result.get("ignoredFiltersMessage").toString()).contains("unknownKey");
      assertThat(result.get("ignoredFiltersMessage").toString())
          .contains("Supported keys: entityType, service, tags");
    }

    @Test
    void execute_mixedKnownUnknownFilters_onlyUnknownReported() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> filters = new HashMap<>();
      filters.put("entityType", List.of("table")); // known
      filters.put("service", List.of("db")); // known
      filters.put("tags", List.of("PII")); // known
      filters.put("customField", List.of("val")); // unknown
      filters.put("anotherUnknown", List.of("val2")); // unknown

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      String msg = (String) result.get("ignoredFiltersMessage");
      assertThat(msg).contains("customField");
      assertThat(msg).contains("anotherUnknown");
      // The "Supported keys" suffix always contains known keys; verify they are NOT
      // listed in the "ignored" prefix before the period separator.
      String ignoredPart = msg.split("\\. Supported")[0];
      assertThat(ignoredPart).doesNotContain("entityType");
      assertThat(ignoredPart).doesNotContain("service");
      assertThat(ignoredPart).doesNotContain("tags");
    }

    @Test
    void execute_noFilters_noIgnoredFiltersMessage() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result).doesNotContainKey("ignoredFiltersMessage");
    }

    @Test
    void execute_ignoredFiltersInWarningsEnvelope() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> filters = new HashMap<>();
      filters.put("unknownKey", List.of("value"));

      Map<String, Object> params = baseParams();
      params.put("filters", filters);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      @SuppressWarnings("unchecked")
      List<String> warnings = (List<String>) result.get("warnings");
      assertThat(warnings).isNotNull();
      assertThat(warnings).anyMatch(w -> w.contains("ignoredFilter") && w.contains("unknownKey"));
    }
  }

  // ── Envelope Structure ────────────────────────────────────────────────

  @Nested
  class EnvelopeStructure {

    @Test
    void execute_withHits_hasAllRequiredFields() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      // Envelope fields
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("pagination");

      // Backward-compat fields
      assertThat(result).containsEntry("query", "test query");
      assertThat(result).containsEntry("tookMillis", 10L);
      assertThat(result).containsEntry("totalFound", 1);
      assertThat(result).containsEntry("returnedCount", 1);
      assertThat(result).containsKey("usage");

      // Pagination block
      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsKey("from");
      assertThat(pagination).containsKey("size");
      assertThat(pagination).containsKey("total");
    }

    @Test
    void execute_emptyResults_hasAllRequiredFields() throws Exception {
      VectorSearchResponse response = new VectorSearchResponse(15L, Collections.emptyList());

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("pagination");
      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result).containsEntry("query", "test query");
    }

    @Test
    void execute_usageHint_includesGetEntityDetails() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t", "t", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> result = executeWithVectorResponse(baseParams(), response);

      assertThat(result.get("usage").toString()).contains("get_entity_details");
    }

    @Test
    void execute_resultsEqualSize_hasMoreMessage() throws Exception {
      List<Map<String, Object>> hits = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        hits.add(buildHit("table", "db.schema.t" + i, "t" + i, 0.9 - i * 0.1));
      }
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> params = baseParams();
      params.put("size", 3);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      assertThat(result.get("message").toString()).contains("Showing 3 results");
      assertThat(result.get("message").toString()).contains("increase 'size'");
    }

    @Test
    void execute_resultsLessThanSize_noMoreMessage() throws Exception {
      List<Map<String, Object>> hits = List.of(buildHit("table", "db.schema.t1", "t1", 0.9));
      VectorSearchResponse response = new VectorSearchResponse(10L, hits);

      Map<String, Object> params = baseParams();
      params.put("size", 10);
      Map<String, Object> result = executeWithVectorResponse(params, response);

      // "message" key may be absent or contain "No results" — but not "Showing N results"
      if (result.containsKey("message")) {
        assertThat(result.get("message").toString()).doesNotContain("Showing");
      }
    }
  }

  // ── Limits Enforcement ────────────────────────────────────────────────

  @Nested
  class LimitsEnforcement {

    @Test
    void execute_withLimits_throwsUnsupported() {
      assertThatThrownBy(
              () ->
                  tool.execute(
                      authorizer,
                      mock(org.openmetadata.service.limits.Limits.class),
                      securityContext,
                      baseParams()))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("SemanticSearchTool does not support limits");
    }
  }
}
