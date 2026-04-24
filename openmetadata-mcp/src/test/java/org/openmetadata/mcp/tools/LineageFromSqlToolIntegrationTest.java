package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.api.lineage.AddLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Integration tests for {@link LineageFromSqlTool}.
 *
 * <p>All tests use {@link McpEntityBridge.EntityReferenceResolver}, {@link
 * McpEntityBridge.SearchRepositoryProvider}, and {@link McpEntityBridge.LineageRepositoryProvider}
 * functional interfaces instead of {@code mockStatic(Entity.class)}, eliminating the need to mock
 * Entity static initializers. The {@code Entity.getEntityReferenceByName()}, {@code
 * Entity.getSearchRepository()}, and {@code Entity.getLineageRepository()} calls are never invoked
 * because injected lambdas bypass them entirely.
 *
 * <p>Tests verify:
 *
 * <ul>
 *   <li>R8.1 Parameter validation (missing sql, blank sql, oversized SQL)
 *   <li>R8.3 SQL parsing for all supported shapes (SELECT, INSERT, CREATE TABLE AS, CREATE/ALTER
 *       VIEW, CTE, UNION)
 *   <li>R8.4 Table resolution and confidence scoring (exact FQN, defaultService, search fallback,
 *       unresolvable)
 *   <li>R8.5 Plan building (edge structure, confidence = min(source, target), sourcesOnly for bare
 *       SELECT)
 *   <li>R8.6 Apply flow (high-confidence applied, low-confidence requiresConfirmation)
 *   <li>R8.7 Error handling (unparseable SQL, oversized SQL)
 *   <li>Narrative generation
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineageFromSqlToolIntegrationTest {

  private LineageFromSqlTool tool;
  private CatalogSecurityContext securityContext;

  // Injected functional interfaces — no mockStatic(Entity.class) needed
  private McpEntityBridge.EntityReferenceResolver referenceResolver;
  private McpEntityBridge.SearchRepositoryProvider searchRepoProvider;
  private McpEntityBridge.LineageRepositoryProvider lineageRepoProvider;
  private LineageFromSqlTool.LineageAuthorizer noOpAuthorizer;

  @BeforeEach
  void setUp() {
    tool = new LineageFromSqlTool();
    securityContext = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Default: all Entity lookups return null (nothing found)
    referenceResolver = (entityType, fqn, include) -> null;
    // Default: no search repository available
    searchRepoProvider = () -> null;
    // Default: no lineage repository available
    lineageRepoProvider = () -> null;
    // Default: no-op authorizer
    noOpAuthorizer = (entityType) -> {};
  }

  // ====================== Parameter Validation ======================

  @Nested
  class ParameterValidation {

    @Test
    void execute_missingSql_throwsException() {
      Map<String, Object> params = new HashMap<>();
      assertThatThrownBy(
              () ->
                  tool.execute(
                      securityContext,
                      params,
                      referenceResolver,
                      searchRepoProvider,
                      lineageRepoProvider,
                      noOpAuthorizer,
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sql");
    }

    @Test
    void execute_blankSql_throwsException() {
      Map<String, Object> params = Map.of("sql", "   ");
      assertThatThrownBy(
              () ->
                  tool.execute(
                      securityContext,
                      params,
                      referenceResolver,
                      searchRepoProvider,
                      lineageRepoProvider,
                      noOpAuthorizer,
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sql");
    }

    @Test
    void execute_oversizedSql_returnsError() throws IOException {
      String bigSql = "SELECT * FROM " + "x".repeat(4001);
      Map<String, Object> params = Map.of("sql", bigSql);
      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              referenceResolver,
              searchRepoProvider,
              lineageRepoProvider,
              noOpAuthorizer,
              null);
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("exceeds maximum length");
    }
  }

  // ====================== SQL Parsing (R8.3) — Direct method tests ======================

  @Nested
  class SqlParsingSelect {

    @Test
    void bareSelect_extractsSourcesOnly() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("SELECT a, b FROM orders JOIN customers ON orders.cust_id = customers.id"),
          edges,
          sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).containsExactlyInAnyOrder("orders", "customers");
    }

    @Test
    void selectWithSchema_extractsSchemaQualifiedName() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(parseStatement("SELECT * FROM myschema.orders"), edges, sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).containsExactly("myschema.orders");
    }

    @Test
    void selectWithSubquery_extractsBothLevels() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement(
              "SELECT * FROM (SELECT x FROM raw_data) AS sub JOIN lookup ON sub.x = lookup.id"),
          edges,
          sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).containsExactlyInAnyOrder("raw_data", "lookup");
    }
  }

  @Nested
  class SqlParsingInsert {

    @Test
    void insertIntoSelect_extractsTargetAndSource() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("INSERT INTO target_table SELECT * FROM source_table"),
          edges,
          sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).sqlShape).isEqualTo("INSERT");
      assertThat(edges.get(0).targetTable).isEqualTo("target_table");
      assertThat(edges.get(0).sourceTables).containsExactly("source_table");
      assertThat(sourcesOnly).isEmpty();
    }

    @Test
    void insertWithJoin_extractsMultipleSources() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement(
              "INSERT INTO summary SELECT * FROM orders o JOIN customers c ON o.cust_id = c.id"),
          edges,
          sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).targetTable).isEqualTo("summary");
      assertThat(edges.get(0).sourceTables).containsExactlyInAnyOrder("orders", "customers");
    }
  }

  @Nested
  class SqlParsingCreateTableAs {

    @Test
    void createTableAsSelect_extractsTargetAndSource() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("CREATE TABLE summary AS SELECT * FROM raw_data"), edges, sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).sqlShape).isEqualTo("CREATE_TABLE_AS");
      assertThat(edges.get(0).targetTable).isEqualTo("summary");
      assertThat(edges.get(0).sourceTables).containsExactly("raw_data");
    }
  }

  @Nested
  class SqlParsingCreateView {

    @Test
    void createViewAsSelect_extractsTargetAndSource() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("CREATE VIEW customer_view AS SELECT * FROM customers"),
          edges,
          sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).sqlShape).isEqualTo("CREATE_VIEW_AS");
      assertThat(edges.get(0).targetTable).isEqualTo("customer_view");
      assertThat(edges.get(0).sourceTables).containsExactly("customers");
    }

    @Test
    void alterViewAsSelect_extractsTargetAndSource() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("ALTER VIEW customer_view AS SELECT id, name FROM customers"),
          edges,
          sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).sqlShape).isEqualTo("ALTER_VIEW_AS");
      assertThat(edges.get(0).targetTable).isEqualTo("customer_view");
      assertThat(edges.get(0).sourceTables).containsExactly("customers");
    }
  }

  @Nested
  class SqlParsingCte {

    @Test
    void cteInlined_replacesCteRefWithUnderlyingSources() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement(
              "WITH filtered AS (SELECT * FROM raw_data WHERE active = 1) "
                  + "INSERT INTO target SELECT * FROM filtered"),
          edges,
          sourcesOnly);

      assertThat(edges).hasSize(1);
      assertThat(edges.get(0).targetTable).isEqualTo("target");
      // "filtered" CTE should be inlined to "raw_data"
      assertThat(edges.get(0).sourceTables).contains("raw_data");
      assertThat(edges.get(0).sourceTables).doesNotContain("filtered");
    }

    @Test
    void bareSelectWithCte_inlinesSources() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement(
              "WITH enriched AS (SELECT * FROM orders JOIN products ON orders.pid = products.id) SELECT * FROM enriched"),
          edges,
          sourcesOnly);

      assertThat(edges).isEmpty();
      // "enriched" CTE should be inlined to the underlying tables
      assertThat(sourcesOnly).containsExactlyInAnyOrder("orders", "products");
      assertThat(sourcesOnly).doesNotContain("enriched");
    }
  }

  @Nested
  class SqlParsingSetOperations {

    @Test
    void union_extractsSourcesFromAllBranches() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("SELECT a FROM table1 UNION SELECT b FROM table2"), edges, sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).containsExactlyInAnyOrder("table1", "table2");
    }
  }

  @Nested
  class SqlParsingNonLineageStatements {

    @Test
    void updateStatement_ignored() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("UPDATE orders SET status = 'closed' WHERE id = 1"), edges, sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).isEmpty();
    }

    @Test
    void deleteStatement_ignored() {
      List<LineageFromSqlTool.SqlLineageEdge> edges = new java.util.ArrayList<>();
      List<String> sourcesOnly = new java.util.ArrayList<>();
      tool.extractLineageEdges(
          parseStatement("DELETE FROM orders WHERE id = 1"), edges, sourcesOnly);

      assertThat(edges).isEmpty();
      assertThat(sourcesOnly).isEmpty();
    }
  }

  // ====================== Table Resolution & Confidence (R8.4) ======================

  @Nested
  class TableResolution {

    @Test
    void exactFqn_confidence1() {
      EntityReference ref =
          buildEntityRef("postgres.mydb.myschema.orders", UUID.randomUUID(), "table");

      // Inject a resolver that returns the ref for the exact FQN, null for everything else
      McpEntityBridge.EntityReferenceResolver resolver =
          (entityType, fqn, include) -> "postgres.mydb.myschema.orders".equals(fqn) ? ref : null;

      LineageFromSqlTool.ResolvedTable result =
          tool.resolveTable("postgres.mydb.myschema.orders", null, resolver, searchRepoProvider);
      assertThat(result.confidence).isEqualTo(1.0);
      assertThat(result.resolutionNote).isEqualTo("Exact FQN match");
      assertThat(result.entityRef).isNotNull();
    }

    @Test
    void defaultServiceUnique_confidence08() {
      EntityReference ref =
          buildEntityRef("postgres.mydb.myschema.orders", UUID.randomUUID(), "table");

      // Inject a resolver that returns the ref for the service-qualified FQN, null otherwise
      McpEntityBridge.EntityReferenceResolver resolver =
          (entityType, fqn, include) -> "postgres.myschema.orders".equals(fqn) ? ref : null;

      LineageFromSqlTool.ResolvedTable result =
          tool.resolveTable("myschema.orders", "postgres", resolver, searchRepoProvider);
      assertThat(result.confidence).isEqualTo(0.8);
      assertThat(result.resolutionNote).contains("defaultService");
    }

    @Test
    void buildFqnCandidates_generatesCorrectFqns() {
      String[] candidates = tool.buildFqnCandidates("schema.orders", "postgres");
      assertThat(candidates).containsExactly("postgres.schema.orders");
    }

    @Test
    void multipleMatches_confidence05() {
      // In the real code, 0.5 would come from multiple matches found via search.
      // Here we directly create a 0.5-confidence ResolvedTable and verify plan behavior.
      EntityReference ref1 =
          buildEntityRef("postgres.db1.schema.orders", UUID.randomUUID(), "table");

      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("orders", ref1, 0.5, "Multiple matches found");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.summary", UUID.randomUUID(), "table");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("summary", targetRef, 1.0, "Exact FQN match");

      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", target, List.of(source), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());

      // Edge confidence = min(source=0.5, target=1.0) = 0.5
      double confidence = ((Number) plan.get(0).get("confidence")).doubleValue();
      assertThat(confidence).isEqualTo(0.5);
      assertThat(plan.get(0).get("confidenceNote").toString()).contains("0.5");
      assertThat(plan.get(0).get("confidenceNote").toString()).contains("Multiple matches");
    }

    @Test
    void unresolvable_confidence03() {
      // Default resolver returns null → nothing found
      LineageFromSqlTool.ResolvedTable result =
          tool.resolveTable("nonexistent_table", null, referenceResolver, searchRepoProvider);
      assertThat(result.confidence).isEqualTo(0.3);
      assertThat(result.resolutionNote).contains("not found");
      assertThat(result.entityRef).isNull();
    }

    @Test
    void blankTable_confidence03() {
      LineageFromSqlTool.ResolvedTable result =
          tool.resolveTable("  ", null, referenceResolver, searchRepoProvider);
      assertThat(result.confidence).isEqualTo(0.3);
      assertThat(result.entityRef).isNull();
    }
  }

  // ====================== Plan Building (R8.5) ======================

  @Nested
  class PlanBuilding {

    @Test
    void insertEdge_planHasCorrectShape() {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.summary", UUID.randomUUID(), "table");

      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("orders", sourceRef, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("summary", targetRef, 1.0, "Exact FQN match");
      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", target, List.of(source), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());

      assertThat(plan).hasSize(1);
      Map<String, Object> planEntry = plan.get(0);
      assertThat(planEntry).containsEntry("sqlShape", "INSERT");
      assertThat(planEntry.get("from")).isNotNull();
      assertThat(planEntry.get("to")).isNotNull();
      // Edge confidence = min(source=0.8, target=1.0) = 0.8
      assertThat(((Number) planEntry.get("confidence")).doubleValue()).isEqualTo(0.8);
    }

    @Test
    void bareSelect_planHasSourcesOnlyEntry() {
      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("orders", null, 0.3, "Not found");

      List<Map<String, Object>> plan = tool.buildPlan(List.of(), List.of(source));

      assertThat(plan).hasSize(1);
      Map<String, Object> planEntry = plan.get(0);
      assertThat(planEntry).containsEntry("sqlShape", "SELECT");
      assertThat(planEntry.get("from")).isNull();
      assertThat(planEntry.get("to")).isNull();
      assertThat(planEntry).containsKey("sourcesOnly");
      assertThat(planEntry).containsEntry("confidence", 0.0);
    }

    @Test
    void edgeConfidenceIsMinOfSourceAndTarget() {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.src", UUID.randomUUID(), "table");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.tgt", UUID.randomUUID(), "table");

      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("src", sourceRef, 0.5, "Multiple matches");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("tgt", targetRef, 1.0, "Exact FQN match");
      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", target, List.of(source), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());

      double confidence = ((Number) plan.get(0).get("confidence")).doubleValue();
      assertThat(confidence).isEqualTo(0.5); // min(0.5, 1.0)
    }

    @Test
    void cteNameIncludedInPlanWhenPresent() {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.raw", UUID.randomUUID(), "table");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.tgt", UUID.randomUUID(), "table");

      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("raw", sourceRef, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("tgt", targetRef, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge(
              "INSERT", target, List.of(source), "filtered_cte");

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());

      assertThat(plan.get(0)).containsEntry("viaCte", "filtered_cte");
    }
  }

  // ====================== Full execute() flow ======================

  @Nested
  class ExecuteFlow {

    @Test
    void unparseableSql_returnsErrorEnvelope() throws IOException {
      Map<String, Object> params = Map.of("sql", "NOT VALID SQL !!!");
      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              referenceResolver,
              searchRepoProvider,
              lineageRepoProvider,
              noOpAuthorizer,
              null);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("parse SQL");
    }

    @Test
    void insertWithDefaultService_planOnly() throws IOException {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.summary", UUID.randomUUID(), "table");

      // Inject a resolver that returns refs for the service-qualified FQNs, null otherwise
      McpEntityBridge.EntityReferenceResolver resolver =
          (entityType, fqn, include) -> {
            if ("postgres.db.schema.orders".equals(fqn)) return sourceRef;
            if ("postgres.db.schema.summary".equals(fqn)) return targetRef;
            return null;
          };

      Map<String, Object> params = new HashMap<>();
      params.put("sql", "INSERT INTO db.schema.summary SELECT * FROM db.schema.orders");
      params.put("defaultService", "postgres");
      params.put("apply", false);

      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              resolver,
              searchRepoProvider,
              lineageRepoProvider,
              noOpAuthorizer,
              null);

      assertThat(result).containsEntry("apply", false);
      assertThat(result).containsKey("plan");
      assertThat(result).containsKey("edgeCount");
      assertThat(result).containsEntry("sqlShape", "INSERT");
      assertThat(result).containsKey("narrative");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> plan = (List<Map<String, Object>>) result.get("plan");
      // Should have an edge from orders → summary
      long realEdges =
          plan.stream().filter(e -> e.get("from") != null && e.get("to") != null).count();
      assertThat(realEdges).isGreaterThanOrEqualTo(1);
    }

    @Test
    void bareSelect_reportsSourcesOnly() throws IOException {
      // Default resolver returns null for all → tables unresolvable
      Map<String, Object> params =
          Map.of("sql", "SELECT * FROM orders JOIN customers ON orders.cust_id = customers.id");

      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              referenceResolver,
              searchRepoProvider,
              lineageRepoProvider,
              noOpAuthorizer,
              null);

      assertThat(result).containsEntry("sqlShape", "SELECT");
      assertThat(result).containsEntry("apply", false);
      assertThat(result).containsKey("sourcesOnlyCount");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> plan = (List<Map<String, Object>>) result.get("plan");
      // Should have a sourcesOnly entry
      assertThat(plan.stream().anyMatch(e -> e.containsKey("sourcesOnly"))).isTrue();
    }
  }

  // ====================== Apply flow (R8.6) ======================

  @Nested
  class ApplyFlow {

    /**
     * We test the apply logic by calling the extracted {@code applyHighConfidenceEdges()} method
     * directly with pre-built plan entries and injected functional interfaces ({@link
     * LineageFromSqlTool.LineageAuthorizer} + {@link LineageFromSqlTool.LineageAppender} + {@link
     * McpEntityBridge.LineageRepositoryProvider}), completely bypassing Entity static initializer
     * NPEs:
     * (1) lineageRepo=null + high-confidence plan → error result (using injected lineageRepoProvider)
     * (2) low-confidence-only plan → requiresConfirmation, no repo fetch (no Entity calls)
     * (3) happy path with LineageAppender → verifies AddLineage from/to IDs (no Entity calls)
     * (4) mixed confidence → high applied, low confirmed (no Entity calls)
     * (5) appender throws → failed status (no Entity calls)
     * (6) plan-level tests via buildPlan() for edge structure verification
     * (7) execute()-level test for end-to-end low-confidence flow
     */
    @Test
    void applyHighConfidenceEdges_lineageRepoNull_returnsError() {
      UUID sourceId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();

      Map<String, Object> fromSpec = new HashMap<>();
      fromSpec.put("tableName", "orders");
      fromSpec.put("confidence", 1.0);
      fromSpec.put("id", sourceId.toString());
      fromSpec.put("entityType", "table");
      fromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      Map<String, Object> toSpec = new HashMap<>();
      toSpec.put("tableName", "summary");
      toSpec.put("confidence", 1.0);
      toSpec.put("id", targetId.toString());
      toSpec.put("entityType", "table");
      toSpec.put("fullyQualifiedName", "postgres.db.schema.summary");

      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("sqlShape", "INSERT");
      planEntry.put("from", fromSpec);
      planEntry.put("to", toSpec);
      planEntry.put("confidence", 1.0);

      // No-op authorizer + lineageRepoProvider that returns null
      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(planEntry), "test-user", noOpAuthorizer, null, lineageRepoProvider);

      assertThat(result.error).isNotNull();
      assertThat(result.error).contains("not initialized");
      assertThat(result.applied).isEmpty();
      assertThat(result.requiresConfirmation).isEmpty();
    }

    @Test
    void applyHighConfidenceEdges_lowConfidenceOnly_noRepoFetch() {
      Map<String, Object> fromSpec = new HashMap<>();
      fromSpec.put("tableName", "orders");
      fromSpec.put("confidence", 0.8);
      fromSpec.put("id", UUID.randomUUID().toString());
      fromSpec.put("entityType", "table");
      fromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      // Target unresolvable — no id, confidence 0.3
      Map<String, Object> toSpec = new HashMap<>();
      toSpec.put("tableName", "summary");
      toSpec.put("confidence", 0.3);
      // No id — target unresolvable
      toSpec.put("entityType", "table");

      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("sqlShape", "INSERT");
      planEntry.put("from", fromSpec);
      planEntry.put("to", toSpec);
      planEntry.put("confidence", 0.3); // min(0.8, 0.3)

      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(planEntry), "test-user", noOpAuthorizer, null, lineageRepoProvider);

      assertThat(result.error).isNull();
      assertThat(result.applied).isEmpty();
      assertThat(result.requiresConfirmation).hasSize(1);
      assertThat(result.requiresConfirmation.get(0))
          .containsEntry("status", "requiresConfirmation");
    }

    @Test
    void applyHighConfidenceEdges_happyPath_createsLineage() throws Exception {
      UUID sourceId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();

      Map<String, Object> fromSpec = new HashMap<>();
      fromSpec.put("tableName", "orders");
      fromSpec.put("confidence", 1.0);
      fromSpec.put("id", sourceId.toString());
      fromSpec.put("entityType", "table");
      fromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      Map<String, Object> toSpec = new HashMap<>();
      toSpec.put("tableName", "summary");
      toSpec.put("confidence", 1.0);
      toSpec.put("id", targetId.toString());
      toSpec.put("entityType", "table");
      toSpec.put("fullyQualifiedName", "postgres.db.schema.summary");

      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("sqlShape", "INSERT");
      planEntry.put("from", fromSpec);
      planEntry.put("to", toSpec);
      planEntry.put("confidence", 1.0);

      // Capture the AddLineage passed to the appender
      List<AddLineage> capturedLineages = new ArrayList<>();
      LineageFromSqlTool.LineageAppender appender =
          (addLineage, user) -> {
            capturedLineages.add(addLineage);
          };

      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(planEntry), "test-user", noOpAuthorizer, appender, lineageRepoProvider);

      assertThat(result.error).isNull();
      assertThat(result.applied).hasSize(1);
      assertThat(result.applied.get(0)).containsEntry("status", "applied");
      assertThat(result.applied.get(0)).containsEntry("sqlShape", "INSERT");
      assertThat(result.requiresConfirmation).isEmpty();

      // Verify the appender was called with the correct from/to entity references
      assertThat(capturedLineages).hasSize(1);
      AddLineage captured = capturedLineages.get(0);
      assertThat(captured.getEdge().getFromEntity().getId()).isEqualTo(sourceId);
      assertThat(captured.getEdge().getToEntity().getId()).isEqualTo(targetId);
      assertThat(captured.getEdge().getFromEntity().getType()).isEqualTo("table");
      assertThat(captured.getEdge().getToEntity().getType()).isEqualTo("table");
    }

    @Test
    void applyHighConfidenceEdges_mixedConfidence_appliesHighAndConfirmsLow() throws Exception {
      UUID sourceId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();

      // High-confidence edge
      Map<String, Object> highFromSpec = new HashMap<>();
      highFromSpec.put("tableName", "orders");
      highFromSpec.put("confidence", 1.0);
      highFromSpec.put("id", sourceId.toString());
      highFromSpec.put("entityType", "table");
      highFromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      Map<String, Object> highToSpec = new HashMap<>();
      highToSpec.put("tableName", "summary");
      highToSpec.put("confidence", 1.0);
      highToSpec.put("id", targetId.toString());
      highToSpec.put("entityType", "table");
      highToSpec.put("fullyQualifiedName", "postgres.db.schema.summary");

      Map<String, Object> highEdge = new HashMap<>();
      highEdge.put("sqlShape", "INSERT");
      highEdge.put("from", highFromSpec);
      highEdge.put("to", highToSpec);
      highEdge.put("confidence", 1.0);

      // Low-confidence edge (target unresolved — no id)
      Map<String, Object> lowFromSpec = new HashMap<>();
      lowFromSpec.put("tableName", "staging");
      lowFromSpec.put("confidence", 0.8);
      lowFromSpec.put("id", UUID.randomUUID().toString());
      lowFromSpec.put("entityType", "table");
      lowFromSpec.put("fullyQualifiedName", "postgres.db.schema.staging");

      Map<String, Object> lowToSpec = new HashMap<>();
      lowToSpec.put("tableName", "missing_target");
      lowToSpec.put("confidence", 0.3);
      lowToSpec.put("entityType", "table");
      // No id → unresolvable target

      Map<String, Object> lowEdge = new HashMap<>();
      lowEdge.put("sqlShape", "INSERT");
      lowEdge.put("from", lowFromSpec);
      lowEdge.put("to", lowToSpec);
      lowEdge.put("confidence", 0.3);

      // Capture lineage calls
      List<AddLineage> capturedLineages = new ArrayList<>();
      LineageFromSqlTool.LineageAppender appender =
          (addLineage, user) -> {
            capturedLineages.add(addLineage);
          };

      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(highEdge, lowEdge),
              "test-user",
              noOpAuthorizer,
              appender,
              lineageRepoProvider);

      assertThat(result.error).isNull();
      assertThat(result.applied).hasSize(1);
      assertThat(result.applied.get(0)).containsEntry("status", "applied");
      assertThat(result.requiresConfirmation).hasSize(1);
      assertThat(result.requiresConfirmation.get(0))
          .containsEntry("status", "requiresConfirmation");

      // Only the high-confidence edge should have been passed to the appender
      assertThat(capturedLineages).hasSize(1);
    }

    @Test
    void applyHighConfidenceEdges_appenderThrows_returnsFailedStatus() throws Exception {
      UUID sourceId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();

      Map<String, Object> fromSpec = new HashMap<>();
      fromSpec.put("tableName", "orders");
      fromSpec.put("confidence", 1.0);
      fromSpec.put("id", sourceId.toString());
      fromSpec.put("entityType", "table");
      fromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      Map<String, Object> toSpec = new HashMap<>();
      toSpec.put("tableName", "summary");
      toSpec.put("confidence", 1.0);
      toSpec.put("id", targetId.toString());
      toSpec.put("entityType", "table");
      toSpec.put("fullyQualifiedName", "postgres.db.schema.summary");

      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("sqlShape", "INSERT");
      planEntry.put("from", fromSpec);
      planEntry.put("to", toSpec);
      planEntry.put("confidence", 1.0);

      // Appender that throws
      LineageFromSqlTool.LineageAppender throwingAppender =
          (addLineage, user) -> {
            throw new RuntimeException("Lineage creation failed: duplicate edge");
          };

      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(planEntry),
              "test-user",
              noOpAuthorizer,
              throwingAppender,
              lineageRepoProvider);

      assertThat(result.error).isNull(); // Not a fatal error — just a per-edge failure
      assertThat(result.applied).hasSize(1);
      assertThat(result.applied.get(0)).containsEntry("status", "failed");
      assertThat(result.requiresConfirmation).isEmpty();
      assertThat(result.applied.get(0))
          .containsEntry("error", "Lineage creation failed: duplicate edge");
    }

    @Test
    void applyHighConfidenceEdges_authorizerThrows_returnsFailedStatus() throws Exception {
      UUID sourceId = UUID.randomUUID();
      UUID targetId = UUID.randomUUID();

      Map<String, Object> fromSpec = new HashMap<>();
      fromSpec.put("tableName", "orders");
      fromSpec.put("confidence", 1.0);
      fromSpec.put("id", sourceId.toString());
      fromSpec.put("entityType", "table");
      fromSpec.put("fullyQualifiedName", "postgres.db.schema.orders");

      Map<String, Object> toSpec = new HashMap<>();
      toSpec.put("tableName", "summary");
      toSpec.put("confidence", 1.0);
      toSpec.put("id", targetId.toString());
      toSpec.put("entityType", "table");
      toSpec.put("fullyQualifiedName", "postgres.db.schema.summary");

      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("sqlShape", "INSERT");
      planEntry.put("from", fromSpec);
      planEntry.put("to", toSpec);
      planEntry.put("confidence", 1.0);

      // Authorizer that denies permission
      LineageFromSqlTool.LineageAuthorizer denyingAuthorizer =
          (entityType) -> {
            throw new RuntimeException("Permission denied: EDIT_LINEAGE on " + entityType);
          };

      // Appender should never be called — authorization fails first
      List<AddLineage> capturedLineages = new ArrayList<>();
      LineageFromSqlTool.LineageAppender appender =
          (addLineage, user) -> {
            capturedLineages.add(addLineage);
          };

      LineageFromSqlTool.ApplyResult result =
          tool.applyHighConfidenceEdges(
              List.of(planEntry), "test-user", denyingAuthorizer, appender, lineageRepoProvider);

      assertThat(result.error).isNull(); // Not a fatal error — per-edge failure
      assertThat(result.applied).hasSize(1);
      assertThat(result.applied.get(0)).containsEntry("status", "failed");
      assertThat(result.applied.get(0))
          .containsEntry("error", "Permission denied: EDIT_LINEAGE on table");
      assertThat(result.requiresConfirmation).isEmpty();
      // Appender should never have been called — authorization failed first
      assertThat(capturedLineages).isEmpty();
    }

    @Test
    void highConfidenceEdge_wouldBeApplied() {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");
      EntityReference targetRef =
          buildEntityRef("postgres.db.schema.summary", UUID.randomUUID(), "table");

      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("orders", sourceRef, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("summary", targetRef, 1.0, "Exact FQN match");
      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", target, List.of(source), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());
      Map<String, Object> planEntry = plan.get(0);

      // Verify the plan entry has all fields needed for apply: confidence ≥ 0.8, from/to IDs
      assertThat(((Number) planEntry.get("confidence")).doubleValue()).isGreaterThanOrEqualTo(0.8);
      @SuppressWarnings("unchecked")
      Map<String, Object> fromSpec = (Map<String, Object>) planEntry.get("from");
      @SuppressWarnings("unchecked")
      Map<String, Object> toSpec = (Map<String, Object>) planEntry.get("to");
      assertThat(fromSpec).containsKey("id");
      assertThat(toSpec).containsKey("id");
      assertThat(fromSpec.get("id")).isNotNull();
      assertThat(toSpec.get("id")).isNotNull();
    }

    @Test
    void lowConfidenceEdge_wouldRequireConfirmation() {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");

      // Source resolves at 0.8, target unresolvable at 0.3 → edge confidence = 0.3
      LineageFromSqlTool.ResolvedTable source =
          new LineageFromSqlTool.ResolvedTable("orders", sourceRef, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable target =
          new LineageFromSqlTool.ResolvedTable("summary", null, 0.3, "Table not found");
      LineageFromSqlTool.ResolvedLineageEdge edge =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", target, List.of(source), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge), List.of());
      Map<String, Object> planEntry = plan.get(0);

      // Edge confidence = min(0.8, 0.3) = 0.3 < 0.8 → would go to requiresConfirmation
      assertThat(((Number) planEntry.get("confidence")).doubleValue()).isLessThan(0.8);
      @SuppressWarnings("unchecked")
      Map<String, Object> toSpec = (Map<String, Object>) planEntry.get("to");
      // Unresolvable target has no ID — can't be auto-applied
      assertThat(toSpec).doesNotContainKey("id");
    }

    @Test
    void applyTrue_lowConfidenceOnly_goesToRequiresConfirmation() throws IOException {
      EntityReference sourceRef =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");

      // Inject a resolver that resolves orders but not the target "summary"
      McpEntityBridge.EntityReferenceResolver resolver =
          (entityType, fqn, include) -> "postgres.db.schema.orders".equals(fqn) ? sourceRef : null;

      Map<String, Object> params = new HashMap<>();
      params.put("sql", "INSERT INTO db.schema.summary SELECT * FROM db.schema.orders");
      params.put("defaultService", "postgres");
      params.put("apply", true);

      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              resolver,
              searchRepoProvider,
              lineageRepoProvider,
              noOpAuthorizer,
              null);

      assertThat(result).containsEntry("apply", true);
      assertThat(result).containsKey("requiresConfirmation");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> requiresConfirmation =
          (List<Map<String, Object>>) result.get("requiresConfirmation");
      assertThat(requiresConfirmation).isNotEmpty();
      assertThat(requiresConfirmation.get(0)).containsEntry("status", "requiresConfirmation");
    }

    @Test
    void mixedConfidence_highApplied_lowRequiresConfirmation() {
      EntityReference src1Ref =
          buildEntityRef("postgres.db.schema.orders", UUID.randomUUID(), "table");
      EntityReference tgt1Ref =
          buildEntityRef("postgres.db.schema.summary", UUID.randomUUID(), "table");
      EntityReference src2Ref =
          buildEntityRef("postgres.db.schema.staging", UUID.randomUUID(), "table");

      // Edge 1: high confidence (both source and target resolved)
      LineageFromSqlTool.ResolvedTable src1 =
          new LineageFromSqlTool.ResolvedTable("orders", src1Ref, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable tgt1 =
          new LineageFromSqlTool.ResolvedTable("summary", tgt1Ref, 1.0, "Exact FQN match");
      LineageFromSqlTool.ResolvedLineageEdge edge1 =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", tgt1, List.of(src1), null);

      // Edge 2: low confidence (source resolved, target not found)
      LineageFromSqlTool.ResolvedTable src2 =
          new LineageFromSqlTool.ResolvedTable("staging", src2Ref, 0.8, "Unique match");
      LineageFromSqlTool.ResolvedTable tgt2 =
          new LineageFromSqlTool.ResolvedTable("missing_target", null, 0.3, "Table not found");
      LineageFromSqlTool.ResolvedLineageEdge edge2 =
          new LineageFromSqlTool.ResolvedLineageEdge("INSERT", tgt2, List.of(src2), null);

      List<Map<String, Object>> plan = tool.buildPlan(List.of(edge1, edge2), List.of());

      // Verify plan has 2 edges with different confidence levels
      assertThat(plan).hasSize(2);
      double conf1 = ((Number) plan.get(0).get("confidence")).doubleValue();
      double conf2 = ((Number) plan.get(1).get("confidence")).doubleValue();
      assertThat(conf1).isGreaterThanOrEqualTo(0.8); // high → would be applied
      assertThat(conf2).isLessThan(0.8); // low → would require confirmation
    }
  }

  // ====================== Narrative Generation ======================

  @Nested
  class NarrativeGeneration {

    @Test
    void planOnly_narrativeMentionsApply() {
      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("from", Map.of("tableName", "orders", "confidence", 0.8));
      planEntry.put("to", Map.of("tableName", "summary", "confidence", 1.0));
      planEntry.put("confidence", 0.8);
      planEntry.put("sqlShape", "INSERT");

      String narrative =
          tool.generateNarrative(List.of(planEntry), List.of(), List.of(), "INSERT", false);
      assertThat(narrative).contains("INSERT");
      assertThat(narrative).contains("apply=true");
      assertThat(narrative).contains("high-confidence");
    }

    @Test
    void applied_narrativeMentionsApplied() {
      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("from", Map.of("tableName", "orders", "confidence", 0.8));
      planEntry.put("to", Map.of("tableName", "summary", "confidence", 1.0));
      planEntry.put("confidence", 0.8);
      planEntry.put("sqlShape", "INSERT");

      Map<String, Object> appliedEntry = new HashMap<>(planEntry);
      appliedEntry.put("status", "applied");

      String narrative =
          tool.generateNarrative(
              List.of(planEntry), List.of(appliedEntry), List.of(), "INSERT", true);
      assertThat(narrative).contains("Applied");
      assertThat(narrative).contains("1 edges created");
    }

    @Test
    void requiresConfirmation_narrativeMentionsIt() {
      Map<String, Object> planEntry = new HashMap<>();
      planEntry.put("from", Map.of("tableName", "orders", "confidence", 0.3));
      planEntry.put("to", Map.of("tableName", "summary", "confidence", 0.3));
      planEntry.put("confidence", 0.3);
      planEntry.put("sqlShape", "INSERT");

      Map<String, Object> confirmEntry = new HashMap<>(planEntry);
      confirmEntry.put("status", "requiresConfirmation");

      String narrative =
          tool.generateNarrative(
              List.of(planEntry), List.of(), List.of(confirmEntry), "INSERT", true);
      assertThat(narrative).contains("Requires confirmation");
    }

    @Test
    void emptyPlan_narrativeSaysNoEdges() {
      String narrative = tool.generateNarrative(List.of(), List.of(), List.of(), "unknown", false);
      assertThat(narrative).contains("No lineage edges");
    }
  }

  // ====================== buildFqnCandidates ======================

  @Nested
  class BuildFqnCandidates {

    @Test
    void bareTableName_prependsService() {
      String[] candidates = tool.buildFqnCandidates("orders", "postgres");
      assertThat(candidates).containsExactly("postgres.orders");
    }

    @Test
    void schemaQualifiedName_prependsService() {
      String[] candidates = tool.buildFqnCandidates("myschema.orders", "postgres");
      assertThat(candidates).containsExactly("postgres.myschema.orders");
    }
  }

  // ====================== Helpers ======================

  private EntityReference buildEntityRef(String fqn, UUID id, String type) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    when(ref.getType()).thenReturn(type);
    when(ref.getName()).thenReturn(fqn);
    return ref;
  }

  /** Parses a SQL string into a JSQLParser Statement for direct method testing. */
  private net.sf.jsqlparser.statement.Statement parseStatement(String sql) {
    try {
      java.util.List<net.sf.jsqlparser.statement.Statement> stmts =
          net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(sql);
      return stmts.get(0);
    } catch (net.sf.jsqlparser.JSQLParserException e) {
      throw new RuntimeException("Test SQL parse failed: " + sql, e);
    }
  }
}
