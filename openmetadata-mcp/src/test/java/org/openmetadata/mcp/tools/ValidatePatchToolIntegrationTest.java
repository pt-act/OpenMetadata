package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.json.JsonPatch;
import jakarta.json.JsonValue;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.util.EntityUtil;

/**
 * Integration tests for {@link ValidatePatchTool} — dry-run patch validator.
 *
 * <p>Tests parameter validation, warning generation, downstream count estimation, and the full
 * execute() flow ensuring the tool never mutates state.
 *
 * <p>Tests inject functional interfaces via {@link McpEntityBridge} instead of {@code
 * mockStatic(Entity.class)}, eliminating the need to mock Entity static initializers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ValidatePatchToolIntegrationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.EntityReferenceResolver referenceResolver;
  private McpEntityBridge.PatchAuthorizer noopPatchAuthorizer;
  private McpEntityBridge.RepositoryProvider repoProvider;
  private McpEntityBridge.LineageRepositoryProvider lineageProvider;

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

    // Default injected interfaces — individual tests override as needed
    referenceResolver =
        (entityType, fqn, include) -> {
          EntityReference ref = mock(EntityReference.class);
          when(ref.getFullyQualifiedName()).thenReturn(fqn);
          when(ref.getId()).thenReturn(UUID.randomUUID());
          return ref;
        };
    noopPatchAuthorizer = (entityType, jsonPatch, fqn) -> {};
    lineageProvider = () -> null;
  }

  // ====================== Helper methods ======================

  private EntityReference buildEntityRef(String fqn, UUID id) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    return ref;
  }

  private EntityInterface buildMockEntity(String fqn) {
    EntityInterface entity = mock(EntityInterface.class);
    when(entity.getFullyQualifiedName()).thenReturn(fqn);
    return entity;
  }

  /** Creates a simple entity JSON with owners, tier, description, and tags. */
  private ObjectNode createSampleEntityJson() {
    ObjectNode entity = OBJECT_MAPPER.createObjectNode();
    entity.put("name", "orders");
    entity.put("fullyQualifiedName", "db.schema.orders");
    entity.put("description", "Orders fact table");

    ArrayNode owners = entity.putArray("owners");
    ObjectNode owner1 = owners.addObject();
    owner1.put("name", "alice");
    owner1.put("type", "user");

    ObjectNode tier = entity.putObject("tier");
    tier.put("tagFQN", "Tier.Tier1");

    ArrayNode tags = entity.putArray("tags");
    for (int i = 0; i < 6; i++) {
      ObjectNode tag = tags.addObject();
      tag.put("tagFQN", "PII.Sensitive" + i);
    }
    return entity;
  }

  /** Creates an EntityLineage with downstream nodes. */
  private EntityLineage createLineageWithNodes(String sourceFqn, int downstreamCount) {
    EntityLineage lineage = new EntityLineage();
    List<EntityReference> nodes = new ArrayList<>();

    EntityReference sourceRef = mock(EntityReference.class);
    when(sourceRef.getFullyQualifiedName()).thenReturn(sourceFqn);
    nodes.add(sourceRef);

    for (int i = 0; i < downstreamCount; i++) {
      EntityReference downRef = mock(EntityReference.class);
      when(downRef.getFullyQualifiedName()).thenReturn("db.schema.downstream" + i);
      nodes.add(downRef);
    }

    lineage.setNodes(nodes);
    return lineage;
  }

  /**
   * Sets up the common mock stubs for a full execute() test.
   *
   * <p>All parameters in mockStatic.when() use matchers consistently (never mix raw values with
   * matchers) to avoid InvalidUseOfMatchersException.
   *
   * <p>The {@code convertValue} stub uses an Answer to differentiate the afterSnapshot call (where
   * the argument is the same reference as {@code patchedJson}) from diff entry calls (where the
   * arguments are real diff nodes computed by {@code JsonDiff.asJson}). For diff entries, the
   * Answer converts the node's "op" and "path" fields into a Map so that diff assertions work.
   */
  private void stubJsonUtils(
      MockedStatic<JsonUtils> jsonMock,
      JsonNode entityJson,
      JsonNode patchedJson,
      Map<String, Object> beforeMap,
      Map<String, Object> afterMap) {

    jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
    jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(beforeMap);

    JsonValue patchedJsonValue = mock(JsonValue.class);
    jsonMock
        .when(() -> JsonUtils.applyPatch(any(), any(JsonPatch.class)))
        .thenReturn(patchedJsonValue);
    jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(patchedJson.toString());
    jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(patchedJson);
    jsonMock
        .when(() -> JsonUtils.convertValue(any(JsonNode.class), eq(Map.class)))
        .thenAnswer(
            invocation -> {
              JsonNode arg = invocation.getArgument(0);
              // afterSnapshot call: argument is the same reference as patchedJson
              // (relies on readTree stub returning patchedJson directly)
              if (arg == patchedJson) {
                return afterMap;
              }
              // diff entry: convert the real diff node into a Map with op/path/value
              Map<String, Object> entryMap = new LinkedHashMap<>();
              if (arg.has("op")) entryMap.put("op", arg.get("op").asText());
              if (arg.has("path")) entryMap.put("path", arg.get("path").asText());
              if (arg.has("from")) entryMap.put("from", arg.get("from").asText());
              if (arg.has("value")) {
                JsonNode valueNode = arg.get("value");
                if (valueNode.isTextual()) {
                  entryMap.put("value", valueNode.asText());
                } else {
                  entryMap.put("value", valueNode);
                }
              }
              return entryMap;
            });
  }

  /** Calls the test-friendly execute overload with injected providers. */
  private Map<String, Object> executeWithProviders(
      ValidatePatchTool tool,
      Map<String, Object> params,
      EntityReference entityRef,
      EntityRepository<? extends EntityInterface> mockRepo) {

    // Build reference resolver that returns the expected entityRef for the fqn in params
    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;

    // Build repo provider that returns the mock repo
    McpEntityBridge.RepositoryProvider rp =
        entityType -> {
          @SuppressWarnings("unchecked")
          EntityRepository<EntityInterface> repo = (EntityRepository<EntityInterface>) mockRepo;
          return repo;
        };

    try {
      return tool.execute(params, resolver, noopPatchAuthorizer, rp, lineageProvider);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Calls the production execute overload (for parameter validation tests that don't need Entity). */
  private Map<String, Object> executeProduction(ValidatePatchTool tool, Map<String, Object> params)
      throws Exception {
    return tool.execute(authorizer, securityContext, params);
  }

  // ====================== Parameter Validation Tests ======================

  @Nested
  class ParameterValidation {

    private ValidatePatchTool tool;

    @BeforeEach
    void setUp() {
      tool = new ValidatePatchTool();
    }

    @Test
    void execute_nullParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void execute_emptyParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void execute_nullPatch_throwsIllegalArgumentException() {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID());
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", null);

      // Use test-friendly overload — no mockStatic(Entity.class) needed
      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;

      assertThatThrownBy(
              () ->
                  tool.execute(
                      params, resolver, noopPatchAuthorizer, repoProvider, lineageProvider))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Patch cannot be null or empty");
    }

    @Test
    void execute_emptyPatch_throwsIllegalArgumentException() {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID());
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", "");

      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;

      assertThatThrownBy(
              () ->
                  tool.execute(
                      params, resolver, noopPatchAuthorizer, repoProvider, lineageProvider))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Patch cannot be null or empty");
    }

    @Test
    void execute_malformedJsonPatch_throwsIllegalArgumentException() {
      EntityReference entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID());
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", "not valid json");

      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;

      assertThatThrownBy(
              () ->
                  tool.execute(
                      params, resolver, noopPatchAuthorizer, repoProvider, lineageProvider))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid JSON Patch");
    }

    @Test
    void execute_withLimits_throwsUnsupportedOperationException() {
      assertThatThrownBy(() -> tool.execute(authorizer, null, securityContext, Map.of()))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  // ====================== Warning Generation Tests ======================

  @Nested
  class WarningGeneration {

    @Test
    void generateWarnings_ownerRemoval_warnsWhenAllOwnersRemoved() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putArray("owners").addObject().put("name", "alice");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).anyMatch(w -> w.contains("ownerRemoval"));
    }

    @Test
    void generateWarnings_ownerPresentBeforeAndAfter_noWarning() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putArray("owners").addObject().put("name", "alice");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.putArray("owners").addObject().put("name", "bob");
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).noneMatch(w -> w.contains("ownerRemoval"));
    }

    @Test
    void generateWarnings_tierRemoval_warnsWhenTierRemoved() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putObject("tier").put("tagFQN", "Tier.Tier1");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).anyMatch(w -> w.contains("tierRemoval"));
    }

    @Test
    void generateWarnings_tierPresentBeforeAndAfter_noWarning() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putObject("tier").put("tagFQN", "Tier.Tier1");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.putObject("tier").put("tagFQN", "Tier.Tier2");
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).noneMatch(w -> w.contains("tierRemoval"));
    }

    @Test
    void generateWarnings_massTagRemoval_warnsWhenFiveOrMoreTagsRemoved() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putArray("tags");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.putArray("tags");

      ArrayNode diffNode = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < 5; i++) {
        diffNode.addObject().put("op", "remove").put("path", "/tags/" + i);
      }

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).anyMatch(w -> w.contains("massTagRemoval"));
    }

    @Test
    void generateWarnings_fewerThanFiveTagRemovals_noMassTagWarning() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putArray("tags");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.putArray("tags");

      ArrayNode diffNode = OBJECT_MAPPER.createArrayNode();
      for (int i = 0; i < 4; i++) {
        diffNode.addObject().put("op", "remove").put("path", "/tags/" + i);
      }

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).noneMatch(w -> w.contains("massTagRemoval"));
    }

    @Test
    void generateWarnings_descriptionCleared_warnsWhenDescriptionRemoved() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.put("description", "Important table");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).anyMatch(w -> w.contains("descriptionCleared"));
    }

    @Test
    void generateWarnings_descriptionReplaced_noClearWarning() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.put("description", "Old description");
      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.put("description", "New description");
      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();

      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).noneMatch(w -> w.contains("descriptionCleared"));
    }

    @Test
    void generateWarnings_noRiskyChanges_returnsEmptyList() {
      ObjectNode before = OBJECT_MAPPER.createObjectNode();
      before.putArray("owners").addObject().put("name", "alice");
      before.putObject("tier").put("tagFQN", "Tier.Tier1");
      before.put("description", "A table");

      ObjectNode after = OBJECT_MAPPER.createObjectNode();
      after.putArray("owners").addObject().put("name", "alice");
      after.putObject("tier").put("tagFQN", "Tier.Tier1");
      after.put("description", "A table");

      JsonNode diffNode = OBJECT_MAPPER.createArrayNode();
      List<String> warnings = ValidatePatchTool.generateWarnings(before, after, diffNode);
      assertThat(warnings).isEmpty();
    }
  }

  // ====================== Downstream Count Estimation Tests ======================

  @Nested
  class DownstreamCountEstimation {

    @Test
    void estimateDownstreamCount_nullLineageRepo_returnsZero() {
      // Inject null lineage provider — no mockStatic(Entity.class) needed
      McpEntityBridge.LineageRepositoryProvider lp = () -> null;
      assertThat(ValidatePatchTool.estimateDownstreamCount("table", "db.schema.orders", lp))
          .isEqualTo(0);
    }

    @Test
    void estimateDownstreamCount_exceptionDuringLineage_returnsZero() {
      // Inject provider that throws — no mockStatic(Entity.class) needed
      McpEntityBridge.LineageRepositoryProvider lp =
          () -> {
            throw new RuntimeException("unavailable");
          };
      assertThat(ValidatePatchTool.estimateDownstreamCount("table", "db.schema.orders", lp))
          .isEqualTo(0);
    }

    @Test
    void countDownstream_nullLineage_returnsZero() {
      assertThat(ValidatePatchTool.countDownstream(null, "db.schema.orders")).isEqualTo(0);
    }

    @Test
    void countDownstream_lineageWithNullNodes_returnsZero() {
      EntityLineage lineage = new EntityLineage();
      lineage.setNodes(null);
      assertThat(ValidatePatchTool.countDownstream(lineage, "db.schema.orders")).isEqualTo(0);
    }

    @Test
    void countDownstream_withDownstream_excludesSourceEntity() {
      EntityLineage lineage = createLineageWithNodes("db.schema.orders", 2);
      assertThat(ValidatePatchTool.countDownstream(lineage, "db.schema.orders")).isEqualTo(2);
    }

    @Test
    void countDownstream_noDownstream_returnsZero() {
      EntityLineage lineage = createLineageWithNodes("db.schema.orders", 0);
      assertThat(ValidatePatchTool.countDownstream(lineage, "db.schema.orders")).isEqualTo(0);
    }

    @Test
    void countDownstream_multipleDownstream_correctCount() {
      EntityLineage lineage = createLineageWithNodes("db.schema.orders", 5);
      assertThat(ValidatePatchTool.countDownstream(lineage, "db.schema.orders")).isEqualTo(5);
    }
  }

  // ====================== Full Execute Flow Tests ======================

  @Nested
  class FullExecuteFlow {

    private ValidatePatchTool tool;

    @BeforeEach
    void setUp() {
      tool = new ValidatePatchTool();
    }

    @Test
    void execute_simpleReplacePatch_returnsBeforeAndAfter() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
      entityJson.put("name", "orders");
      entityJson.put("description", "Old description");
      entityJson.put("fullyQualifiedName", "db.schema.orders");

      ObjectNode patchedJson = entityJson.deepCopy();
      patchedJson.put("description", "New description");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      beforeMap.put("description", "Old description");

      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");
      afterMap.put("description", "New description");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put(
          "patch",
          "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"New description\"}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, mockRepo);

        // Verify response shape (R9.2)
        assertThat(result).containsEntry("fqn", "db.schema.orders");
        assertThat(result).containsEntry("entityType", "table");
        assertThat(result).containsKey("beforeSnapshot");
        assertThat(result).containsKey("afterSnapshot");
        assertThat(result).containsKey("diff");
        assertThat(result).containsKey("affectedDownstreamCount");
        assertThat(result)
            .containsEntry(
                "affectedDownstreamCountNote",
                "1-hop lineage estimate, not exhaustive; use change_impact for full analysis");

        // Verify before and after differ
        @SuppressWarnings("unchecked")
        Map<String, Object> before = (Map<String, Object>) result.get("beforeSnapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) result.get("afterSnapshot");
        assertThat(before).containsEntry("description", "Old description");
        assertThat(after).containsEntry("description", "New description");

        // No warnings for a simple description replace
        assertThat(result).doesNotContainKey("warnings");
      }
    }

    @Test
    void execute_addOwnerPatch_returnsDiffWithAddOperation() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
      entityJson.put("name", "orders");
      entityJson.putArray("owners").addObject().put("name", "alice").put("type", "user");

      ObjectNode patchedJson = entityJson.deepCopy();
      ((ArrayNode) patchedJson.get("owners")).addObject().put("name", "bob").put("type", "user");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put(
          "patch",
          "[{\"op\":\"add\",\"path\":\"/owners/-\",\"value\":{\"name\":\"bob\",\"type\":\"user\"}}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, mockRepo);

        // Verify diff is present and contains add operation for /owners
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diff = (List<Map<String, Object>>) result.get("diff");
        assertThat(diff).isNotEmpty();
        assertThat(
                diff.stream()
                    .anyMatch(
                        e ->
                            "add".equals(e.get("op"))
                                && e.get("path") != null
                                && e.get("path").toString().startsWith("/owners")))
            .isTrue();
      }
    }

    @Test
    void execute_removeAllOwners_includesOwnerRemovalWarning() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = createSampleEntityJson();
      ObjectNode patchedJson = OBJECT_MAPPER.createObjectNode();
      patchedJson.put("name", "orders");
      patchedJson.put("fullyQualifiedName", "db.schema.orders");
      patchedJson.put("description", "Orders fact table");
      patchedJson.putObject("tier").put("tagFQN", "Tier.Tier1");
      // No owners in patched

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", "[{\"op\":\"remove\",\"path\":\"/owners\"}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, mockRepo);

        assertThat(result).containsKey("warnings");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).anyMatch(w -> w.contains("ownerRemoval"));
      }
    }

    @Test
    void execute_nullLineageRepo_returnsZeroDownstreamCount() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
      entityJson.put("name", "orders");
      entityJson.put("description", "test");
      entityJson.put("fullyQualifiedName", "db.schema.orders");

      ObjectNode patchedJson = entityJson.deepCopy();
      patchedJson.put("description", "updated");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"updated\"}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        // lineageProvider defaults to null — no mockStatic(Entity.class) needed
        Map<String, Object> result = executeWithProviders(tool, params, entityRef, mockRepo);
        assertThat(result).containsEntry("affectedDownstreamCount", 0);
      }
    }

    @Test
    void execute_neverCallsRepositoryPatchOrUpdate() throws Exception {
      // R9.4: validate_patch must never write to OM
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
      entityJson.put("name", "orders");
      entityJson.put("description", "test");
      entityJson.put("fullyQualifiedName", "db.schema.orders");

      ObjectNode patchedJson = entityJson.deepCopy();
      patchedJson.put("description", "new");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("patch", "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        executeWithProviders(tool, params, entityRef, mockRepo);

        // R9.4: verify only read-only methods were called on the repository
        verify(mockRepo).getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class));
        verify(mockRepo).getFields(anyString());
        verifyNoMoreInteractions(mockRepo); // no patch(), update(), delete(), etc.
      }
    }

    @Test
    void execute_fullyQualifiedNameAlias_resolvesEntity() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      EntityInterface mockEntity = buildMockEntity("db.schema.orders");

      ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
      entityJson.put("name", "orders");
      entityJson.put("description", "test");

      ObjectNode patchedJson = entityJson.deepCopy();
      patchedJson.put("description", "new");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(mockEntity);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> beforeMap = new LinkedHashMap<>();
      beforeMap.put("name", "orders");
      Map<String, Object> afterMap = new LinkedHashMap<>();
      afterMap.put("name", "orders");

      // Use fullyQualifiedName instead of fqn (R9.1 — same input shape as patch_entity)
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fullyQualifiedName", "db.schema.orders");
      params.put("patch", "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        stubJsonUtils(jsonMock, entityJson, patchedJson, beforeMap, afterMap);

        Map<String, Object> result = executeWithProviders(tool, params, entityRef, mockRepo);

        assertThat(result).containsEntry("fqn", "db.schema.orders");
        assertThat(result).containsKey("beforeSnapshot");
        assertThat(result).containsKey("afterSnapshot");
      }
    }

    /**
     * R9.8: Parity test — for golden patches, {@code validate_patch} afterSnapshot must equal
     * {@code patch_entity} post-state. Requires a running server with seeded data.
     */
    @Test
    @Disabled("R9.8: see ValidatePatchParityIT in openmetadata-integration-tests")
    void execute_parityWithPatchEntity_afterSnapshotMatches() {
      // Placeholder: when a server is available, apply the same patch via both
      // validate_patch and patch_entity, then compare afterSnapshot with actual post-state.
    }
  }
}
