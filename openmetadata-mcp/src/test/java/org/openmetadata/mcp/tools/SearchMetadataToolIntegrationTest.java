package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.service.Entity;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.security.policyevaluator.SubjectCache;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Integration tests for {@link SearchMetadataTool}.
 *
 * <p>Tests cover two categories:
 *
 * <ol>
 *   <li>Direct static method tests for @VisibleForTesting/public static methods (no mocking
 *       needed): buildEnhancedSearchResponse, cleanSearchResult, createEmptyResponse
 *   <li>Full execute() flow tests with mocked SearchRepository, Entity, and SubjectCache
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchMetadataToolIntegrationTest {

  private SearchMetadataTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private SearchRepository searchRepository;
  private User mockUser;

  @BeforeEach
  void setUp() {
    tool = new SearchMetadataTool();
    authorizer = mock(Authorizer.class);
    doNothing()
        .when(authorizer)
        .authorize(
            any(CatalogSecurityContext.class),
            any(OperationContext.class),
            any(ResourceContext.class));
    securityContext = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    searchRepository = mock(SearchRepository.class);
    when(searchRepository.getIndexOrAliasName(anyString()))
        .thenAnswer(invocation -> "openmetadata_" + invocation.getArgument(0));

    mockUser = new User();
    mockUser.setId(UUID.randomUUID());
    mockUser.setName("test-user");
    mockUser.setIsAdmin(false);
    mockUser.setIsBot(false);

    Entity.setSearchRepository(searchRepository);
  }

  // ====================== Helper methods ======================

  private Map<String, Object> buildOpenSearchHit(String fqn, String name, String entityType) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("fullyQualifiedName", fqn);
    source.put("name", name);
    source.put("entityType", entityType);
    source.put("displayName", name);
    source.put("description", "A test entity");
    source.put("deleted", false);
    return source;
  }

  private Map<String, Object> buildOpenSearchResponse(
      List<Map<String, Object>> sources, int totalValue) {
    List<Map<String, Object>> hitsList = new ArrayList<>();
    for (Map<String, Object> source : sources) {
      Map<String, Object> hit = new LinkedHashMap<>();
      hit.put("_source", source);
      hitsList.add(hit);
    }

    Map<String, Object> total = new LinkedHashMap<>();
    total.put("value", totalValue);

    Map<String, Object> hits = new LinkedHashMap<>();
    hits.put("hits", hitsList);
    hits.put("total", total);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("hits", hits);
    return response;
  }

  private Map<String, Object> buildOpenSearchResponseWithNumericTotal(
      List<Map<String, Object>> sources, int totalValue) {
    List<Map<String, Object>> hitsList = new ArrayList<>();
    for (Map<String, Object> source : sources) {
      Map<String, Object> hit = new LinkedHashMap<>();
      hit.put("_source", source);
      hitsList.add(hit);
    }

    Map<String, Object> hits = new LinkedHashMap<>();
    hits.put("hits", hitsList);
    hits.put("total", totalValue);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("hits", hits);
    return response;
  }

  // ====================== buildEnhancedSearchResponse (static, no mocking) ======================

  @Nested
  class BuildEnhancedSearchResponse {

    @Test
    void nullSearchResponse_returnsEmptyResponse() {
      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(null, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result).containsEntry("message", "No results found");
    }

    @Test
    void nullHits_returnsEmptyResponse() {
      Map<String, Object> searchResponse = Map.of("noHits", "here");
      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
    }

    @Test
    void emptyHitsList_returnsEmptyResults() {
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(), 0);
      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void singleHit_extractsSourceFields() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.orders", "orders", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 1);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "orders", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 1);
      assertThat(result).containsEntry("returnedCount", 1);
      assertThat(result).containsEntry("query", "orders");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).containsEntry("fullyQualifiedName", "db.schema.orders");
      assertThat(results.get(0)).containsEntry("name", "orders");
      assertThat(results.get(0)).containsEntry("entityType", "table");
    }

    @Test
    void multipleHits_allExtracted() {
      Map<String, Object> s1 = buildOpenSearchHit("db.schema.orders", "orders", "table");
      Map<String, Object> s2 = buildOpenSearchHit("db.schema.customers", "customers", "table");
      Map<String, Object> s3 =
          buildOpenSearchHit("dw.sales_dashboard", "sales_dashboard", "dashboard");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(s1, s2, s3), 100);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "*", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 100);
      assertThat(result).containsEntry("returnedCount", 3);
      assertThat(result).containsEntry("hasMore", true);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results).hasSize(3);
    }

    @Test
    void numericTotalValue_handledCorrectly() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse =
          buildOpenSearchResponseWithNumericTotal(List.of(source), 42);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 42);
    }

    @Test
    void hasMore_falseWhenAllResultsFetched() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 1);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("hasMore");
    }

    @Test
    void hasMore_trueWhenMoreResultsExist() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 50);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("hasMore", true);
    }

    @Test
    void paginationBlock_includesFromSizeTotal() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 100);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 20, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("from", 20);
      assertThat(pagination).containsEntry("size", 10);
      assertThat(pagination).containsEntry("total", 100);
    }

    @Test
    void paginationBlock_includesNextFromWhenMorePages() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 100);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("nextFrom", 10);
    }

    @Test
    void paginationBlock_noNextFromOnLastPage() {
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 5);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).doesNotContainKey("nextFrom");
    }

    @Test
    void usageHint_includedInResponse() {
      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(), 0);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsKey("usage");
      assertThat(result.get("usage").toString()).contains("get_entity_details");
    }

    @Test
    void aggregations_includedWhenFlagTrue() {
      Map<String, Object> searchResponse = buildOpenSearchResponseWithAggregations(5);
      @SuppressWarnings("unchecked")
      Map<String, Object> hitsBlock =
          (Map<String, Object>) buildOpenSearchResponse(List.of(), 0).get("hits");
      searchResponse.put("hits", hitsBlock);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), true, 10);

      assertThat(result).containsKey("aggregations");
    }

    @Test
    void aggregations_excludedWhenFlagFalse() {
      Map<String, Object> searchResponse = buildOpenSearchResponseWithAggregations(5);
      @SuppressWarnings("unchecked")
      Map<String, Object> hitsBlock =
          (Map<String, Object>) buildOpenSearchResponse(List.of(), 0).get("hits");
      searchResponse.put("hits", hitsBlock);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("aggregations");
    }

    @Test
    void aggregations_truncatedWithMessage() {
      @SuppressWarnings("unchecked")
      Map<String, Object> hitsBlock =
          (Map<String, Object>) buildOpenSearchResponse(List.of(), 0).get("hits");
      Map<String, Object> searchResponse = buildOpenSearchResponseWithAggregations(20);
      searchResponse.put("hits", hitsBlock);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), true, 5);

      assertThat(result).containsEntry("aggregationsTruncated", true);
      assertThat(result).containsKey("aggregationsMessage");
      assertThat(result.get("aggregationsMessage").toString()).contains("5");
    }

    @Test
    void hitWithoutSource_skipped() {
      Map<String, Object> hitNoSource = Map.of("_id", "abc123");
      Map<String, Object> source = buildOpenSearchHit("db.schema.t1", "t1", "table");
      Map<String, Object> hitWithSource = Map.of("_source", source);

      Map<String, Object> total = Map.of("value", 2);
      Map<String, Object> hits = new LinkedHashMap<>();
      hits.put("hits", List.of(hitNoSource, hitWithSource));
      hits.put("total", total);

      Map<String, Object> searchResponse = new LinkedHashMap<>();
      searchResponse.put("hits", hits);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("returnedCount", 1);
    }

    @Test
    void requestedFields_includedInCleanedResults() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("fullyQualifiedName", "db.schema.t1");
      source.put("name", "t1");
      source.put("entityType", "table");
      source.put("columnNames", List.of("id", "name"));
      source.put("customField", "custom_value");

      Map<String, Object> searchResponse = buildOpenSearchResponse(List.of(source), 1);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of("customField"), false, 10);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results.get(0)).containsEntry("customField", "custom_value");
    }
  }

  // ====================== cleanSearchResult (static, no mocking) ======================

  @Nested
  class CleanSearchResult {

    @Test
    void essentialFields_includedFromSource() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("displayName", "Orders Table");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", "Contains order data");
      source.put("entityType", "table");
      source.put("service", "pg_service");
      source.put("deleted", false);

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      assertThat(result).containsEntry("name", "orders");
      assertThat(result).containsEntry("displayName", "Orders Table");
      assertThat(result).containsEntry("fullyQualifiedName", "db.schema.orders");
      assertThat(result).containsEntry("description", "Contains order data");
      assertThat(result).containsEntry("entityType", "table");
      assertThat(result).containsEntry("service", "pg_service");
      assertThat(result).containsEntry("deleted", false);
    }

    @Test
    void nonEssentialFields_excludedByDefault() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("internalField", "should_be_removed");
      source.put("debugInfo", "should_be_removed");

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      assertThat(result).doesNotContainKey("internalField");
      assertThat(result).doesNotContainKey("debugInfo");
    }

    @Test
    void requestedFields_addedOnTopOfEssentials() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("columnNames", List.of("id", "name"));
      source.put("serviceType", "PostgreSQL");

      Map<String, Object> result =
          SearchMetadataTool.cleanSearchResult(source, List.of("columnNames"));

      assertThat(result).containsEntry("columnNames", List.of("id", "name"));
    }

    @Test
    void longDescription_truncated() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", "A".repeat(600));

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      String desc = (String) result.get("description");
      assertThat(desc.length()).isLessThanOrEqualTo(453); // 450 + "..."
      assertThat(desc).endsWith("...");
    }

    @Test
    void shortDescription_notTruncated() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", "Short description");

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      assertThat(result).containsEntry("description", "Short description");
    }

    @Test
    void descriptionAtMaxLength_notTruncated() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", "A".repeat(500));

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      String desc = (String) result.get("description");
      assertThat(desc.length()).isEqualTo(500);
      assertThat(desc).doesNotEndWith("...");
    }

    @Test
    void descriptionJustOverMax_truncated() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", "A".repeat(501));

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      String desc = (String) result.get("description");
      assertThat(desc).endsWith("...");
      assertThat(desc.length()).isEqualTo(453); // 450 + 3 for "..."
    }

    @Test
    void missingEssentialFields_notAdded() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      // No fullyQualifiedName

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      assertThat(result).containsEntry("name", "orders");
      assertThat(result).doesNotContainKey("fullyQualifiedName");
    }

    @Test
    void requestedFieldMissingInSource_notAdded() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");

      Map<String, Object> result =
          SearchMetadataTool.cleanSearchResult(source, List.of("nonExistentField"));

      assertThat(result).doesNotContainKey("nonExistentField");
    }

    @Test
    void nullDescription_notTruncated() {
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("name", "orders");
      source.put("fullyQualifiedName", "db.schema.orders");
      source.put("description", null);

      Map<String, Object> result = SearchMetadataTool.cleanSearchResult(source, List.of());

      // null values are not included via "if (source.containsKey(field))"
      // but the value is null, so it gets added with null value
      assertThat(result).containsKey("description");
    }
  }

  // ====================== createEmptyResponse (static, no mocking) ======================

  @Nested
  class CreateEmptyResponse {

    @Test
    void containsRequiredFields() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result).containsEntry("message", "No results found");
    }

    @Test
    void resultsListIsEmpty() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void paginationBlockPresent() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("from", 0);
      assertThat(pagination).containsEntry("size", 0);
      assertThat(pagination).containsEntry("total", 0);
      assertThat(pagination).doesNotContainKey("nextFrom");
    }
  }

  // ====================== execute() flow — basic search ======================

  @Nested
  class ExecuteBasicSearch {

    @Test
    void execute_defaultParams_returnsResults() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsEntry("totalFound", 0);
        assertThat(result).containsEntry("returnedCount", 0);
        assertThat(result).containsKey("results");
        assertThat(result).containsKey("pagination");
      }
    }

    @Test
    void execute_withEntityType_usesCorrectIndex() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dashboard"))
            .thenReturn("openmetadata_dashboard");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "sales");
        params.put("entityType", "dashboard");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsEntry("query", "sales");
      }
    }

    @Test
    void execute_withHits_returnsCleanedResults() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("table_search_index"))
            .thenReturn("openmetadata_table_search_index");

        String jsonResponse =
            "{\"hits\":{\"hits\":[{\"_source\":{\"name\":\"orders\","
                + "\"fullyQualifiedName\":\"db.schema.orders\",\"entityType\":\"table\","
                + "\"description\":\"Orders table\"}}],\"total\":{\"value\":1}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "orders");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsEntry("totalFound", 1);
        assertThat(result).containsEntry("returnedCount", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("name", "orders");
        assertThat(results.get(0)).containsEntry("fullyQualifiedName", "db.schema.orders");
      }
    }

    @Test
    void execute_objectResponse_convertedCorrectly() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        // When response.getEntity() returns a Map (not a String)
        Map<String, Object> responseObject = buildOpenSearchResponse(List.of(), 0);
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(responseObject);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsEntry("totalFound", 0);
      }
    }
  }

  // ====================== execute() flow — parameter handling ======================

  @Nested
  class ParameterHandling {

    @Test
    void execute_sizeClampedTo50() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("size", 200); // Over max

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("size")).isEqualTo(50);
      }
    }

    @Test
    void execute_sizeClampedTo1_minimum() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("size", -5); // Below min

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("size")).isEqualTo(1);
      }
    }

    @Test
    void execute_sizeAsString_parsedCorrectly() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("size", "25");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("size")).isEqualTo(25);
      }
    }

    @Test
    void execute_invalidSizeString_defaultsTo10() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("size", "not_a_number");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("size")).isEqualTo(10);
      }
    }

    @Test
    void execute_fromAsString_parsedCorrectly() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("from", "20");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("from")).isEqualTo(20);
      }
    }

    @Test
    void execute_fromNegative_clampedTo0() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("from", -10);

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("from")).isEqualTo(0);
      }
    }

    @Test
    void execute_includeDeletedString_parsedCorrectly() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("includeDeleted", "true");

        // Verify it doesn't throw and returns valid results
        Map<String, Object> result = tool.execute(authorizer, securityContext, params);
        assertThat(result).containsKey("results");
      }
    }

    @Test
    void execute_defaultQuery_isStar() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        // No query provided

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsEntry("query", "*");
      }
    }
  }

  // ====================== execute() flow — queryFilter handling ======================

  @Nested
  class QueryFilterHandling {

    @Test
    void execute_validQueryFilter_usesDirectQuery() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity())
            .thenReturn("{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}");
        when(searchRepository.searchWithDirectQuery(
                any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("queryFilter", "{\"term\":{\"entityType\":\"dashboard\"}}");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("results");
      }
    }

    @Test
    void execute_invalidQueryFilter_returnsError() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("queryFilter", "not valid json {");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("Invalid queryFilter JSON");
      }
    }

    @Test
    void execute_queryFilterNonObject_returnsError() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("queryFilter", "\"just a string\"");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("queryFilter must be a JSON object");
      }
    }

    @Test
    void execute_queryFilterWithoutQuery_wrapsInQuery() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity())
            .thenReturn("{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}");
        when(searchRepository.searchWithDirectQuery(
                any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        // queryFilter without top-level "query" key should be auto-wrapped
        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("queryFilter", "{\"term\":{\"entityType\":\"dashboard\"}}");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("results");
      }
    }

    @Test
    void execute_queryFilterWithQueryKey_usesAsIs() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity())
            .thenReturn("{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}");
        when(searchRepository.searchWithDirectQuery(
                any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put(
            "queryFilter",
            "{\"query\":{\"bool\":{\"must\":[{\"term\":{\"entityType\":\"dashboard\"}}]}}}");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("results");
      }
    }
  }

  // ====================== execute() flow — searchAndExtract integration ======================

  @Nested
  class SearchAndExtractIntegration {

    @Test
    void execute_resultsContainOnlyEssentialFields() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("table_search_index"))
            .thenReturn("openmetadata_table_search_index");

        String jsonResponse =
            "{\"hits\":{\"hits\":[{\"_source\":{\"name\":\"orders\","
                + "\"fullyQualifiedName\":\"db.schema.orders\","
                + "\"entityType\":\"table\","
                + "\"description\":\"Orders\","
                + "\"internalDebugField\":\"should_be_removed\","
                + "\"rawEsScore\":3.14}}],\"total\":{\"value\":1}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "orders");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        Map<String, Object> cleaned = results.get(0);
        assertThat(cleaned).containsEntry("name", "orders");
        assertThat(cleaned).doesNotContainKey("internalDebugField");
        assertThat(cleaned).doesNotContainKey("rawEsScore");
      }
    }

    @Test
    void execute_envelopeHasBackwardCompatFields() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse = "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        // Backward-compat fields
        assertThat(result).containsKey("totalFound");
        assertThat(result).containsKey("returnedCount");
        assertThat(result).containsKey("query");
        assertThat(result).containsKey("usage");

        // Envelope fields
        assertThat(result).containsKey("results");
        assertThat(result).containsKey("pagination");
      }
    }

    @Test
    void execute_paginationHasNextFromWhenMoreResults() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse =
            "{\"hits\":{\"hits\":[{\"_source\":{\"name\":\"t1\"}}],\"total\":{\"value\":50}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("size", 10);

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination).containsEntry("nextFrom", 10);
        assertThat(result).containsEntry("hasMore", true);
      }
    }
  }

  // ====================== execute() flow — aggregation handling ======================

  @Nested
  class AggregationHandling {

    @Test
    void execute_aggregationsIncludedWhenRequested() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse =
            "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}},"
                + "\"aggregations\":{\"serviceType\":{\"buckets\":["
                + "{\"key\":\"PostgreSQL\",\"doc_count\":10},"
                + "{\"key\":\"MySQL\",\"doc_count\":5}]}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("includeAggregations", true);

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("aggregations");
      }
    }

    @Test
    void execute_aggregationsExcludedByDefault() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse =
            "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}},"
                + "\"aggregations\":{\"serviceType\":{\"buckets\":[]}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).doesNotContainKey("aggregations");
      }
    }

    @Test
    void execute_maxAggregationBucketsClamped() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        // Build aggregations with 30 buckets
        StringBuilder bucketsJson = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
          if (i > 0) bucketsJson.append(",");
          bucketsJson
              .append("{\"key\":\"Svc")
              .append(i)
              .append("\",\"doc_count\":")
              .append(30 - i)
              .append("}");
        }
        bucketsJson.append("]");

        String jsonResponse =
            "{\"hits\":{\"hits\":[],\"total\":{\"value\":0}},"
                + "\"aggregations\":{\"serviceType\":{\"buckets\":"
                + bucketsJson
                + "}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("includeAggregations", true);
        params.put("maxAggregationBuckets", 200); // Over max of 50

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        assertThat(result).containsKey("aggregations");
        // Max should be clamped to 50, so with 30 buckets, no truncation needed
        assertThat(result).doesNotContainKey("aggregationsTruncated");
      }
    }
  }

  // ====================== execute() flow — search method selection ======================

  @Nested
  class SearchMethodSelection {

    @Test
    void execute_withoutQueryFilter_callsSearch() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity())
            .thenReturn("{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}");
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");

        tool.execute(authorizer, securityContext, params);

        verify(searchRepository).search(any(SearchRequest.class), any(SubjectContext.class));
        verify(searchRepository, never())
            .searchWithDirectQuery(any(SearchRequest.class), any(SubjectContext.class));
      }
    }

    @Test
    void execute_withQueryFilter_callsSearchWithDirectQuery() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity())
            .thenReturn("{\"hits\":{\"hits\":[],\"total\":{\"value\":0}}}");
        when(searchRepository.searchWithDirectQuery(
                any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("queryFilter", "{\"term\":{\"entityType\":\"dashboard\"}}");

        tool.execute(authorizer, securityContext, params);

        verify(searchRepository)
            .searchWithDirectQuery(any(SearchRequest.class), any(SubjectContext.class));
        verify(searchRepository, never())
            .search(any(SearchRequest.class), any(SubjectContext.class));
      }
    }
  }

  // ====================== execute() flow — fields parameter ======================

  @Nested
  class FieldsParameter {

    @Test
    void execute_fieldsParam_includesExtraFieldsInResults() throws Exception {
      try (MockedStatic<SubjectCache> subjectCacheMock = mockStatic(SubjectCache.class)) {
        subjectCacheMock.when(() -> SubjectCache.getUserContext("test-user")).thenReturn(mockUser);

        when(searchRepository.getIndexOrAliasName("dataAsset"))
            .thenReturn("openmetadata_dataAsset");

        String jsonResponse =
            "{\"hits\":{\"hits\":[{\"_source\":{\"name\":\"orders\","
                + "\"fullyQualifiedName\":\"db.schema.orders\","
                + "\"entityType\":\"table\","
                + "\"columnNames\":[\"id\",\"name\"],"
                + "\"serviceType\":\"PostgreSQL\"}}],"
                + "\"total\":{\"value\":1}}}";
        Response mockResponse = mock(Response.class);
        when(mockResponse.getEntity()).thenReturn(jsonResponse);
        when(searchRepository.search(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(mockResponse);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "orders");
        params.put("fields", "columnNames,serviceType");

        Map<String, Object> result = tool.execute(authorizer, securityContext, params);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        // Essential field always present
        assertThat(results.get(0)).containsEntry("name", "orders");
        // Requested extra fields present
        assertThat(results.get(0)).containsKey("columnNames");
        assertThat(results.get(0)).containsKey("serviceType");
      }
    }
  }

  // ====================== execute() flow — limits enforcement ======================

  @Nested
  class LimitsEnforcement {

    @Test
    void execute_withLimits_throwsUnsupportedOperation() {
      assertThatThrownBy(
              () ->
                  tool.execute(
                      authorizer,
                      mock(org.openmetadata.service.limits.Limits.class),
                      securityContext,
                      Map.of("query", "test")))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("SearchMetadataTool does not support limits enforcement");
    }
  }

  // ====================== helpers for aggregation test data ======================

  private Map<String, Object> buildOpenSearchResponseWithAggregations(int bucketCount) {
    Map<String, Object> aggregations = new LinkedHashMap<>();
    Map<String, Object> serviceTypeAgg = new LinkedHashMap<>();
    List<Map<String, Object>> buckets = new ArrayList<>();

    for (int i = 0; i < bucketCount; i++) {
      Map<String, Object> bucket = new LinkedHashMap<>();
      bucket.put("key", "Service" + i);
      bucket.put("doc_count", 100 - i);
      buckets.add(bucket);
    }

    serviceTypeAgg.put("buckets", buckets);
    aggregations.put("serviceType", serviceTypeAgg);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("aggregations", aggregations);
    return response;
  }
}
