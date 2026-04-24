package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.ChangeEventRepository;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.search.SearchUtil;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Integration tests for the Stewardship Copilot tools: {@link FindUnownedAssetsTool}, {@link
 * SuggestOwnerForTool}, and {@link DraftOwnershipPatchTool}.
 *
 * <p>Tests the full execute() flow with injected functional interfaces via {@link McpEntityBridge},
 * eliminating the need for {@code mockStatic(Entity.class)}.
 *
 * <p>Note: {@link FindUnownedAssetsTool} still requires {@code mockStatic} for {@link
 * DefaultAuthorizer}, {@link SearchUtil}, and {@link SearchMetadataTool} because those static
 * methods are called directly inside the tool's search logic (not via Entity).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StewardshipCopilotIntegrationTest {

  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;

  @BeforeEach
  void setUp() {
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
  }

  // ====================== Helper methods ======================

  private EntityReference buildEntityRef(String fqn, UUID id, String type) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    when(ref.getType()).thenReturn(type);
    return ref;
  }

  private Map<String, Object> buildEntityWithOwners(String name, String ownerName) {
    Map<String, Object> ownerEntry = new LinkedHashMap<>();
    ownerEntry.put("name", ownerName);
    ownerEntry.put("type", "user");
    ownerEntry.put("id", UUID.randomUUID().toString());
    Map<String, Object> entityMap = new LinkedHashMap<>();
    entityMap.put("owners", List.of(ownerEntry));
    entityMap.put("name", name);
    return entityMap;
  }

  private ChangeEvent buildChangeEvent(UUID entityId, String userName, long timestamp) {
    ChangeEvent ce = new ChangeEvent();
    ce.setEntityId(entityId);
    ce.setEntityType("table");
    ce.setUserName(userName);
    ce.setEventType(EventType.ENTITY_UPDATED);
    ce.setTimestamp(timestamp);
    return ce;
  }

  private SearchRepository createSearchRepoWithEmptyResults() {
    SearchRepository searchRepo = mock(SearchRepository.class);
    when(searchRepo.getIndexOrAliasName(anyString())).thenReturn("search_index");
    return searchRepo;
  }

  private SubjectContext createSubjectContext() {
    return mock(SubjectContext.class);
  }

  /** Pre-computed empty search result JsonNode — avoids calling JsonUtils.readTree inside mock setup. */
  private static final com.fasterxml.jackson.databind.JsonNode EMPTY_SEARCH_NODE;

  static {
    try {
      EMPTY_SEARCH_NODE = JsonUtils.readTree("{\"hits\":{\"hits\":[]}}");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Sets up static mocks needed for FindUnownedAssetsTool tests (excluding Entity, which is
   * handled via injected functional interfaces).
   */
  private void setupFindUnownedMocks(
      MockedStatic<JsonUtils> jsonMock,
      MockedStatic<SearchMetadataTool> searchToolMock,
      MockedStatic<DefaultAuthorizer> authorizerMock,
      MockedStatic<SearchUtil> searchUtilMock,
      SearchRepository searchRepo,
      SubjectContext subjectContext) {

    authorizerMock
        .when(() -> DefaultAuthorizer.getSubjectContext(securityContext))
        .thenReturn(subjectContext);

    searchUtilMock
        .when(() -> SearchUtil.mapEntityTypesToIndexNames(anyString()))
        .thenReturn("table_index");

    jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(EMPTY_SEARCH_NODE);
    jsonMock
        .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
        .thenReturn(Map.of("results", List.of()));

    searchToolMock
        .when(
            () ->
                SearchMetadataTool.buildEnhancedSearchResponse(
                    any(), anyString(), anyInt(), anyInt(), any(), anyBoolean(), anyInt()))
        .thenReturn(Map.of("results", List.of()));
  }

  // ====================== FindUnownedAssetsTool Tests ======================

  @Nested
  class FindUnownedAssets {

    private FindUnownedAssetsTool tool;

    @BeforeEach
    void setUp() {
      tool = new FindUnownedAssetsTool();
    }

    @Test
    void execute_emptyResults_returnsEmptyList() throws Exception {
      SearchRepository searchRepo = createSearchRepoWithEmptyResults();
      SubjectContext subjectContext = mock(SubjectContext.class);
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");

      // Inject functional interfaces — no mockStatic(Entity.class) needed for Entity
      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchUtil> searchUtilMock = mockStatic(SearchUtil.class)) {

        setupFindUnownedMocks(
            jsonMock, searchToolMock, authorizerMock, searchUtilMock, searchRepo, subjectContext);

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noopAuthorizer, () -> searchRepo, lineageProvider);

        assertThat(result).containsKey("results");
        assertThat(result).containsKey("narrative");
        String narrative = (String) result.get("narrative");
        assertThat(narrative).contains("Unowned Assets Report");
      }
    }

    @Test
    void execute_withScopeString_treatsAsDomain() throws Exception {
      SearchRepository searchRepo = createSearchRepoWithEmptyResults();
      SubjectContext subjectContext = mock(SubjectContext.class);
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("scope", "Marketing");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchUtil> searchUtilMock = mockStatic(SearchUtil.class)) {

        setupFindUnownedMocks(
            jsonMock, searchToolMock, authorizerMock, searchUtilMock, searchRepo, subjectContext);

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noopAuthorizer, () -> searchRepo, lineageProvider);

        assertThat(result).containsKey("scope");
        @SuppressWarnings("unchecked")
        Map<String, String> scope = (Map<String, String>) result.get("scope");
        assertThat(scope).containsEntry("type", "domain");
        assertThat(scope).containsEntry("value", "Marketing");
      }
    }

    @Test
    void execute_withScopeObject_usesExplicitType() throws Exception {
      SearchRepository searchRepo = createSearchRepoWithEmptyResults();
      SubjectContext subjectContext = mock(SubjectContext.class);
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;

      Map<String, Object> scopeParam = new HashMap<>();
      scopeParam.put("type", "service");
      scopeParam.put("value", "BigQuery");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "dashboard");
      params.put("scope", scopeParam);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchUtil> searchUtilMock = mockStatic(SearchUtil.class)) {

        setupFindUnownedMocks(
            jsonMock, searchToolMock, authorizerMock, searchUtilMock, searchRepo, subjectContext);

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noopAuthorizer, () -> searchRepo, lineageProvider);

        assertThat(result).containsEntry("entityType", "dashboard");
      }
    }

    @Test
    void countDownstreamNodes_excludesSourceFqn() {
      Set<Map<String, Object>> nodesSet = new HashSet<>();
      Map<String, Object> n1 = new LinkedHashMap<>();
      n1.put("fullyQualifiedName", "db.schema.orders");
      Map<String, Object> n2 = new LinkedHashMap<>();
      n2.put("fullyQualifiedName", "db.schema.customers");
      nodesSet.add(n1);
      nodesSet.add(n2);

      Map<String, Object> lineageWithSet = new HashMap<>();
      lineageWithSet.put("nodes", nodesSet);

      int count = FindUnownedAssetsTool.countDownstreamNodes(lineageWithSet, "db.schema.orders");
      assertThat(count).isEqualTo(1); // Only customers, not the source
    }
  }

  // ====================== SuggestOwnerForTool Tests ======================

  @Nested
  class SuggestOwnerFor {

    private SuggestOwnerForTool tool;

    @BeforeEach
    void setUp() {
      tool = new SuggestOwnerForTool();
    }

    @Test
    void execute_withRecentPatchers_ranksPatcherHighest() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");

      ChangeEvent ce1 = buildChangeEvent(entityId, "alice", System.currentTimeMillis() - 1000);
      ChangeEvent ce2 = buildChangeEvent(entityId, "alice", System.currentTimeMillis() - 2000);
      ChangeEvent ce3 = buildChangeEvent(entityId, "alice", System.currentTimeMillis() - 3000);
      ChangeEvent ce4 = buildChangeEvent(entityId, "bob", System.currentTimeMillis() - 4000);

      ChangeEventRepository changeEventRepo = mock(ChangeEventRepository.class);
      when(changeEventRepo.list(anyLong(), any(), any(), any(), any()))
          .thenReturn(List.of(ce1, ce2, ce3, ce4));

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> entityRef;
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider = () -> changeEventRepo;
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher =
          (ref, fields, include) -> null; // no domain owners
      McpEntityBridge.EntityFetcher entityFetcher =
          (entityType, fqn, fields, include) -> null; // no schema siblings

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");
        jsonMock.when(() -> JsonUtils.readValue(anyString(), eq(Map.class))).thenReturn(Map.of());
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(Map.of("nodes", Set.of(), "edges", Set.of()));

        Map<String, Object> result =
            tool.execute(
                params,
                referenceResolver,
                noopAuthorizer,
                changeEventRepoProvider,
                lineageProvider,
                entityByRefFetcher,
                entityFetcher);

        assertThat(result).containsEntry("fqn", "db.schema.orders");
        assertThat(result).containsKey("candidates");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).isNotEmpty();

        // Alice should be ranked higher (3 patches × 3.0 weight = 9.0 vs 1 × 3.0 = 3.0)
        Map<String, Object> topCandidate = candidates.get(0);
        assertThat(topCandidate).containsEntry("name", "alice");
        double aliceScore = ((Number) topCandidate.get("score")).doubleValue();
        assertThat(aliceScore).isGreaterThan(3.0);
      }
    }

    @Test
    void execute_noCandidates_returnsEmptyList() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");

      ChangeEventRepository changeEventRepo = mock(ChangeEventRepository.class);
      when(changeEventRepo.list(anyLong(), any(), any(), any(), any())).thenReturn(List.of());

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> entityRef;
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider = () -> changeEventRepo;
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher = (ref, fields, include) -> null;
      McpEntityBridge.EntityFetcher entityFetcher = (entityType, fqn, fields, include) -> null;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");
        jsonMock.when(() -> JsonUtils.readValue(anyString(), eq(Map.class))).thenReturn(Map.of());
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(Map.of("nodes", Set.of(), "edges", Set.of()));

        Map<String, Object> result =
            tool.execute(
                params,
                referenceResolver,
                noopAuthorizer,
                changeEventRepoProvider,
                lineageProvider,
                entityByRefFetcher,
                entityFetcher);

        assertThat(result).containsEntry("fqn", "db.schema.orders");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).isEmpty();
      }
    }

    @Test
    void execute_nullParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractSchemaFqn_validTableFqn_returnsSchemaFqn() {
      String result = SuggestOwnerForTool.extractSchemaFqn("postgres.mydb.myschema.orders");
      assertThat(result).isEqualTo("postgres.mydb.myschema");
    }

    @Test
    void extractSchemaFqn_shortFqn_returnsNull() {
      assertThat(SuggestOwnerForTool.extractSchemaFqn("orders")).isNull();
      assertThat(SuggestOwnerForTool.extractSchemaFqn("db.orders")).isNull();
      assertThat(SuggestOwnerForTool.extractSchemaFqn(null)).isNull();
    }
  }

  // ====================== DraftOwnershipPatchTool Tests ======================

  @Nested
  class DraftOwnershipPatch {

    private DraftOwnershipPatchTool tool;

    @BeforeEach
    void setUp() {
      tool = new DraftOwnershipPatchTool();
    }

    @Test
    void execute_addMode_appendsOwner() throws Exception {
      UUID entityId = UUID.randomUUID();
      UUID ownerId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");
      EntityReference ownerRef = mock(EntityReference.class);
      when(ownerRef.getId()).thenReturn(ownerId);

      // Inject functional interface — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> {
            if ("table".equals(entityType)) return entityRef;
            if ("user".equals(entityType) && "jane.doe".equals(fqn)) return ownerRef;
            return null;
          };

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("owner", "jane.doe");
      params.put("mode", "add");

      Map<String, Object> result = tool.execute(params, referenceResolver, noopAuthorizer);

      assertThat(result).containsEntry("fqn", "db.schema.orders");
      assertThat(result).containsEntry("mode", "add");
      assertThat(result).containsEntry("applied", false);

      // Patch should use /owners/- (append)
      String patch = (String) ((Map<?, ?>) ((List<?>) result.get("results")).get(0)).get("patch");
      assertThat(patch).contains("/owners/-");
      assertThat(patch).contains("jane.doe");

      // Instruction should mention patch_entity
      String instruction =
          (String) ((Map<?, ?>) ((List<?>) result.get("results")).get(0)).get("instruction");
      assertThat(instruction).contains("patch_entity");
    }

    @Test
    void execute_replaceMode_overwritesOwners() throws Exception {
      UUID entityId = UUID.randomUUID();
      UUID ownerId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");
      EntityReference ownerRef = mock(EntityReference.class);
      when(ownerRef.getId()).thenReturn(ownerId);

      // Inject functional interface — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> {
            if ("table".equals(entityType)) return entityRef;
            if ("team".equals(entityType) && "DataPlatform".equals(fqn)) return ownerRef;
            return null;
          };

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("owner", Map.of("name", "DataPlatform", "type", "team"));
      params.put("mode", "replace");

      Map<String, Object> result = tool.execute(params, referenceResolver, noopAuthorizer);

      assertThat(result).containsEntry("mode", "replace");
      String patch = (String) ((Map<?, ?>) ((List<?>) result.get("results")).get(0)).get("patch");
      assertThat(patch).contains("/owners");
      assertThat(patch).doesNotContain("/owners/-");
      assertThat(patch).contains("DataPlatform");
      assertThat(patch).contains("team");
    }

    @Test
    void execute_unresolvedOwner_includesWarning() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");

      // Inject functional interface — owner cannot be resolved
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> {
            if ("table".equals(entityType)) return entityRef;
            // "user" + "nonexistent.user" → null (unresolved)
            return null;
          };

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("owner", "nonexistent.user");
      params.put("mode", "add");

      Map<String, Object> result = tool.execute(params, referenceResolver, noopAuthorizer);

      // Should have a warning at the envelope level
      assertThat(result).containsKey("warnings");
      @SuppressWarnings("unchecked")
      List<String> warnings = (List<String>) result.get("warnings");
      assertThat(warnings).anyMatch(w -> w.contains("ownerUnresolved"));
    }

    @Test
    void execute_missingOwner_throwsIllegalArgumentException() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");

      // Inject functional interface — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> entityRef;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      // No "owner" parameter

      assertThatThrownBy(() -> tool.execute(params, referenceResolver, noopAuthorizer))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("owner");
    }

    @Test
    void execute_invalidMode_throwsIllegalArgumentException() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");

      // Inject functional interface — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> entityRef;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("owner", "jane.doe");
      params.put("mode", "delete");

      assertThatThrownBy(() -> tool.execute(params, referenceResolver, noopAuthorizer))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("mode");
    }

    @Test
    void execute_nullParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseOwnerSpec_stringInput_defaultsToUserType() {
      DraftOwnershipPatchTool.OwnerSpec spec = DraftOwnershipPatchTool.parseOwnerSpec("jane.doe");
      assertThat(spec.name).isEqualTo("jane.doe");
      assertThat(spec.type).isEqualTo("user");
      assertThat(spec.id).isNull();
    }

    @Test
    void parseOwnerSpec_mapInput_usesExplicitType() {
      Map<String, Object> ownerMap = new LinkedHashMap<>();
      ownerMap.put("name", "DataPlatform");
      ownerMap.put("type", "team");
      ownerMap.put("id", "abc-123");

      DraftOwnershipPatchTool.OwnerSpec spec = DraftOwnershipPatchTool.parseOwnerSpec(ownerMap);
      assertThat(spec.name).isEqualTo("DataPlatform");
      assertThat(spec.type).isEqualTo("team");
      assertThat(spec.id).isEqualTo("abc-123");
    }

    @Test
    void parseOwnerSpec_mapWithoutName_throwsIllegalArgumentException() {
      Map<String, Object> ownerMap = new LinkedHashMap<>();
      ownerMap.put("type", "user");
      // No "name"

      assertThatThrownBy(() -> DraftOwnershipPatchTool.parseOwnerSpec(ownerMap))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name");
    }

    @Test
    void buildOwnershipPatch_addMode_appendsToArray() {
      DraftOwnershipPatchTool.OwnerEntityRef ownerRef =
          new DraftOwnershipPatchTool.OwnerEntityRef("jane.doe", "user", "uuid-1");
      String patch = DraftOwnershipPatchTool.buildOwnershipPatch(ownerRef, "add");
      assertThat(patch).contains("\"op\":\"add\"");
      assertThat(patch).contains("\"path\":\"/owners/-\"");
      assertThat(patch).contains("\"name\":\"jane.doe\"");
      assertThat(patch).contains("\"type\":\"user\"");
    }

    @Test
    void buildOwnershipPatch_replaceMode_overwritesArray() {
      DraftOwnershipPatchTool.OwnerEntityRef ownerRef =
          new DraftOwnershipPatchTool.OwnerEntityRef("DataPlatform", "team", "uuid-2");
      String patch = DraftOwnershipPatchTool.buildOwnershipPatch(ownerRef, "replace");
      assertThat(patch).contains("\"op\":\"add\"");
      assertThat(patch).contains("\"path\":\"/owners\"");
      assertThat(patch).doesNotContain("/owners/-");
      assertThat(patch).contains("\"name\":\"DataPlatform\"");
      assertThat(patch).contains("\"type\":\"team\"");
    }

    @Test
    void buildOwnershipPatch_nullId_omitsIdKey() {
      DraftOwnershipPatchTool.OwnerEntityRef ownerRef =
          new DraftOwnershipPatchTool.OwnerEntityRef("alice", "user", null);
      String patch = DraftOwnershipPatchTool.buildOwnershipPatch(ownerRef, "add");
      assertThat(patch).doesNotContain("\"id\"");
      assertThat(patch).contains("\"name\":\"alice\"");
      assertThat(patch).contains("\"type\":\"user\"");
    }

    @Test
    void generateNarrative_addMode_noReplaceWarning() {
      DraftOwnershipPatchTool.OwnerSpec spec =
          new DraftOwnershipPatchTool.OwnerSpec("jane.doe", "user", null);
      String narrative = DraftOwnershipPatchTool.generateNarrative("db.schema.orders", spec, "add");
      assertThat(narrative).contains("Draft Ownership Patch");
      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("jane.doe");
      assertThat(narrative).contains("NOT been applied");
      assertThat(narrative).doesNotContain("Replace mode");
    }

    @Test
    void generateNarrative_replaceMode_includesWarning() {
      DraftOwnershipPatchTool.OwnerSpec spec =
          new DraftOwnershipPatchTool.OwnerSpec("DataPlatform", "team", null);
      String narrative =
          DraftOwnershipPatchTool.generateNarrative("db.schema.orders", spec, "replace");
      assertThat(narrative).contains("NOT been applied");
      assertThat(narrative).contains("Replace mode will remove");
    }
  }

  // ====================== Cross-tool workflow ======================

  @Nested
  class CrossToolWorkflow {

    @Test
    void suggestOwnerFor_usesWeights_fromMultipleSources() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef =
          buildEntityRef("postgres.mydb.myschema.orders", entityId, "table");

      // 1. Recent patcher: alice (2 patches)
      ChangeEvent ce1 = buildChangeEvent(entityId, "alice", System.currentTimeMillis() - 1000);
      ChangeEvent ce2 = buildChangeEvent(entityId, "alice", System.currentTimeMillis() - 2000);

      ChangeEventRepository changeEventRepo = mock(ChangeEventRepository.class);
      when(changeEventRepo.list(anyLong(), any(), any(), any(), any()))
          .thenReturn(List.of(ce1, ce2));

      // 2. Upstream owner: bob — but lineage repo is null, so only patchers count
      Map<String, Object> upstreamWithOwners = buildEntityWithOwners("raw_orders", "bob");

      SuggestOwnerForTool suggestTool = new SuggestOwnerForTool();

      // Inject functional interfaces — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> entityRef;
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider = () -> changeEventRepo;
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher =
          (ref, fields, include) -> null; // no domain
      McpEntityBridge.EntityFetcher entityFetcher =
          (entityType, fqn, fields, include) -> null; // no schema siblings

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "postgres.mydb.myschema.orders");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
        jsonMock
            .when(() -> JsonUtils.pojoToJson(any()))
            .thenReturn("{\"owners\":[{\"name\":\"bob\",\"type\":\"user\",\"id\":\"u2\"}]}");
        jsonMock
            .when(() -> JsonUtils.readValue(anyString(), eq(Map.class)))
            .thenReturn(upstreamWithOwners);
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(Map.of("nodes", Set.of(), "edges", Set.of()));

        Map<String, Object> result =
            suggestTool.execute(
                params,
                referenceResolver,
                noopAuthorizer,
                changeEventRepoProvider,
                lineageProvider,
                entityByRefFetcher,
                entityFetcher);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).isNotEmpty();

        // Alice should rank higher: 2 patches × 3.0 = 6.0
        Map<String, Object> topCandidate = candidates.get(0);
        assertThat(topCandidate).containsEntry("name", "alice");
        double aliceScore = ((Number) topCandidate.get("score")).doubleValue();
        assertThat(aliceScore).isGreaterThanOrEqualTo(6.0);
      }
    }

    @Test
    void draftPatch_afterSuggest_appliesToSameEntity() throws Exception {
      UUID entityId = UUID.randomUUID();
      UUID ownerId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId, "table");
      EntityReference ownerRef = mock(EntityReference.class);
      when(ownerRef.getId()).thenReturn(ownerId);

      DraftOwnershipPatchTool draftTool = new DraftOwnershipPatchTool();

      // Inject functional interface — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver referenceResolver =
          (entityType, fqn, include) -> {
            if ("table".equals(entityType)) return entityRef;
            if ("user".equals(entityType) && "alice".equals(fqn)) return ownerRef;
            return null;
          };

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("owner", "alice");
      params.put("mode", "add");

      Map<String, Object> result = draftTool.execute(params, referenceResolver, noopAuthorizer);

      // Verify patch is well-formed JSON
      String patch = (String) ((Map<?, ?>) ((List<?>) result.get("results")).get(0)).get("patch");
      assertThat(patch).startsWith("[");
      assertThat(patch).endsWith("]");
      assertThat(patch).contains("alice");

      // Verify not applied
      assertThat(result).containsEntry("applied", false);

      // Verify instruction references the correct entity
      String instruction =
          (String) ((Map<?, ?>) ((List<?>) result.get("results")).get(0)).get("instruction");
      assertThat(instruction).contains("db.schema.orders");
      assertThat(instruction).contains("patch_entity");
    }
  }

  // ====================== Parameter validation ======================

  @Nested
  class ParameterValidation {

    @Test
    void suggestOwner_missingFqn_throwsIllegalArgumentException() {
      SuggestOwnerForTool suggestTool = new SuggestOwnerForTool();
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      // No fqn

      // The tool extracts fqn from params; when missing, ToolUtils.resolveEntityRef throws
      assertThatThrownBy(
              () ->
                  suggestTool.execute(
                      params,
                      (entityType, fqn, include) -> null,
                      noopAuthorizer,
                      () -> null,
                      () -> null,
                      (ref, fields, include) -> null,
                      (et, fqn2, fields, include) -> null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftPatch_missingEntity_throwsIllegalArgumentException() {
      DraftOwnershipPatchTool draftTool = new DraftOwnershipPatchTool();
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("owner", "jane.doe");
      // No fqn

      assertThatThrownBy(
              () -> draftTool.execute(params, (entityType, fqn, include) -> null, noopAuthorizer))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findUnowned_limitCappedAtMax() throws Exception {
      FindUnownedAssetsTool findTool = new FindUnownedAssetsTool();
      SearchRepository searchRepo = createSearchRepoWithEmptyResults();
      SubjectContext subjectContext = mock(SubjectContext.class);
      McpEntityBridge.LineageRepositoryProvider lineageProvider = () -> null;

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("limit", 9999); // way over MAX_LIMIT (200)

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
          MockedStatic<SearchMetadataTool> searchToolMock = mockStatic(SearchMetadataTool.class);
          MockedStatic<DefaultAuthorizer> authorizerMock = mockStatic(DefaultAuthorizer.class);
          MockedStatic<SearchUtil> searchUtilMock = mockStatic(SearchUtil.class)) {

        setupFindUnownedMocks(
            jsonMock, searchToolMock, authorizerMock, searchUtilMock, searchRepo, subjectContext);

        Map<String, Object> result =
            findTool.execute(
                params, securityContext, noopAuthorizer, () -> searchRepo, lineageProvider);

        // Should succeed without error — limit is clamped internally
        assertThat(result).containsKey("results");
      }
    }
  }

  // ====================== Narrative generation ======================

  @Nested
  class NarrativeGeneration {

    @Test
    void findUnowned_emptyResults_allOwnedMessage() {
      String narrative = FindUnownedAssetsTool.generateNarrative("table", null, null, 0, List.of());
      assertThat(narrative).contains("All assets in this scope have owners");
    }

    @Test
    void findUnowned_withResults_rankedByDownstream() {
      Map<String, Object> asset1 = new LinkedHashMap<>();
      asset1.put("fullyQualifiedName", "db.schema.high_impact");
      asset1.put("downstreamCount", 10);

      Map<String, Object> asset2 = new LinkedHashMap<>();
      asset2.put("fullyQualifiedName", "db.schema.low_impact");
      asset2.put("downstreamCount", 2);

      String narrative =
          FindUnownedAssetsTool.generateNarrative(
              "table", "domain", "Marketing", 2, List.of(asset1, asset2));
      assertThat(narrative).contains("Marketing");
      assertThat(narrative).contains("high_impact");
      assertThat(narrative).contains("10 downstream");
      assertThat(narrative).contains("suggest_owner_for");
    }

    @Test
    void suggestOwner_noCandidates_suggestsDomainDefault() {
      String narrative = SuggestOwnerForTool.generateNarrative("db.schema.orders", List.of());
      assertThat(narrative).contains("No owner candidates found");
      assertThat(narrative).contains("domain default");
    }

    @Test
    void suggestOwner_withCandidates_showsScores() {
      SuggestOwnerForTool.OwnerCandidate c1 =
          new SuggestOwnerForTool.OwnerCandidate(
              "alice", "user", null, List.of("mostFrequentPatcher"), 6.0);
      SuggestOwnerForTool.OwnerCandidate c2 =
          new SuggestOwnerForTool.OwnerCandidate(
              "bob", "user", null, List.of("upstreamOwner"), 2.0);

      String narrative = SuggestOwnerForTool.generateNarrative("db.schema.orders", List.of(c1, c2));
      assertThat(narrative).contains("alice");
      assertThat(narrative).contains("bob");
      assertThat(narrative).contains("6.0");
      assertThat(narrative).contains("2.0");
      assertThat(narrative).contains("draft_ownership_patch");
    }
  }

  // ====================== Limits enforcement ======================

  @Nested
  class LimitsEnforcement {

    @Test
    void findUnowned_throwsUnsupportedOperation_forLimitsOverload() {
      FindUnownedAssetsTool findTool = new FindUnownedAssetsTool();
      assertThatThrownBy(() -> findTool.execute(authorizer, null, securityContext, Map.of()))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void suggestOwner_throwsUnsupportedOperation_forLimitsOverload() {
      SuggestOwnerForTool suggestTool = new SuggestOwnerForTool();
      assertThatThrownBy(
              () -> suggestTool.execute(authorizer, null, securityContext, Map.of("fqn", "x")))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void draftPatch_throwsUnsupportedOperation_forLimitsOverload() {
      DraftOwnershipPatchTool draftTool = new DraftOwnershipPatchTool();
      assertThatThrownBy(
              () ->
                  draftTool.execute(
                      authorizer, null, securityContext, Map.of("fqn", "x", "owner", "y")))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
