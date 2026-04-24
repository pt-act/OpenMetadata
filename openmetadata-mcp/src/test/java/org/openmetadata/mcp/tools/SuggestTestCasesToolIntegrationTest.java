package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnConstraint;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Integration tests for {@link SuggestTestCasesTool}.
 *
 * <p>Strategy: Tests are split into two categories:
 *
 * <ol>
 *   <li>Direct static method tests for @VisibleForTesting methods (no repository mocking needed)
 *   <li>Full execute() flow tests using injected functional interfaces via {@link McpEntityBridge}
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuggestTestCasesToolIntegrationTest {

  private SuggestTestCasesTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;

  @BeforeEach
  void setUp() {
    tool = new SuggestTestCasesTool();
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

    noopAuthorizer = (entityType, op) -> {};
  }

  // ====================== Helper methods ======================

  private EntityReference buildEntityRef(String fqn, UUID id, String type) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    when(ref.getType()).thenReturn(type);
    return ref;
  }

  private Table buildTableWithPKAndNotNull(String fqn) {
    Table table = new Table();
    table.setName(fqn);

    Column idCol = new Column();
    idCol.setName("id");
    idCol.setConstraint(ColumnConstraint.PRIMARY_KEY);

    Column nameCol = new Column();
    nameCol.setName("name");
    nameCol.setConstraint(ColumnConstraint.NOT_NULL);

    Column emailCol = new Column();
    emailCol.setName("email");
    // No constraint

    table.setColumns(List.of(idCol, nameCol, emailCol));

    TableConstraint pkConstraint = new TableConstraint();
    pkConstraint.setConstraintType(TableConstraint.ConstraintType.PRIMARY_KEY);
    pkConstraint.setColumns(List.of("id"));

    TableConstraint fkConstraint = new TableConstraint();
    fkConstraint.setConstraintType(TableConstraint.ConstraintType.FOREIGN_KEY);
    fkConstraint.setColumns(List.of("id"));
    fkConstraint.setReferredColumns(List.of("user_id"));

    table.setTableConstraints(List.of(pkConstraint, fkConstraint));

    return table;
  }

  private Table buildTableNoConstraints(String fqn) {
    Table table = new Table();
    table.setName(fqn);

    Column col1 = new Column();
    col1.setName("col1");
    // No constraint

    Column col2 = new Column();
    col2.setName("col2");

    table.setColumns(List.of(col1, col2));
    return table;
  }

  private SearchRepository createEmptySearchRepo() throws Exception {
    SearchRepository searchRepo = mock(SearchRepository.class);
    when(searchRepo.getIndexOrAliasName(anyString())).thenReturn("search_index");

    Response searchResponse = mock(Response.class);
    doReturn(searchResponse)
        .when(searchRepo)
        .searchWithDirectQuery(any(), any(SubjectContext.class));
    when(searchResponse.getEntity()).thenReturn(new HashMap<>());

    return searchRepo;
  }

  /** Calls the test-friendly execute overload with injected providers. */
  private Map<String, Object> executeWithProviders(
      SuggestTestCasesTool tool,
      Map<String, Object> params,
      EntityReference entityRef,
      Table table,
      SearchRepository searchRepo) {

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.RepositoryProvider repoProvider =
        entityType -> {
          EntityRepository<?> repo = mock(EntityRepository.class);
          try {
            doReturn(table != null ? table : new Object())
                .when(repo)
                .getByName(any(), anyString(), any());
            when(repo.getFields(anyString()))
                .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return repo;
        };
    McpEntityBridge.EntityFetcher entityFetcher =
        (entityType, fqn, fields, include) -> null; // No profiler data by default
    McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepo;

    try {
      return tool.execute(
          params,
          securityContext,
          resolver,
          noopAuthorizer,
          repoProvider,
          entityFetcher,
          searchRepoProvider);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ====================== proposeNotNullTests (static, no mocking) ======================

  @Nested
  class ProposeNotNullTests {

    @Test
    void tableWithPKAndNotNull_generatesProposals() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeNotNullTests(table, "db.schema.orders", Set.of());

      assertThat(proposals).hasSize(2); // id (PK) + name (NOT_NULL)
      assertThat(
              proposals.stream()
                  .map(p -> p.get("testDefinitionFqn"))
                  .allMatch(fqn -> "columnValuesToBeNotNull".equals(fqn)))
          .isTrue();
      assertThat(proposals.stream().map(p -> p.get("columnName")))
          .containsExactlyInAnyOrder("id", "name");
    }

    @Test
    void pkColumn_hasHigherConfidence() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeNotNullTests(table, "db.schema.orders", Set.of());

      Map<String, Object> idProposal =
          proposals.stream()
              .filter(p -> "id".equals(p.get("columnName")))
              .findFirst()
              .orElseThrow();
      assertThat((Double) idProposal.get("confidence")).isEqualTo(0.95);

      Map<String, Object> nameProposal =
          proposals.stream()
              .filter(p -> "name".equals(p.get("columnName")))
              .findFirst()
              .orElseThrow();
      assertThat((Double) nameProposal.get("confidence")).isEqualTo(0.9);
    }

    @Test
    void nullColumns_skipped() {
      Table table = buildTableNoConstraints("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeNotNullTests(table, "db.schema.orders", Set.of());
      assertThat(proposals).isEmpty();
    }

    @Test
    void noColumns_returnsEmpty() {
      Table table = new Table();
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeNotNullTests(table, "db.schema.orders", Set.of());
      assertThat(proposals).isEmpty();
    }

    @Test
    void proposalIncludesEntityLink() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeNotNullTests(table, "db.schema.orders", Set.of());

      for (Map<String, Object> p : proposals) {
        String entityLink = (String) p.get("entityLink");
        assertThat(entityLink).startsWith("<#E::table::db.schema.orders::columns::");
      }
    }
  }

  // ====================== proposeUniqueTests (static, no mocking) ======================

  @Nested
  class ProposeUniqueTests {

    @Test
    void tableWithPK_generatesUniqueProposal() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeUniqueTests(table, "db.schema.orders", Set.of());

      assertThat(proposals).hasSize(1);
      assertThat(proposals.get(0).get("testDefinitionFqn")).isEqualTo("columnValuesToBeUnique");
      assertThat(proposals.get(0).get("columnName")).isEqualTo("id");
      assertThat((Double) proposals.get(0).get("confidence")).isEqualTo(0.95);
    }

    @Test
    void alreadyHasUniqueTest_stillGeneratesProposals() {
      // Uniqueness is per-column; we no longer short-circuit because a composite PK
      // (id, org_id) may have one column with the test but the other still needs it
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeUniqueTests(
              table, "db.schema.orders", Set.of("columnValuesToBeUnique"));
      assertThat(proposals).hasSize(1); // still proposes for PK column "id"
    }

    @Test
    void noPK_returnsEmpty() {
      Table table = buildTableNoConstraints("db.schema.orders");
      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeUniqueTests(table, "db.schema.orders", Set.of());
      assertThat(proposals).isEmpty();
    }
  }

  // ====================== proposeRowCountTests (static, no mocking) ======================

  @Nested
  class ProposeRowCountTests {

    @Test
    void noProfilerData_lowConfidenceProposal() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.EntityFetcher fetcher = (type, fqn, fields, inc) -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeRowCountTests(table, "db.schema.orders", Set.of(), fetcher);

      assertThat(proposals).hasSize(1);
      assertThat(proposals.get(0).get("testDefinitionFqn")).isEqualTo("tableRowCountToBeBetween");
      assertThat((Double) proposals.get(0).get("confidence")).isEqualTo(0.3);
    }

    @Test
    void alreadyHasRowCountTest_returnsEmpty() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.EntityFetcher fetcher = (type, fqn, fields, inc) -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeRowCountTests(
              table, "db.schema.orders", Set.of("tableRowCountToBeBetween"), fetcher);
      assertThat(proposals).isEmpty();
    }

    @Test
    void withProfilerData_higherConfidence() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");

      // Build entity with profile data
      Map<String, Object> entityMap = new HashMap<>();
      Map<String, Object> profile = new HashMap<>();
      profile.put("rowCount", 1000);
      profile.put("rowCountStdDev", 100.0);
      entityMap.put("profile", profile);

      McpEntityBridge.EntityFetcher fetcher = (type, fqn, fields, inc) -> entityMap;

      try (var jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{\"profile\":{}}");
        jsonMock.when(() -> JsonUtils.readValue(anyString(), eq(Map.class))).thenReturn(entityMap);

        List<Map<String, Object>> proposals =
            SuggestTestCasesTool.proposeRowCountTests(table, "db.schema.orders", Set.of(), fetcher);

        assertThat(proposals).hasSize(1);
        Map<String, Object> params = (Map<String, Object>) proposals.get(0).get("parameters");
        assertThat(params.get("minValue")).isEqualTo(700L); // 1000 - 3*100
        assertThat(params.get("maxValue")).isEqualTo(1300L); // 1000 + 3*100
        assertThat((Double) proposals.get(0).get("confidence")).isEqualTo(0.85);
      }
    }
  }

  // ====================== proposeFreshnessTests (static, no mocking) ======================

  @Nested
  class ProposeFreshnessTests {

    @Test
    void noCadenceData_defaultProposal() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.EntityFetcher fetcher = (type, fqn, fields, inc) -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeFreshnessTests(table, "db.schema.orders", Set.of(), fetcher);

      assertThat(proposals).hasSize(1);
      assertThat(proposals.get(0).get("testDefinitionFqn")).isEqualTo("tableFreshness");
      Map<String, Object> params = (Map<String, Object>) proposals.get(0).get("parameters");
      assertThat(params.get("freshnessThreshold")).isEqualTo(36);
      assertThat((Double) proposals.get(0).get("confidence")).isEqualTo(0.4);
    }

    @Test
    void alreadyHasFreshnessTest_returnsEmpty() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.EntityFetcher fetcher = (type, fqn, fields, inc) -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeFreshnessTests(
              table, "db.schema.orders", Set.of("tableFreshness"), fetcher);
      assertThat(proposals).isEmpty();
    }
  }

  // ====================== proposeReferentialIntegrityTests (static, no mocking)
  // ======================

  @Nested
  class ProposeReferentialIntegrityTests {

    @Test
    void tableWithFK_generatesRIProposal() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeReferentialIntegrityTests(
              table, "db.schema.orders", Set.of(), searchRepoProvider);

      assertThat(proposals).hasSize(1);
      assertThat(proposals.get(0).get("testDefinitionFqn")).isEqualTo("tableCustomSQLQuery");
      assertThat((Double) proposals.get(0).get("confidence")).isEqualTo(0.7);
    }

    @Test
    void noFK_returnsEmpty() {
      Table table = buildTableNoConstraints("db.schema.orders");
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeReferentialIntegrityTests(
              table, "db.schema.orders", Set.of(), searchRepoProvider);
      assertThat(proposals).isEmpty();
    }

    @Test
    void existingCustomSQLTest_stillProposesRI() {
      // RI proposals use tableCustomSQLQuery; dedup on that name would suppress ALL
      // custom SQL tests, which is too aggressive. So we still propose even if one exists.
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> null;

      List<Map<String, Object>> proposals =
          SuggestTestCasesTool.proposeReferentialIntegrityTests(
              table, "db.schema.orders", Set.of("tableCustomSQLQuery"), searchRepoProvider);
      // Still generates proposal — dedup is intentionally not applied for RI/tableCustomSQLQuery
      assertThat(proposals).hasSize(1);
    }
  }

  // ====================== generateNarrative (static, no mocking) ======================

  @Nested
  class GenerateNarrative {

    @Test
    void emptyProposals_givesNoNewProposalsMessage() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      String narrative =
          SuggestTestCasesTool.generateNarrative("db.schema.orders", table, List.of());
      assertThat(narrative).contains("No new test case proposals");
    }

    @Test
    void withProposals_groupsByTestDefinition() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      Map<String, Object> p1 = new LinkedHashMap<>();
      p1.put("testDefinitionFqn", "columnValuesToBeNotNull");
      p1.put("columnName", "id");
      p1.put("confidence", 0.95);
      p1.put("rationale", "PK column should not be null");

      Map<String, Object> p2 = new LinkedHashMap<>();
      p2.put("testDefinitionFqn", "columnValuesToBeUnique");
      p2.put("columnName", "id");
      p2.put("confidence", 0.95);
      p2.put("rationale", "PK column must be unique");

      String narrative =
          SuggestTestCasesTool.generateNarrative("db.schema.orders", table, List.of(p1, p2));

      assertThat(narrative).contains("2 proposal(s)");
      assertThat(narrative).contains("columnValuesToBeNotNull");
      assertThat(narrative).contains("columnValuesToBeUnique");
      assertThat(narrative).contains("create_test_case");
    }
  }

  // ====================== Helper methods (static, no mocking) ======================

  @Nested
  class HelperMethods {

    @Test
    void isColumnInPrimaryKey_trueForPKColumn() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      assertThat(SuggestTestCasesTool.isColumnInPrimaryKey(table, "id")).isTrue();
    }

    @Test
    void isColumnInPrimaryKey_falseForNonPKColumn() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      assertThat(SuggestTestCasesTool.isColumnInPrimaryKey(table, "email")).isFalse();
    }

    @Test
    void getPrimaryKeyColumns_returnsPKColumnNames() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      assertThat(SuggestTestCasesTool.getPrimaryKeyColumns(table)).containsExactly("id");
    }

    @Test
    void getForeignKeyConstraints_returnsFKConstraints() {
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      List<TableConstraint> fkConstraints = SuggestTestCasesTool.getForeignKeyConstraints(table);
      assertThat(fkConstraints).hasSize(1);
      assertThat(fkConstraints.get(0).getConstraintType())
          .isEqualTo(TableConstraint.ConstraintType.FOREIGN_KEY);
    }

    @Test
    void buildColumnEntityLink_correctFormat() {
      assertThat(SuggestTestCasesTool.buildColumnEntityLink("db.schema.orders", "id"))
          .isEqualTo("<#E::table::db.schema.orders::columns::id>");
    }

    @Test
    void buildTableEntityLink_correctFormat() {
      assertThat(SuggestTestCasesTool.buildTableEntityLink("db.schema.orders"))
          .isEqualTo("<#E::table::db.schema.orders>");
    }

    @Test
    void buildRISql_correctFormat() {
      String sql = SuggestTestCasesTool.buildRISql("db.schema.orders", List.of("id"));
      assertThat(sql).contains("db_schema_orders");
      assertThat(sql).contains("SELECT * FROM");
      assertThat(sql).contains("referenced_table");
    }
  }

  // ====================== execute() flow — basic proposal generation ======================

  @Nested
  class ExecuteFlow {

    @Test
    void execute_tableWithConstraints_generatesAllProposalTypes() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");
      Table table = buildTableWithPKAndNotNull("db.schema.orders");
      SearchRepository searchRepo = createEmptySearchRepo();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");

      try (var jsonMock = mockStatic(JsonUtils.class);
          var authMock = mockStatic(org.openmetadata.service.security.DefaultAuthorizer.class)) {
        SubjectContext subjectContext = mock(SubjectContext.class);
        authMock
            .when(
                () ->
                    org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext(
                        securityContext))
            .thenReturn(subjectContext);
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");
        jsonMock
            .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
            .thenReturn(new HashMap<>());

        Map<String, Object> result =
            executeWithProviders(tool, params, entityRef, table, searchRepo);

        assertThat(result).containsKey("results");
        assertThat(result).containsKey("narrative");
        assertThat(result).containsEntry("fqn", "db.schema.orders");
        assertThat(result).containsEntry("entityType", "table");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        Map<String, Object> resultData = results.get(0);

        assertThat(resultData).containsKey("proposals");
        assertThat(resultData).containsKey("proposalCount");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals =
            (List<Map<String, Object>>) resultData.get("proposals");

        // Should have: 2 not-null (id PK + name NOT_NULL) + 1 unique (id PK) + 1 row count + 1
        // freshness + 1 RI
        assertThat(proposals.size()).isGreaterThanOrEqualTo(4);

        Set<String> testDefs =
            proposals.stream()
                .map(p -> (String) p.get("testDefinitionFqn"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(testDefs).contains("columnValuesToBeNotNull");
        assertThat(testDefs).contains("columnValuesToBeUnique");
        assertThat(testDefs).contains("tableRowCountToBeBetween");
        assertThat(testDefs).contains("tableFreshness");
      }
    }

    @Test
    void execute_tableNotFound_returnsError() throws Exception {
      EntityReference entityRef =
          buildEntityRef("db.schema.nonexistent", UUID.randomUUID(), "table");
      SearchRepository searchRepo = createEmptySearchRepo();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.nonexistent");

      // Use a repoProvider that returns null from getByName
      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
      McpEntityBridge.RepositoryProvider repoProvider =
          entityType -> {
            EntityRepository<?> repo = mock(EntityRepository.class);
            try {
              doReturn(null).when(repo).getByName(any(), anyString(), any());
              when(repo.getFields(anyString()))
                  .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            return repo;
          };
      McpEntityBridge.EntityFetcher entityFetcher = (type, fqn, fields, inc) -> null;

      try (var jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");

        Map<String, Object> result =
            tool.execute(
                params,
                securityContext,
                resolver,
                noopAuthorizer,
                repoProvider,
                entityFetcher,
                () -> searchRepo);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        Map<String, Object> resultData = results.get(0);
        assertThat(resultData.get("error").toString()).contains("not found");
        assertThat(resultData.get("proposalCount")).isEqualTo(0);
      }
    }

    @Test
    void execute_noConstraints_generatesOnlyRowCountAndFreshness() throws Exception {
      EntityReference entityRef = buildEntityRef("db.schema.simple", UUID.randomUUID(), "table");
      Table table = buildTableNoConstraints("db.schema.simple");
      SearchRepository searchRepo = createEmptySearchRepo();

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.simple");

      try (var jsonMock = mockStatic(JsonUtils.class);
          var authMock = mockStatic(org.openmetadata.service.security.DefaultAuthorizer.class)) {
        SubjectContext subjectContext = mock(SubjectContext.class);
        authMock
            .when(
                () ->
                    org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext(
                        securityContext))
            .thenReturn(subjectContext);
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");
        jsonMock
            .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
            .thenReturn(new HashMap<>());

        Map<String, Object> result =
            executeWithProviders(tool, params, entityRef, table, searchRepo);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        Map<String, Object> resultData = results.get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals =
            (List<Map<String, Object>>) resultData.get("proposals");

        // No constraints → only row count + freshness = 2 proposals
        assertThat(proposals).hasSize(2);
        Set<String> testDefs =
            proposals.stream()
                .map(p -> (String) p.get("testDefinitionFqn"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(testDefs)
            .containsExactlyInAnyOrder("tableRowCountToBeBetween", "tableFreshness");
      }
    }
  }

  // ====================== enforceByteCap (static) ======================

  @Nested
  class EnforceByteCap {

    @Test
    void underCap_noMutation() {
      Map<String, Object> result = new LinkedHashMap<>();
      Map<String, Object> resultData = new LinkedHashMap<>();
      resultData.put("proposals", List.of());
      resultData.put("proposalCount", 0);
      result.put("results", List.of(resultData));

      try (var jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{\"small\":true}");

        Map<String, Object> capped = SuggestTestCasesTool.enforceByteCap(result);
        assertThat(capped).isSameAs(result);
        assertThat(capped).doesNotContainKey("warnings");
      }
    }

    @Test
    void overCap_truncatesProposals() {
      Map<String, Object> result = new LinkedHashMap<>();
      List<Map<String, Object>> proposals = new ArrayList<>();
      for (int i = 0; i < 15; i++) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("testDefinitionFqn", "columnValuesToBeNotNull");
        p.put("columnName", "col" + i);
        proposals.add(p);
      }
      Map<String, Object> resultData = new LinkedHashMap<>();
      resultData.put("proposals", proposals);
      resultData.put("proposalCount", 15);
      result.put("results", List.of(resultData));

      try (var jsonMock = mockStatic(JsonUtils.class)) {
        String bigJson = "x".repeat(9000);
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(bigJson);

        Map<String, Object> capped = SuggestTestCasesTool.enforceByteCap(result);

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) capped.get("warnings");
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.get(0)).contains("truncated:proposals");
      }
    }
  }
}
