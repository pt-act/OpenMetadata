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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openmetadata.schema.entity.data.Glossary;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.service.jdbi3.GlossaryRepository;
import org.openmetadata.service.resources.glossary.GlossaryMapper;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

/**
 * Unit tests for GlossaryTool.
 *
 * <p>All tests use {@link McpEntityBridge.CreateOperationAuthorizer}, {@link
 * McpEntityBridge.RepositoryProvider}, and {@link McpEntityBridge.ChangeEventPublisher} functional
 * interfaces instead of {@code mockStatic(Entity.class)}, eliminating the need to mock Entity
 * static initializers. The {@code CreateResourceContext} constructor (which calls {@code
 * Entity.getEntityRepository()} internally) and {@code Entity.getCollectionDAO()} are never
 * invoked because the injected no-op authorizer and publisher bypass them.
 */
@ExtendWith(MockitoExtension.class)
class GlossaryToolTest {

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
    GlossaryRepository repo = mock(GlossaryRepository.class);
    Glossary glossary = new Glossary();
    glossary.setId(UUID.randomUUID());
    glossary.setName("TestGlossary");

    RestUtil.PutResponse<Glossary> putResponse =
        new RestUtil.PutResponse<>(Response.Status.CREATED, glossary, EventType.ENTITY_CREATED);

    when(repo.createOrUpdate(isNull(), any(Glossary.class), anyString(), any()))
        .thenReturn(putResponse);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.CreateOperationAuthorizer<Glossary> noOpAuthorizer = (entityType, entity) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> repo;
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    try (MockedConstruction<GlossaryMapper> mapperMock =
        mockConstruction(
            GlossaryMapper.class,
            (mapper, context) ->
                when(mapper.createToEntity(any(), anyString())).thenReturn(glossary))) {

      Map<String, Object> params = new HashMap<>();
      params.put("name", "TestGlossary");
      params.put("description", "A test glossary");
      params.put("mutuallyExclusive", false);

      GlossaryTool tool = new GlossaryTool();
      Map<String, Object> result =
          tool.execute(securityContext, params, noOpAuthorizer, repoProvider, noOpPublisher);

      assertNotNull(result);
      verify(repo).prepareInternal(any(Glossary.class), eq(false));

      // Envelope structure assertions
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isNotEmpty();

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("TestGlossary");

      // Backward-compat fields
      assertThat(result).containsEntry("glossaryName", "TestGlossary");
    }
  }

  @Nested
  class BuildGlossaryResponseTests {

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestGlossary");

      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(entityData, "TestGlossary");

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
    }

    @Test
    void resultsContainsEntityData() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestGlossary");
      entityData.put("description", "A test glossary");

      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(entityData, "TestGlossary");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).isSameAs(entityData);
    }

    @Test
    void resultsIsEmptyListWhenEntityDataIsNull() {
      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(null, "TestGlossary");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void narrativeDescribesTheCreation() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "BusinessTerms");

      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(entityData, "BusinessTerms");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("BusinessTerms");
      assertThat(narrative).startsWith("Created glossary");
    }

    @Test
    void backwardCompatGlossaryName() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "MyGlossary");

      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(entityData, "MyGlossary");

      assertThat(result).containsEntry("glossaryName", "MyGlossary");
    }

    @Test
    void noPaginationBlock() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestGlossary");

      Map<String, Object> result = GlossaryTool.buildGlossaryResponse(entityData, "TestGlossary");

      assertThat(result).doesNotContainKey("pagination");
    }
  }
}
