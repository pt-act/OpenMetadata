package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
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
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.service.jdbi3.GlossaryTermRepository;
import org.openmetadata.service.resources.glossary.GlossaryTermMapper;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

/**
 * Unit tests for GlossaryTermTool.
 *
 * <p>All tests use {@link McpEntityBridge.CreateOperationAuthorizer}, {@link
 * McpEntityBridge.RepositoryProvider}, and {@link McpEntityBridge.ChangeEventPublisher} functional
 * interfaces instead of {@code mockStatic(Entity.class)}, eliminating the need to mock Entity
 * static initializers. The {@code CreateResourceContext} constructor (which calls {@code
 * Entity.getEntityRepository()} internally) and {@code Entity.getCollectionDAO()} are never
 * invoked because the injected no-op authorizer and publisher bypass them.
 *
 * <p>Note: {@code mockStatic(CommonUtils.class)} is still used for reviewer-related tests, but
 * {@code mockStatic(Entity.class)} is no longer needed.
 */
@ExtendWith(MockitoExtension.class)
class GlossaryTermToolTest {

  private CatalogSecurityContext securityContext;

  @BeforeEach
  void setUp() {
    securityContext = mock(CatalogSecurityContext.class);
    Principal mockPrincipal = mock(Principal.class);
    lenient().when(mockPrincipal.getName()).thenReturn("test-user");
    lenient().when(securityContext.getUserPrincipal()).thenReturn(mockPrincipal);
  }

  @Test
  void testExecuteCallsPrepareInternal() {
    GlossaryTermRepository repo = mock(GlossaryTermRepository.class);
    GlossaryTerm glossaryTerm = new GlossaryTerm();
    glossaryTerm.setId(UUID.randomUUID());
    glossaryTerm.setName("TestTerm");

    RestUtil.PutResponse<GlossaryTerm> putResponse =
        new RestUtil.PutResponse<>(Response.Status.CREATED, glossaryTerm, EventType.ENTITY_CREATED);

    when(repo.createOrUpdate(isNull(), any(GlossaryTerm.class), anyString(), any()))
        .thenReturn(putResponse);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.CreateOperationAuthorizer<GlossaryTerm> noOpAuthorizer =
        (entityType, entity) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> repo;
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    try (MockedConstruction<GlossaryTermMapper> mapperMock =
        mockConstruction(
            GlossaryTermMapper.class,
            (mapper, context) ->
                when(mapper.createToEntity(any(), anyString())).thenReturn(glossaryTerm))) {

      Map<String, Object> params = new HashMap<>();
      params.put("name", "TestTerm");
      params.put("glossary", "TestGlossary");
      params.put("description", "A test term");

      GlossaryTermTool tool = new GlossaryTermTool();
      Map<String, Object> result =
          tool.execute(securityContext, params, noOpAuthorizer, repoProvider, noOpPublisher);

      assertNotNull(result);
      verify(repo).prepareInternal(any(GlossaryTerm.class), eq(false));

      // Envelope structure assertions
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isNotEmpty();

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("TestTerm");
      assertThat(narrative).contains("TestGlossary");

      // Backward-compat fields
      assertThat(result).containsEntry("glossaryTermName", "TestTerm");
      assertThat(result).containsEntry("glossary", "TestGlossary");
    }
  }

  @Test
  void testExecuteWithReviewersCallsGetTeamsOrUsers() {
    GlossaryTermRepository repo = mock(GlossaryTermRepository.class);
    GlossaryTerm glossaryTerm = new GlossaryTerm();
    glossaryTerm.setId(UUID.randomUUID());
    glossaryTerm.setName("TestTerm");

    RestUtil.PutResponse<GlossaryTerm> putResponse =
        new RestUtil.PutResponse<>(Response.Status.CREATED, glossaryTerm, EventType.ENTITY_CREATED);

    when(repo.createOrUpdate(isNull(), any(GlossaryTerm.class), anyString(), any()))
        .thenReturn(putResponse);

    List<EntityReference> reviewerRefs = new ArrayList<>();
    reviewerRefs.add(new EntityReference().withId(UUID.randomUUID()).withType("user"));

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.CreateOperationAuthorizer<GlossaryTerm> noOpAuthorizer =
        (entityType, entity) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> repo;
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    // Only mockStatic(CommonUtils.class) is needed for reviewer tests — no Entity mock
    try (MockedStatic<CommonUtils> commonUtilsMock = mockStatic(CommonUtils.class);
        MockedConstruction<GlossaryTermMapper> mapperMock =
            mockConstruction(
                GlossaryTermMapper.class,
                (mapper, context) ->
                    when(mapper.createToEntity(any(), anyString())).thenReturn(glossaryTerm))) {

      commonUtilsMock.when(() -> CommonUtils.getTeamsOrUsers(any())).thenReturn(reviewerRefs);

      List<String> reviewers = new ArrayList<>();
      reviewers.add("reviewer-user");

      Map<String, Object> params = new HashMap<>();
      params.put("name", "TestTerm");
      params.put("glossary", "TestGlossary");
      params.put("description", "A test term");
      params.put("reviewers", reviewers);

      GlossaryTermTool tool = new GlossaryTermTool();
      Map<String, Object> result =
          tool.execute(securityContext, params, noOpAuthorizer, repoProvider, noOpPublisher);

      assertNotNull(result);
      verify(repo).prepareInternal(any(GlossaryTerm.class), eq(false));
      commonUtilsMock.verify(() -> CommonUtils.getTeamsOrUsers(any()));

      // Envelope structure assertions
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");

      @SuppressWarnings("unchecked")
      List<Object> reviewerResults = (List<Object>) result.get("results");
      assertThat(reviewerResults).isNotEmpty();

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("TestTerm");

      // Backward-compat fields
      assertThat(result).containsEntry("glossaryTermName", "TestTerm");
      assertThat(result).containsEntry("glossary", "TestGlossary");
    }
  }

  @Nested
  class BuildGlossaryTermResponseTests {

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestTerm");

      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(entityData, "TestTerm", "TestGlossary");

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
    }

    @Test
    void resultsContainsEntityData() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestTerm");
      entityData.put("description", "A test term");

      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(entityData, "TestTerm", "TestGlossary");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).isSameAs(entityData);
    }

    @Test
    void resultsIsEmptyListWhenEntityDataIsNull() {
      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(null, "TestTerm", "TestGlossary");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void narrativeDescribesTheCreation() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "Revenue");

      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(entityData, "Revenue", "FinanceTerms");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("Revenue");
      assertThat(narrative).contains("FinanceTerms");
      assertThat(narrative).startsWith("Created glossary term");
    }

    @Test
    void backwardCompatFields() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "MyTerm");

      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(entityData, "MyTerm", "MyGlossary");

      assertThat(result).containsEntry("glossaryTermName", "MyTerm");
      assertThat(result).containsEntry("glossary", "MyGlossary");
    }

    @Test
    void noPaginationBlock() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestTerm");

      Map<String, Object> result =
          GlossaryTermTool.buildGlossaryTermResponse(entityData, "TestTerm", "TestGlossary");

      assertThat(result).doesNotContainKey("pagination");
    }
  }
}
