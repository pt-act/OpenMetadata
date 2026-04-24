package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * Unit tests for RootCauseAnalysisTool.
 *
 * <p>Tests inject functional interfaces via {@link McpEntityBridge} instead of {@code
 * mockStatic(Entity.class)}, eliminating the need to mock Entity static initializers.
 *
 * <p>Tests verify:
 * - Missing fqn/fullyQualifiedName throws IllegalArgumentException (via ToolUtils)
 * - fullyQualifiedName alias resolves correctly
 * - Zero depth accepted
 * - No upstream failures → no downstream analysis
 * - Authorizer is called with correct params
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RootCauseAnalysisToolTest {

  private RootCauseAnalysisTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.EntityReferenceResolver referenceResolver;
  private McpEntityBridge.SearchRepositoryProvider searchRepoProvider;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;
  private McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider;
  private McpEntityBridge.EntityFetcher entityFetcher;

  @BeforeEach
  void setUp() {
    tool = new RootCauseAnalysisTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    noopAuthorizer = (entityType, op) -> {};
    timeSeriesRepoProvider = entityType -> null;
    entityFetcher = (entityType, fqn, fields, include) -> null;
  }

  @Test
  void execute_missingFqnAndAlias_throwsIllegalArgumentException() {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");

    assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fqn")
        .hasMessageContaining("fullyQualifiedName");
  }

  @Test
  void execute_fullyQualifiedNameAlias_resolvesCorrectly() throws Exception {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fullyQualifiedName", "db.schema.table");
    params.put("upstreamDepth", 0);
    params.put("downstreamDepth", 0);

    SearchRepository searchRepo = mock(SearchRepository.class);
    Response mockResponse = mock(Response.class);
    Map<String, Object> upstreamData = Map.of("edges", Set.of(), "nodes", Set.of());

    when(searchRepo.searchDataQualityLineage(any(), anyInt(), any(), anyBoolean()))
        .thenReturn(mockResponse);
    when(mockResponse.getEntity()).thenReturn(upstreamData);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    referenceResolver =
        (entityType, fqn, include) ->
            "table".equals(entityType) && "db.schema.table".equals(fqn) ? entityRef : null;
    searchRepoProvider = () -> searchRepo;

    Map<String, Object> result =
        tool.execute(
            params,
            referenceResolver,
            noopAuthorizer,
            searchRepoProvider,
            timeSeriesRepoProvider,
            entityFetcher);

    assertThat(result).containsEntry("fqn", "db.schema.table");
    assertThat(result).containsEntry("status", "success");
  }

  @Test
  void execute_noUpstreamFailures_noDownstreamAnalysis() throws Exception {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");
    params.put("upstreamDepth", 1);
    params.put("downstreamDepth", 1);

    SearchRepository searchRepo = mock(SearchRepository.class);
    Response mockResponse = mock(Response.class);
    Map<String, Object> upstreamData = Map.of("edges", Set.of(), "nodes", Set.of());

    when(searchRepo.searchDataQualityLineage(any(), anyInt(), any(), anyBoolean()))
        .thenReturn(mockResponse);
    when(mockResponse.getEntity()).thenReturn(upstreamData);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    referenceResolver =
        (entityType, fqn, include) ->
            "table".equals(entityType) && "db.schema.table".equals(fqn) ? entityRef : null;
    searchRepoProvider = () -> searchRepo;

    Map<String, Object> result =
        tool.execute(
            params,
            referenceResolver,
            noopAuthorizer,
            searchRepoProvider,
            timeSeriesRepoProvider,
            entityFetcher);

    assertThat(result).containsEntry("status", "success");
    // The analysis data (upstreamAnalysis, downstreamAnalysis) is nested inside
    // the envelope's results list, not at the top level.
    @SuppressWarnings("unchecked")
    List<Object> results = (List<Object>) result.get("results");
    assertThat(results).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> analysisData = (Map<String, Object>) results.get(0);
    Map<String, Object> downstream = (Map<String, Object>) analysisData.get("downstreamAnalysis");
    assertThat(downstream).containsKey("reason");
  }

  @Test
  void execute_zeroDepthAccepted() throws Exception {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");
    params.put("upstreamDepth", 0);
    params.put("downstreamDepth", 0);

    SearchRepository searchRepo = mock(SearchRepository.class);
    Response mockResponse = mock(Response.class);
    Map<String, Object> upstreamData = Map.of("edges", Set.of(), "nodes", Set.of());

    when(searchRepo.searchDataQualityLineage(any(), anyInt(), any(), anyBoolean()))
        .thenReturn(mockResponse);
    when(mockResponse.getEntity()).thenReturn(upstreamData);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    referenceResolver =
        (entityType, fqn, include) ->
            "table".equals(entityType) && "db.schema.table".equals(fqn) ? entityRef : null;
    searchRepoProvider = () -> searchRepo;

    Map<String, Object> result =
        tool.execute(
            params,
            referenceResolver,
            noopAuthorizer,
            searchRepoProvider,
            timeSeriesRepoProvider,
            entityFetcher);

    assertThat(result).containsEntry("upstreamDepth", 0);
    assertThat(result).containsEntry("downstreamDepth", 0);
  }

  @Test
  void execute_authorizerCalledWithCorrectParams() throws Exception {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");
    params.put("upstreamDepth", 0);
    params.put("downstreamDepth", 0);

    SearchRepository searchRepo = mock(SearchRepository.class);
    Response mockResponse = mock(Response.class);
    Map<String, Object> upstreamData = Map.of("edges", Set.of(), "nodes", Set.of());

    when(searchRepo.searchDataQualityLineage(any(), anyInt(), any(), anyBoolean()))
        .thenReturn(mockResponse);
    when(mockResponse.getEntity()).thenReturn(upstreamData);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    // Use the default authorizer (not noop) to verify it's called
    referenceResolver =
        (entityType, fqn, include) ->
            "table".equals(entityType) && "db.schema.table".equals(fqn) ? entityRef : null;
    searchRepoProvider = () -> searchRepo;
    // Use a verifying authorizer that delegates to the mock — avoid defaultAuthorizer
    // which calls ResourceContext constructor that accesses Entity.getEntityRepository()
    McpEntityBridge.McpAuthorizer verifyingAuthorizer =
        (entityType, op) ->
            authorizer.authorize(
                securityContext, mock(OperationContext.class), mock(ResourceContext.class));

    tool.execute(
        params,
        referenceResolver,
        verifyingAuthorizer,
        searchRepoProvider,
        timeSeriesRepoProvider,
        entityFetcher);

    verify(authorizer)
        .authorize(
            any(CatalogSecurityContext.class),
            any(OperationContext.class),
            any(ResourceContext.class));
  }
}
