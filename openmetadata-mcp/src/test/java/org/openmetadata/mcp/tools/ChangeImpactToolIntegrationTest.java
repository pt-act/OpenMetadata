package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Integration tests for {@link ChangeImpactTool}.
 *
 * <p>Strategy: Tests are split into two categories:
 *
 * <ol>
 *   <li>Direct static method tests for @VisibleForTesting methods (no repository mocking needed)
 *   <li>Full execute() flow tests using injected functional interfaces via {@link McpEntityBridge}
 *       instead of {@code mockStatic(Entity.class)}, eliminating the need to mock Entity static
 *       initializers.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeImpactToolIntegrationTest {

  private ChangeImpactTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;
  private McpEntityBridge.LineageRepositoryProvider lineageProvider;

  @BeforeEach
  void setUp() {
    tool = new ChangeImpactTool();
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

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    noopAuthorizer = (entityType, op) -> {};
    lineageProvider = () -> null;
  }

  // ====================== Helper methods ======================

  private EntityReference buildEntityRef(String fqn, UUID id, String type) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    when(ref.getType()).thenReturn(type);
    return ref;
  }

  private SearchRepository createSearchRepoWithEmptyResults() {
    SearchRepository searchRepo = mock(SearchRepository.class);
    when(searchRepo.getIndexOrAliasName(anyString())).thenReturn("search_index");
    return searchRepo;
  }

  /** Calls the test-friendly execute overload with injected providers. */
  private Map<String, Object> executeWithProviders(
      ChangeImpactTool tool,
      Map<String, Object> params,
      EntityReference entityRef,
      SearchRepository searchRepo) {

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepo;

    try {
      return tool.execute(
          params, securityContext, resolver, noopAuthorizer, lineageProvider, searchRepoProvider);
    } catch (RuntimeException e) {
      // Rethrow RuntimeException (including IllegalArgumentException) directly
      // so that assertThatThrownBy can match the correct exception type.
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ====================== extractDownstreamNodes (static, no mocking) ======================

  @Nested
  class ExtractDownstreamNodes {

    @Test
    void emptyNodes_returnsEmptyList() {
      Map<String, Object> lineageData = Map.of("nodes", Map.of());
      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.orders");
      assertThat(result).isEmpty();
    }

    @Test
    void threeDownstream_excludesSource() {
      Map<String, Object> n1 = new LinkedHashMap<>();
      n1.put("fullyQualifiedName", "db.schema.report_table");
      n1.put("name", "report_table");
      Map<String, Object> n2 = new LinkedHashMap<>();
      n2.put("fullyQualifiedName", "db.schema.summary_view");
      n2.put("name", "summary_view");
      Map<String, Object> n3 = new LinkedHashMap<>();
      n3.put("fullyQualifiedName", "db.schema.orders"); // same as source
      n3.put("name", "orders");

      Map<String, Object> lineageData = new LinkedHashMap<>();
      lineageData.put("nodes", Map.of("a", n1, "b", n2, "c", n3));

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.orders");

      assertThat(result).hasSize(2);
      assertThat(result.stream().map(n -> n.get("fullyQualifiedName")))
          .containsExactlyInAnyOrder("db.schema.report_table", "db.schema.summary_view");
    }

    @Test
    void allNodesHaveDownstreamHitReason() {
      Map<String, Object> n1 = new LinkedHashMap<>();
      n1.put("fullyQualifiedName", "db.schema.t1");
      n1.put("name", "t1");
      Map<String, Object> n2 = new LinkedHashMap<>();
      n2.put("fullyQualifiedName", "db.schema.t2");
      n2.put("name", "t2");

      Map<String, Object> lineageData = new LinkedHashMap<>();
      lineageData.put("nodes", Map.of("a", n1, "b", n2));

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.source");

      assertThat(result).hasSize(2);
      assertThat(result.stream().allMatch(n -> "downstream".equals(n.get("hitReason")))).isTrue();
    }

    @Test
    void tier1Node_preservedInOutput() {
      Map<String, Object> tierTag = new LinkedHashMap<>();
      tierTag.put("tagFQN", "Tier.Tier1");
      Map<String, Object> n1 = new LinkedHashMap<>();
      n1.put("fullyQualifiedName", "db.schema.critical_table");
      n1.put("name", "critical_table");
      n1.put("tier", tierTag);

      Map<String, Object> lineageData = new LinkedHashMap<>();
      lineageData.put("nodes", Map.of("a", n1));

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.source");

      assertThat(result).hasSize(1);
      Map<String, Object> node = result.get(0);
      assertThat(node.get("fullyQualifiedName")).isEqualTo("db.schema.critical_table");
    }
  }

  // ====================== computeSeverity (static, no mocking) ======================

  @Nested
  class ComputeSeverity {

    @Test
    void noDownstream_returnsLow() {
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(), List.of(), List.of(), List.of(), List.of()))
          .isEqualTo("low");
    }

    @Test
    void oneDownstream_returnsMedium() {
      Map<String, Object> entity = Map.of("fullyQualifiedName", "db.schema.t1");
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(entity), List.of(), List.of(), List.of(), List.of()))
          .isEqualTo("medium");
    }

    @Test
    void fiveDownstream_returnsHigh() {
      Map<String, Object> e1 = Map.of("fqn", "t1");
      Map<String, Object> e2 = Map.of("fqn", "t2");
      Map<String, Object> e3 = Map.of("fqn", "t3");
      Map<String, Object> e4 = Map.of("fqn", "t4");
      Map<String, Object> e5 = Map.of("fqn", "t5");
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(e1, e2, e3, e4, e5), List.of(), List.of(), List.of(), List.of()))
          .isEqualTo("high");
    }

    @Test
    void tier1Downstream_returnsCritical() {
      Map<String, Object> tier = Map.of("tagFQN", "Tier.Tier1");
      Map<String, Object> entity = new LinkedHashMap<>();
      entity.put("fullyQualifiedName", "db.schema.critical");
      entity.put("tier", tier);
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(entity), List.of(), List.of(), List.of(), List.of()))
          .isEqualTo("critical");
    }

    @Test
    void tier1StringFormat_returnsCritical() {
      Map<String, Object> entity = new LinkedHashMap<>();
      entity.put("fullyQualifiedName", "db.schema.critical");
      entity.put("tier", "Tier.Tier1");
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(entity), List.of(), List.of(), List.of(), List.of()))
          .isEqualTo("critical");
    }

    @Test
    void searchHitsCountTowardTotal() {
      // 3 entities + 2 dashboards = 5 total → high
      Map<String, Object> e1 = Map.of("fqn", "t1");
      Map<String, Object> e2 = Map.of("fqn", "t2");
      Map<String, Object> e3 = Map.of("fqn", "t3");
      Map<String, Object> d1 = Map.of("fqn", "d1");
      Map<String, Object> d2 = Map.of("fqn", "d2");
      assertThat(
              ChangeImpactTool.computeSeverity(
                  List.of(e1, e2, e3), List.of(d1, d2), List.of(), List.of(), List.of()))
          .isEqualTo("high");
    }
  }

  // ====================== parseProposedChange (static, no mocking) ======================

  @Nested
  class ParseProposedChange {

    @Test
    void structuredMap_withAllFields() {
      Map<String, Object> params = new HashMap<>();
      params.put(
          "proposedChange",
          Map.of("kind", "dropColumn", "column", "email", "description", "removing email"));
      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("dropColumn");
      assertThat(pc.column).isEqualTo("email");
      assertThat(pc.description).isEqualTo("removing email");
    }

    @Test
    void fallbackIndividualParams() {
      Map<String, Object> params = new HashMap<>();
      params.put("kind", "changeColumnType");
      params.put("column", "id");
      params.put("fromType", "int");
      params.put("toType", "bigint");
      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("changeColumnType");
      assertThat(pc.column).isEqualTo("id");
      assertThat(pc.fromType).isEqualTo("int");
      assertThat(pc.toType).isEqualTo("bigint");
    }

    @Test
    void missingKind_throwsIllegalArgumentException() {
      Map<String, Object> params = Map.of("proposedChange", Map.of("column", "id"));
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("kind");
    }

    @Test
    void missingKindFallback_throwsIllegalArgumentException() {
      Map<String, Object> params = Map.of("column", "id");
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proposedChange")
          .hasMessageContaining("kind");
    }

    @Test
    void dropColumnMissingColumn_throws() {
      Map<String, Object> params = Map.of("proposedChange", Map.of("kind", "dropColumn"));
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dropColumn")
          .hasMessageContaining("column");
    }

    @Test
    void changeColumnTypeMissingFromType_throws() {
      Map<String, Object> params =
          Map.of(
              "proposedChange",
              Map.of("kind", "changeColumnType", "column", "id", "toType", "bigint"));
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fromType");
    }

    @Test
    void customMissingDescription_throws() {
      Map<String, Object> params = Map.of("proposedChange", Map.of("kind", "custom"));
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("custom")
          .hasMessageContaining("description");
    }

    @Test
    void unsupportedKind_throws() {
      Map<String, Object> params = Map.of("proposedChange", Map.of("kind", "renameTable"));
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported")
          .hasMessageContaining("renameTable");
    }
  }

  // ====================== generateNarrative + capNarrative (static, no mocking)
  // ======================

  @Nested
  class NarrativeGeneration {

    @Test
    void lowSeverity_noDownstream() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 0, "dashboards", 0, "pipelines", 0, "tests", 0, "policies", 0);
      String narrative = ChangeImpactTool.generateNarrative("db.schema.orders", pc, "low", counts);
      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("deprecating entity");
      assertThat(narrative).contains("LOW");
      assertThat(narrative).contains("No downstream impact detected");
    }

    @Test
    void criticalSeverity_tier1Warning() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "id", null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 1, "dashboards", 2, "pipelines", 0, "tests", 0, "policies", 0);
      String narrative =
          ChangeImpactTool.generateNarrative("db.schema.orders", pc, "critical", counts);
      assertThat(narrative).contains("CRITICAL");
      assertThat(narrative).contains("Tier-1");
      assertThat(narrative).contains("dropping column `id`");
      assertThat(narrative).contains("**1** downstream entity");
      assertThat(narrative).contains("**2** dashboards");
    }

    @Test
    void capNarrative_truncatesWhenTooLong() {
      String longNarrative = "A".repeat(1500);
      String capped = ChangeImpactTool.capNarrative(longNarrative);
      assertThat(capped.length()).isLessThanOrEqualTo(1200);
      assertThat(capped).endsWith("...");
    }

    @Test
    void capNarrative_noTruncationWhenShort() {
      String shortNarrative = "Short narrative";
      assertThat(ChangeImpactTool.capNarrative(shortNarrative)).isEqualTo(shortNarrative);
    }
  }

  // ====================== buildReferenceQueryFilter (static, no mocking) ======================

  @Nested
  class BuildReferenceQueryFilter {

    @Test
    void columnLevelChange_includesColumnMatch() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "email", null, null, null);
      String filter =
          ChangeImpactTool.buildReferenceQueryFilter("db.schema.orders", pc, "dashboard");
      assertThat(filter).contains("\"term\":{\"entityType\":\"dashboard\"}");
      assertThat(filter).contains("\"match\":{\"columns.name\":\"email\"}");
    }

    @Test
    void entityLevelChange_includesMultiMatch() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      String filter =
          ChangeImpactTool.buildReferenceQueryFilter("db.schema.orders", pc, "pipeline");
      assertThat(filter).contains("\"term\":{\"entityType\":\"pipeline\"}");
      assertThat(filter).contains("\"multi_match\"");
      assertThat(filter).contains("db.schema.orders");
    }

    @Test
    void specialCharactersInFqn_escaped() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      String filter =
          ChangeImpactTool.buildReferenceQueryFilter("db.schema.or\"ders", pc, "dashboard");
      assertThat(filter).doesNotContain("or\"ders");
      assertThat(filter).contains("or\\\"ders");
    }
  }

  // ====================== execute() flow — graceful lineage failure ======================

  @Nested
  class LineageFailureGracefulDegradation {

    @Test
    void execute_lineageFails_searchDisabled_returnsLowSeverity() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      assertThat(result).containsEntry("severity", "low");
      assertThat(result).containsEntry("fqn", "db.schema.orders");
      assertThat(result).containsEntry("entityType", "table");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results).hasSize(1);

      Map<String, Object> impactResult = results.get(0);
      @SuppressWarnings("unchecked")
      Map<String, Object> counts = (Map<String, Object>) impactResult.get("counts");
      assertThat(counts.get("entities")).isEqualTo(0);
      assertThat(counts.get("dashboards")).isEqualTo(0);
      assertThat(counts.get("pipelines")).isEqualTo(0);
      assertThat(counts.get("tests")).isEqualTo(0);
      assertThat(counts.get("policies")).isEqualTo(0);
    }
  }

  // ====================== execute() flow — search fan-out ======================

  @Nested
  class SearchFanOut {

    @Test
    void execute_withDashboardSearch_dashboardHitsAppearInAffected() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = mock(SearchRepository.class);
      when(searchRepo.getIndexOrAliasName(anyString())).thenReturn("search_index");

      SubjectContext subjectContext = mock(SubjectContext.class);
      Response searchResponse = mock(Response.class);

      // Dashboard search returns one hit
      Map<String, Object> dashboardHit = new LinkedHashMap<>();
      dashboardHit.put("fullyQualifiedName", "dw.orders_dashboard");
      dashboardHit.put("name", "orders_dashboard");
      dashboardHit.put("entityType", "dashboard");

      // Pipeline search returns one hit
      Map<String, Object> pipelineHit = new LinkedHashMap<>();
      pipelineHit.put("fullyQualifiedName", "pipeline.etl_orders");
      pipelineHit.put("name", "etl_orders");
      pipelineHit.put("entityType", "pipeline");

      Map<String, Object> enhancedSearchResultDash = new LinkedHashMap<>();
      enhancedSearchResultDash.put("results", List.of(dashboardHit));

      Map<String, Object> enhancedSearchResultPipe = new LinkedHashMap<>();
      enhancedSearchResultPipe.put("results", List.of(pipelineHit));

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "dropColumn", "column", "customer_id"));
      params.put("includeDashboards", true);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class)) {

        // SubjectContext for searchWithDirectQuery
        authorizerMock
            .when(() -> DefaultAuthorizer.getSubjectContext(securityContext))
            .thenReturn(subjectContext);

        // Search response
        when(searchRepo.searchWithDirectQuery(any(SearchRequest.class), any(SubjectContext.class)))
            .thenReturn(searchResponse);
        when(searchResponse.getEntity()).thenReturn(new HashMap<>());

        // JsonUtils for search response → Map conversion
        jsonMock
            .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
            .thenReturn(new HashMap<>());

        // Return dashboard results on first call, pipeline results on second
        searchToolMock
            .when(
                () ->
                    SearchMetadataTool.buildEnhancedSearchResponse(
                        any(Map.class),
                        anyString(),
                        anyInt(),
                        anyInt(),
                        any(List.class),
                        anyBoolean(),
                        anyInt()))
            .thenReturn(enhancedSearchResultDash)
            .thenReturn(enhancedSearchResultPipe);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

        // 2 search hits (dashboard + pipeline), no entities → medium (total=2)
        assertThat(result).containsEntry("severity", "medium");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        Map<String, Object> impactResult = results.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> affected = (Map<String, Object>) impactResult.get("affected");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dashboards =
            (List<Map<String, Object>>) affected.get("dashboards");
        assertThat(dashboards).hasSize(1);
        assertThat(dashboards.get(0).get("fullyQualifiedName")).isEqualTo("dw.orders_dashboard");
        assertThat(dashboards.get(0).get("hitReason").toString()).startsWith("references:");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pipelines = (List<Map<String, Object>>) affected.get("pipelines");
        assertThat(pipelines).hasSize(1);
        assertThat(pipelines.get(0).get("fullyQualifiedName")).isEqualTo("pipeline.etl_orders");
        assertThat(pipelines.get(0).get("hitReason").toString()).startsWith("references:");
      }
    }

    @Test
    void execute_withAllSearchFlags_testAndPolicyHitsAppearInAffected() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = mock(SearchRepository.class);
      when(searchRepo.getIndexOrAliasName(anyString())).thenReturn("search_index");

      SubjectContext subjectContext = mock(SubjectContext.class);
      Response searchResponse = mock(Response.class);
      when(searchRepo.searchWithDirectQuery(any(SearchRequest.class), any(SubjectContext.class)))
          .thenReturn(searchResponse);
      when(searchResponse.getEntity()).thenReturn(new HashMap<>());

      // Dashboard hit
      Map<String, Object> dashboardHit = new LinkedHashMap<>();
      dashboardHit.put("fullyQualifiedName", "dw.orders_dashboard");
      dashboardHit.put("name", "orders_dashboard");
      dashboardHit.put("entityType", "dashboard");

      // Pipeline hit
      Map<String, Object> pipelineHit = new LinkedHashMap<>();
      pipelineHit.put("fullyQualifiedName", "pipeline.etl_orders");
      pipelineHit.put("name", "etl_orders");
      pipelineHit.put("entityType", "pipeline");

      // Test case hit
      Map<String, Object> testHit = new LinkedHashMap<>();
      testHit.put("fullyQualifiedName", "db.schema.orders.columnValuesToBeNotNull");
      testHit.put("name", "columnValuesToBeNotNull");
      testHit.put("entityType", "testCase");

      // Policy hit
      Map<String, Object> policyHit = new LinkedHashMap<>();
      policyHit.put("fullyQualifiedName", "pii_protection_policy");
      policyHit.put("name", "pii_protection_policy");
      policyHit.put("entityType", "policy");

      Map<String, Object> dashResult = new LinkedHashMap<>();
      dashResult.put("results", List.of(dashboardHit));
      Map<String, Object> pipeResult = new LinkedHashMap<>();
      pipeResult.put("results", List.of(pipelineHit));
      Map<String, Object> testResult = new LinkedHashMap<>();
      testResult.put("results", List.of(testHit));
      Map<String, Object> policyResult = new LinkedHashMap<>();
      policyResult.put("results", List.of(policyHit));

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "dropColumn", "column", "email"));
      params.put("includeDashboards", true);
      params.put("includeTests", true);
      params.put("includePolicies", true);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class)) {

        authorizerMock
            .when(() -> DefaultAuthorizer.getSubjectContext(securityContext))
            .thenReturn(subjectContext);

        jsonMock
            .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
            .thenReturn(new HashMap<>());

        // Return type-specific results in sequence: dashboard, pipeline, test, policy
        searchToolMock
            .when(
                () ->
                    SearchMetadataTool.buildEnhancedSearchResponse(
                        any(Map.class),
                        anyString(),
                        anyInt(),
                        anyInt(),
                        any(List.class),
                        anyBoolean(),
                        anyInt()))
            .thenReturn(dashResult)
            .thenReturn(pipeResult)
            .thenReturn(testResult)
            .thenReturn(policyResult);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

        // entities=0, dashboards=1, pipelines=1, tests=1, policies=1 → total=4 → medium
        assertThat(result).containsEntry("severity", "medium");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        Map<String, Object> impactResult = results.get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> affected = (Map<String, Object>) impactResult.get("affected");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tests = (List<Map<String, Object>>) affected.get("tests");
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).get("fullyQualifiedName"))
            .isEqualTo("db.schema.orders.columnValuesToBeNotNull");
        assertThat(tests.get(0)).containsKey("hitReason");
        assertThat(tests.get(0).get("hitReason").toString()).startsWith("testTouches");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policies = (List<Map<String, Object>>) affected.get("policies");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0).get("fullyQualifiedName")).isEqualTo("pii_protection_policy");
        assertThat(policies.get(0)).containsKey("hitReason");
        assertThat(policies.get(0).get("hitReason").toString()).startsWith("policyCovers");
      }
    }
  }

  // ====================== execute() flow — envelope structure ======================

  @Nested
  class EnvelopeStructure {

    @Test
    void execute_hasRequiredTopLevelKeys() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      // Top-level envelope keys
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
      assertThat(result).containsKey("severity");
      assertThat(result).containsKey("fqn");
      assertThat(result).containsKey("entityType");
      assertThat(result).containsKey("downstreamDepth");

      // Nested result keys
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> impactResult = results.get(0);
      assertThat(impactResult).containsKey("severity");
      assertThat(impactResult).containsKey("affected");
      assertThat(impactResult).containsKey("counts");
      assertThat(impactResult).containsKey("proposedChange");

      // Affected keys
      @SuppressWarnings("unchecked")
      Map<String, Object> affected = (Map<String, Object>) impactResult.get("affected");
      assertThat(affected).containsKey("entities");
      assertThat(affected).containsKey("dashboards");
      assertThat(affected).containsKey("pipelines");
      assertThat(affected).containsKey("tests");
      assertThat(affected).containsKey("policies");

      // Counts keys
      @SuppressWarnings("unchecked")
      Map<String, Object> counts = (Map<String, Object>) impactResult.get("counts");
      assertThat(counts).containsKey("entities");
      assertThat(counts).containsKey("dashboards");
      assertThat(counts).containsKey("pipelines");
      assertThat(counts).containsKey("tests");
      assertThat(counts).containsKey("policies");
    }
  }

  // ====================== execute() flow — narrative integration ======================

  @Nested
  class NarrativeIntegration {

    @Test
    void execute_narrativeContainsFqnAndChangeDescription() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "dropColumn", "column", "customer_id"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("dropping column `customer_id`");
      assertThat(narrative).contains("LOW");
    }

    @Test
    void execute_changeColumnType_narrativeContainsFromTo() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put(
          "proposedChange",
          Map.of(
              "kind", "changeColumnType", "column", "id", "fromType", "int", "toType", "bigint"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> proposedChange =
          (Map<String, Object>) results.get(0).get("proposedChange");
      assertThat(proposedChange).containsEntry("kind", "changeColumnType");
      assertThat(proposedChange).containsEntry("column", "id");
      assertThat(proposedChange).containsEntry("fromType", "int");
      assertThat(proposedChange).containsEntry("toType", "bigint");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("changing column `id` from int to bigint");
    }
  }

  // ====================== execute() flow — downstream depth parameter ======================

  @Nested
  class DownstreamDepthParameter {

    @Test
    void execute_customDepth_respected() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));
      params.put("downstreamDepth", 5);
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);
      assertThat(result).containsEntry("downstreamDepth", 5);
    }

    @Test
    void execute_depthCappedAtMax() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));
      params.put("downstreamDepth", 999);
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);
      assertThat(result).containsEntry("downstreamDepth", 10);
    }
  }

  // ====================== execute() flow — non-table entityType ======================

  @Nested
  class NonTableEntityType {

    @Test
    void execute_dashboardEntity_usesCorrectType() throws Exception {
      EntityReference entityRef = buildEntityRef("dw.my_dashboard", UUID.randomUUID(), "dashboard");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "dashboard");
      params.put("fqn", "dw.my_dashboard");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);
      assertThat(result).containsEntry("entityType", "dashboard");
      assertThat(result).containsEntry("fqn", "dw.my_dashboard");
    }
  }

  // ====================== execute() flow — fallback proposedChange params ======================

  @Nested
  class FallbackProposedChangeParams {

    @Test
    void execute_individualParams_worksAsFallback() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("kind", "dropColumn");
      params.put("column", "email");
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> proposedChange =
          (Map<String, Object>) results.get(0).get("proposedChange");
      assertThat(proposedChange).containsEntry("kind", "dropColumn");
      assertThat(proposedChange).containsEntry("column", "email");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("dropping column `email`");
    }
  }

  // ====================== execute() flow — custom proposedChange kind ======================

  @Nested
  class CustomProposedChangeKind {

    @Test
    void execute_customKind_includesDescriptionInNarrative() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");

      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "custom", "description", "renaming table"));
      params.put("includeDashboards", false);
      params.put("includeTests", false);
      params.put("includePolicies", false);

      Map<String, Object> result = executeWithProviders(tool, params, entityRef, searchRepo);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> proposedChange =
          (Map<String, Object>) results.get(0).get("proposedChange");
      assertThat(proposedChange).containsEntry("kind", "custom");
      assertThat(proposedChange).containsEntry("description", "renaming table");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("renaming table");
    }
  }

  // ====================== enforceByteCap (static, needs JsonUtils mock) ======================

  @Nested
  class EnforceByteCap {

    @Test
    void underCap_noMutation() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("severity", "low");
      Map<String, Object> impactResult = new LinkedHashMap<>();
      Map<String, Object> affected = new LinkedHashMap<>();
      affected.put("entities", List.of());
      affected.put("dashboards", List.of());
      affected.put("pipelines", List.of());
      affected.put("tests", List.of());
      affected.put("policies", List.of());
      impactResult.put("affected", affected);
      result.put("results", List.of(impactResult));

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        // Simulate a small JSON payload
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{\"severity\":\"low\"}");

        Map<String, Object> capped = ChangeImpactTool.enforceByteCap(result);
        assertThat(capped).isSameAs(result);
        assertThat(capped).doesNotContainKey("warnings");
      }
    }

    @Test
    void overCap_truncatesEntitiesListAndAddsWarning() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("severity", "high");

      // Build an affected map with 10 entities
      List<Map<String, Object>> entities = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fullyQualifiedName", "db.schema.table" + i);
        entities.add(e);
      }

      Map<String, Object> affected = new LinkedHashMap<>();
      affected.put("entities", entities);
      affected.put("pipelines", List.of());
      affected.put("dashboards", List.of());
      affected.put("tests", List.of());
      affected.put("policies", List.of());

      Map<String, Object> impactResult = new LinkedHashMap<>();
      impactResult.put("affected", affected);
      result.put("results", List.of(impactResult));

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        // First call: over the cap
        String bigJson = "x".repeat(9000);
        // After truncation: under the cap
        String smallJson = "{\"severity\":\"high\"}";
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(bigJson).thenReturn(smallJson);

        Map<String, Object> capped = ChangeImpactTool.enforceByteCap(result);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) capped.get("warnings");
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.get(0)).contains("truncated:entities");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> truncatedEntities =
            (List<Map<String, Object>>)
                ((Map<String, Object>)
                        ((Map<String, Object>) ((List<?>) capped.get("results")).get(0))
                            .get("affected"))
                    .get("entities");
        assertThat(truncatedEntities).hasSize(3);
      }
    }

    @Test
    void smallListNotTruncated() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("severity", "high");

      // Only 2 entities — below the 3-item threshold
      List<Map<String, Object>> entities = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fullyQualifiedName", "db.schema.table" + i);
        entities.add(e);
      }

      Map<String, Object> affected = new LinkedHashMap<>();
      affected.put("entities", entities);
      affected.put("pipelines", List.of());
      affected.put("dashboards", List.of());
      affected.put("tests", List.of());
      affected.put("policies", List.of());

      Map<String, Object> impactResult = new LinkedHashMap<>();
      impactResult.put("affected", affected);
      result.put("results", List.of(impactResult));

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        String bigJson = "x".repeat(9000);
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(bigJson);

        Map<String, Object> capped = ChangeImpactTool.enforceByteCap(result);

        // No truncation happened (list size <= 3)
        assertThat(capped).doesNotContainKey("warnings");
      }
    }
  }

  // ====================== capNarrative boundary ======================

  @Nested
  class CapNarrativeBoundary {

    @Test
    void exactlyAtMax_noTruncation() {
      String narrative = "A".repeat(1200);
      assertThat(ChangeImpactTool.capNarrative(narrative)).isEqualTo(narrative);
    }

    @Test
    void oneOverMax_truncates() {
      String narrative = "A".repeat(1201);
      String capped = ChangeImpactTool.capNarrative(narrative);
      assertThat(capped.length()).isEqualTo(1200);
      assertThat(capped).endsWith("...");
    }
  }

  // ====================== Parameter validation ======================

  @Nested
  class ParameterValidation {

    @Test
    void execute_nullParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Parameters cannot be null or empty");
    }

    @Test
    void execute_emptyParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Parameters cannot be null or empty");
    }

    @Test
    void execute_missingProposedChangeAndKind_throws() {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");
      SearchRepository searchRepo = createSearchRepoWithEmptyResults();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      // No proposedChange and no kind

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      assertThatThrownBy(() -> executeWithProviders(tool, params, entityRef, searchRepo))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proposedChange");
    }
  }
}
