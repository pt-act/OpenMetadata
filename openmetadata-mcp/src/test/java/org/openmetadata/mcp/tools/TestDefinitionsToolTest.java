package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
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
import org.openmetadata.schema.tests.TestDefinition;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.jdbi3.TestDefinitionRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Unit tests for TestDefinitionsTool envelope response.
 *
 * <p>Tests verify:
 * - Envelope structure (results, narrative) in list response
 * - Cursor-based pagination: hasMore derived from paging.after, no offset-based pagination block
 * - Backward-compat fields (entityType, testPlatform, after)
 * - Default parameter values when not specified
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestDefinitionsToolTest {

  private TestDefinitionsTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private TestDefinitionRepository repository;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;
  private McpEntityBridge.RepositoryProvider repoProvider;

  @BeforeEach
  void setUp() {
    tool = new TestDefinitionsTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    repository = mock(TestDefinitionRepository.class);

    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    noopAuthorizer = (entityType, op) -> {};
    repoProvider = entityType -> repository;
  }

  /** Build a ResultList with cursor-based pagination. */
  private static ResultList<TestDefinition> buildResultList(
      List<TestDefinition> data, String beforeCursor, String afterCursor, int total) {
    return new ResultList<>(data, beforeCursor, afterCursor, total);
  }

  private static TestDefinition makeTestDef(String name) {
    TestDefinition td = new TestDefinition();
    td.setName(name);
    return td;
  }

  @Test
  void execute_success_hasEnvelopeStructure() {
    ResultList<TestDefinition> resultList =
        buildResultList(
            List.of(makeTestDef("test_def_1"), makeTestDef("test_def_2")), null, null, 2);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    // Envelope fields
    assertThat(result).containsKey("results");
    assertThat(result).containsKey("narrative");

    // No offset-based pagination block (cursor-based instead)
    assertThat(result).doesNotContainKey("pagination");

    // Backward-compat fields
    assertThat(result).containsEntry("entityType", "TABLE");
    assertThat(result).containsKey("testPlatform");
  }

  @Test
  void execute_withPagingAfter_hasMoreIsTrue() {
    ResultList<TestDefinition> resultList =
        buildResultList(List.of(makeTestDef("test_def_1")), null, "cursor_token_next_page", 100);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    // hasMore is true when paging.after cursor exists
    assertThat(result).containsEntry("hasMore", true);
    // pagingAfter is the Base64-encoded version of "cursor_token_next_page"
    assertThat(result).containsKey("pagingAfter");
  }

  @Test
  void execute_noPagingAfter_hasMoreIsAbsent() {
    ResultList<TestDefinition> resultList =
        buildResultList(List.of(makeTestDef("test_def_1")), null, null, 1);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    // No hasMore when no paging.after cursor
    assertThat(result).doesNotContainKey("hasMore");
    assertThat(result).doesNotContainKey("pagingAfter");
  }

  @Test
  void execute_withPagingBeforeAndAfter_bothPreservedInResult() {
    ResultList<TestDefinition> resultList =
        buildResultList(
            List.of(makeTestDef("test_def_1")), "cursor_prev_page", "cursor_next_page", 100);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    // Both cursors are Base64-encoded in the Paging object
    assertThat(result).containsKey("pagingBefore");
    assertThat(result).containsKey("pagingAfter");
    assertThat(result).containsEntry("hasMore", true);
  }

  @Test
  void execute_withAfterParam_preservedInResult() {
    ResultList<TestDefinition> resultList =
        buildResultList(List.of(makeTestDef("test_def_1")), null, null, 1);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq("some_cursor")))
        .thenReturn(resultList);

    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "TABLE");
    params.put("after", "some_cursor");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    assertThat(result).containsEntry("after", "some_cursor");
  }

  @Test
  void execute_emptyData_resultsIsEmptyList() {
    ResultList<TestDefinition> resultList = buildResultList(List.of(), null, null, 0);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    @SuppressWarnings("unchecked")
    List<Object> results = (List<Object>) result.get("results");
    assertThat(results).isEmpty();
  }

  @Test
  void execute_defaultEntityType_isTable() {
    ResultList<TestDefinition> resultList = buildResultList(List.of(), null, null, 0);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    // No entityType specified — should default to "TABLE"
    Map<String, Object> params = Map.of();

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    assertThat(result).containsEntry("entityType", "TABLE");
  }

  @Test
  void execute_onlyPagingBefore_noHasMore() {
    // When only paging.before exists (last page of backward scrolling), hasMore should be absent
    ResultList<TestDefinition> resultList =
        buildResultList(List.of(makeTestDef("test_def_1")), "cursor_prev_page", null, 100);

    when(repository.listAfter(eq(null), any(), any(ListFilter.class), eq(10), eq(null)))
        .thenReturn(resultList);

    Map<String, Object> params = Map.of("entityType", "TABLE");

    Map<String, Object> result = tool.execute(params, noopAuthorizer, repoProvider);

    assertThat(result).containsKey("pagingBefore");
    assertThat(result).doesNotContainKey("hasMore");
    assertThat(result).doesNotContainKey("pagingAfter");
  }

  @Nested
  class BuildTestDefinitionsResponseTests {

    /** Build a listResult map as returned by JsonUtils.getMap(repository.listAfter(...)). */
    private static Map<String, Object> buildListResult(
        List<Object> data, String pagingBefore, String pagingAfter, Integer total) {
      Map<String, Object> result = new HashMap<>();
      result.put("data", data);
      Map<String, Object> paging = new HashMap<>();
      if (pagingBefore != null) {
        paging.put("before", pagingBefore);
      }
      if (pagingAfter != null) {
        paging.put("after", pagingAfter);
      }
      if (total != null) {
        paging.put("total", total);
      }
      if (!paging.isEmpty()) {
        result.put("paging", paging);
      }
      return result;
    }

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> listResult =
          buildListResult(List.of(Map.of("name", "td1")), null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
    }

    @Test
    void resultsContainsData() {
      List<Object> data = List.of(Map.of("name", "td1"), Map.of("name", "td2"));
      Map<String, Object> listResult = buildListResult(data, null, null, 2);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(2);
    }

    @Test
    void resultsIsEmptyListWhenNoData() {
      Map<String, Object> listResult = buildListResult(List.of(), null, null, 0);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void narrativeDescribesTheListing() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("1 test definition");
      assertThat(narrative).contains("TABLE");
      assertThat(narrative).startsWith("Listed");
    }

    @Test
    void backwardCompatFields() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", "some_cursor");

      assertThat(result).containsEntry("entityType", "TABLE");
      assertThat(result).containsEntry("testPlatform", "OPEN_METADATA");
      assertThat(result).containsEntry("after", "some_cursor");
    }

    @Test
    void noAfterParam_afterFieldAbsent() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).doesNotContainKey("after");
    }

    @Test
    void pagingAfterSetsHasMore() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, "next_page_cursor", 100);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).containsEntry("hasMore", true);
      assertThat(result).containsKey("pagingAfter");
    }

    @Test
    void noPagingAfter_hasMoreAbsent() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).doesNotContainKey("hasMore");
      assertThat(result).doesNotContainKey("pagingAfter");
    }

    @Test
    void onlyPagingBefore_noHasMore() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, "prev_page_cursor", null, 100);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).containsKey("pagingBefore");
      assertThat(result).doesNotContainKey("hasMore");
      assertThat(result).doesNotContainKey("pagingAfter");
    }

    @Test
    void noPaginationBlock() {
      List<Object> data = List.of(Map.of("name", "td1"));
      Map<String, Object> listResult = buildListResult(data, null, null, 1);

      Map<String, Object> result =
          TestDefinitionsTool.buildTestDefinitionsResponse(
              listResult, "TABLE", "OPEN_METADATA", null);

      assertThat(result).doesNotContainKey("pagination");
    }
  }
}
