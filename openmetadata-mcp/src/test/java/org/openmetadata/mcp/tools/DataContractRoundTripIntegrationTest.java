package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnDataType;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.RestUtil;

/**
 * Integration tests for the Data Contract Round-trip tools: {@link GenerateDataContractTool} and
 * {@link ApplyDataContractTool}.
 *
 * <p>Tests the full round-trip flow: export → edit → dry-run apply, partial failure with atomic
 * rollback, missing-entity handling, and parameter validation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataContractRoundTripIntegrationTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;

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
  }

  // ====================== Helper methods ======================

  private EntityReference buildEntityRef(String fqn, UUID id) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    return ref;
  }

  /** Builds a mock Table entity with columns, owners, tier, and tags. */
  private Table buildMockTable(String fqn, String description) {
    Table table = mock(Table.class);
    when(table.getFullyQualifiedName()).thenReturn(fqn);
    when(table.getName()).thenReturn(fqn.substring(fqn.lastIndexOf('.') + 1));
    when(table.getDescription()).thenReturn(description);
    when(table.getDisplayName()).thenReturn(null);
    when(table.getOwners()).thenReturn(null);
    when(table.getDomains()).thenReturn(null);
    when(table.getExtension()).thenReturn(null);
    when(table.getRetentionPeriod()).thenReturn(null);
    when(table.getSourceUrl()).thenReturn(null);
    when(table.getSchemaDefinition()).thenReturn(null);
    when(table.getTableConstraints()).thenReturn(null);

    // Build columns
    Column col1 = mock(Column.class);
    when(col1.getName()).thenReturn("id");
    when(col1.getDataType()).thenReturn(ColumnDataType.BIGINT);
    when(col1.getConstraint()).thenReturn(null);
    when(col1.getDescription()).thenReturn("Primary key");
    when(col1.getTags()).thenReturn(null);

    Column col2 = mock(Column.class);
    when(col2.getName()).thenReturn("name");
    when(col2.getDataType()).thenReturn(ColumnDataType.VARCHAR);
    when(col2.getConstraint()).thenReturn(null);
    when(col2.getDescription()).thenReturn("Customer name");
    when(col2.getTags()).thenReturn(null);

    when(table.getColumns()).thenReturn(List.of(col1, col2));

    // Build tags: one tier + one classification + one glossary
    TagLabel tierTag = mock(TagLabel.class);
    when(tierTag.getTagFQN()).thenReturn("Tier.Tier1");
    when(tierTag.getSource()).thenReturn(TagLabel.TagSource.CLASSIFICATION);

    TagLabel classTag = mock(TagLabel.class);
    when(classTag.getTagFQN()).thenReturn("PII.Sensitive");
    when(classTag.getSource()).thenReturn(TagLabel.TagSource.CLASSIFICATION);

    TagLabel glossaryTag = mock(TagLabel.class);
    when(glossaryTag.getTagFQN()).thenReturn("Glossary.Revenue");
    when(glossaryTag.getSource()).thenReturn(TagLabel.TagSource.GLOSSARY);

    when(table.getTags()).thenReturn(List.of(tierTag, classTag, glossaryTag));

    return table;
  }

  /** Builds a mock Table with owners. */
  private Table buildMockTableWithOwners(String fqn, String description, List<String> ownerNames) {
    Table table = buildMockTable(fqn, description);

    List<EntityReference> owners = new ArrayList<>();
    for (String ownerName : ownerNames) {
      EntityReference ownerRef = mock(EntityReference.class);
      when(ownerRef.getName()).thenReturn(ownerName);
      when(ownerRef.getType()).thenReturn("user");
      when(ownerRef.getId()).thenReturn(UUID.randomUUID());
      owners.add(ownerRef);
    }
    when(table.getOwners()).thenReturn(owners);

    return table;
  }

  // ====================== GenerateDataContractTool Tests ======================

  @Nested
  class GenerateDataContract {

    private GenerateDataContractTool tool;

    @BeforeEach
    void setUp() {
      tool = new GenerateDataContractTool();
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
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_exportsContractWithCorrectApiVersion() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");

      // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
      McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
      McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;

      Map<String, Object> result =
          tool.execute(params, securityContext, resolver, noOpAuthorizer, repoProvider);

      assertThat(result).containsEntry("apiVersion", "openmetadata.org/v1alpha1");
      assertThat(result).containsEntry("kind", "DataContract");
      assertThat(result).containsEntry("fqn", "db.schema.orders");
      assertThat(result).containsKey("contractYaml");
      assertThat(result).containsKey("narrative");

      // Verify YAML is valid and parseable
      String yaml = (String) result.get("contractYaml");
      assertThat(yaml).contains("apiVersion:");
      assertThat(yaml).contains("DataContract");
      assertThat(yaml).contains("db.schema.orders");
    }

    @Test
    void execute_contractIncludesTierTagsAndGlossary() throws Exception {
      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");

      // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
      McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
      McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
      McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;

      Map<String, Object> result =
          tool.execute(params, securityContext, resolver, noOpAuthorizer, repoProvider);

      String yaml = (String) result.get("contractYaml");
      // Tier should be in the YAML
      assertThat(yaml).contains("tier:");
      assertThat(yaml).contains("Tier.Tier1");
      // Classification tags
      assertThat(yaml).contains("tags:");
      assertThat(yaml).contains("PII.Sensitive");
      // Glossary terms
      assertThat(yaml).contains("glossaryTerms:");
      assertThat(yaml).contains("Glossary.Revenue");
      // Schema columns
      assertThat(yaml).contains("schema:");
      assertThat(yaml).contains("id");
      assertThat(yaml).contains("name");
    }

    @Test
    void execute_withLimits_throwsUnsupportedOperationException() {
      assertThatThrownBy(() -> tool.execute(authorizer, null, securityContext, Map.of()))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  // ====================== ApplyDataContractTool Tests ======================

  @Nested
  class ApplyDataContract {

    private ApplyDataContractTool tool;

    @BeforeEach
    void setUp() {
      tool = new ApplyDataContractTool();
    }

    @Test
    void execute_nullParams_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("cannot be null or empty");
    }

    @Test
    void execute_missingContractYaml_throwsIllegalArgumentException() {
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");

      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("contractYaml");
    }

    @Test
    void execute_missingFqnInContract_throwsIllegalArgumentException() {
      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", "apiVersion: openmetadata.org/v1alpha1\nkind: DataContract\n");

      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("metadata.fqn");
    }

    @Test
    void execute_invalidYaml_throwsIllegalArgumentException() {
      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", "{{invalid yaml : [");

      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid contract YAML");
    }

    @Test
    void execute_withLimits_throwsUnsupportedOperationException() {
      assertThatThrownBy(() -> tool.execute(authorizer, null, securityContext, Map.of()))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void execute_dryRunDefault_isTrue() throws Exception {
      // Verify that dryRun defaults to true (safe preview mode per R7.5)
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      // Build a contract that changes the description
      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      metadata.put("description", "Updated description");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      // Not setting dryRun explicitly — should default to true

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        // Set up JsonUtils mocks
        ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
        entityJson.put("name", "orders");
        entityJson.put("description", "Orders fact table");
        entityJson.put("fullyQualifiedName", "db.schema.orders");

        ObjectNode patchedJson = entityJson.deepCopy();
        patchedJson.put("description", "Updated description");

        jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
        jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(Map.of("name", "orders"));
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(patchedJson.toString());
        jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(patchedJson);

        jakarta.json.JsonValue patchedJsonValue = mock(jakarta.json.JsonValue.class);
        jsonMock
            .when(() -> JsonUtils.applyPatch(any(), any(jakarta.json.JsonPatch.class)))
            .thenReturn(patchedJsonValue);
        jsonMock
            .when(
                () ->
                    JsonUtils.convertValue(
                        any(com.fasterxml.jackson.databind.JsonNode.class), eq(Map.class)))
            .thenReturn(Map.of("name", "orders"));
        jsonMock.when(() -> JsonUtils.getObjectMapper()).thenReturn(OBJECT_MAPPER);

        // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
        McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
        McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
        McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noOpResolver, noOpAuthorizer, repoProvider, noOpPublisher);

        // dryRun should default to true
        assertThat(result).containsEntry("dryRun", true);
        assertThat(result).containsEntry("status", "preview");
      }
    }

    @Test
    void execute_noChangesNeeded_returnsNoChangesStatus() throws Exception {
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      // Contract that matches current state (no changes needed)
      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      params.put("dryRun", "false");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        // When entity map equals desired map, diff will be empty
        ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
        entityJson.put("name", "orders");
        jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
        jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(Map.of("name", "orders"));
        jsonMock.when(() -> JsonUtils.getObjectMapper()).thenReturn(OBJECT_MAPPER);

        // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
        McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
        McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
        McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noOpResolver, noOpAuthorizer, repoProvider, noOpPublisher);

        assertThat(result).containsEntry("status", "noChangesNeeded");
        assertThat(result).containsEntry("fqn", "db.schema.orders");
      }
    }
  }

  // ====================== Round-trip Test (R7.9) ======================

  @Nested
  class RoundTripTest {

    private GenerateDataContractTool genTool;
    private ApplyDataContractTool applyTool;

    @BeforeEach
    void setUp() {
      genTool = new GenerateDataContractTool();
      applyTool = new ApplyDataContractTool();
    }

    @Test
    void generateThenApply_dryRun_showsExpectedDiff() throws Exception {
      // R7.9: Round-trip test — generate → edit YAML → apply(dryRun=true) shows expected diff

      UUID entityId = UUID.randomUUID();
      EntityReference entityRef = buildEntityRef("db.schema.orders", entityId);
      Table table =
          buildMockTableWithOwners("db.schema.orders", "Orders fact table", List.of("alice"));

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      // Step 1: Generate the contract
      Map<String, Object> genParams = new HashMap<>();
      genParams.put("entityType", "table");
      genParams.put("fqn", "db.schema.orders");

      // Use test-friendly overloads for both generate and apply steps — inject bridge
      // interfaces, no mockStatic(Entity.class) needed.
      McpEntityBridge.EntityReferenceResolver genResolver = (entityType, fqn, include) -> entityRef;
      McpEntityBridge.McpAuthorizer genAuthorizer = (entityType, op) -> {};
      McpEntityBridge.RepositoryProvider genRepoProvider = (entityType) -> mockRepo;

      Map<String, Object> genResult =
          genTool.execute(genParams, securityContext, genResolver, genAuthorizer, genRepoProvider);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        // Set up JsonUtils for the apply step
        ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
        entityJson.put("name", "orders");
        entityJson.put("description", "Orders fact table");
        entityJson.put("fullyQualifiedName", "db.schema.orders");

        jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(
                Map.of(
                    "name",
                    "orders",
                    "description",
                    "Orders fact table",
                    "fullyQualifiedName",
                    "db.schema.orders"));
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(entityJson.toString());
        jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(entityJson);
        jsonMock.when(() -> JsonUtils.getObjectMapper()).thenReturn(OBJECT_MAPPER);
        jsonMock
            .when(
                () ->
                    JsonUtils.convertValue(
                        any(com.fasterxml.jackson.databind.JsonNode.class), eq(Map.class)))
            .thenReturn(
                Map.of(
                    "op",
                    "replace",
                    "path",
                    "/description",
                    "value",
                    "Updated orders description"));

        jakarta.json.JsonValue patchedJsonValue = mock(jakarta.json.JsonValue.class);
        jsonMock
            .when(() -> JsonUtils.applyPatch(any(), any(jakarta.json.JsonPatch.class)))
            .thenReturn(patchedJsonValue);

        // Step 2: Edit the contract YAML (change description)
        String yaml = (String) genResult.get("contractYaml");
        String editedYaml = yaml.replace("Orders fact table", "Updated orders description");

        // Step 3: Apply with dryRun=true
        Map<String, Object> applyParams = new HashMap<>();
        applyParams.put("contractYaml", editedYaml);
        applyParams.put("dryRun", true);

        // Re-stub for the apply step (desired state differs from current)
        ObjectNode patchedJson = entityJson.deepCopy();
        patchedJson.put("description", "Updated orders description");
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(patchedJson.toString());
        jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(patchedJson);

        // Use test-friendly execute overload for apply step — inject no-op bridge interfaces
        McpEntityBridge.EntityReferenceResolver applyResolver = (entityType, fqn, include) -> null;
        McpEntityBridge.McpAuthorizer applyAuthorizer = (entityType, op) -> {};
        McpEntityBridge.RepositoryProvider applyRepoProvider = (entityType) -> mockRepo;
        McpEntityBridge.ChangeEventPublisher applyPublisher = (entity, changeType, userName) -> {};

        Map<String, Object> applyResult =
            applyTool.execute(
                applyParams,
                securityContext,
                applyResolver,
                applyAuthorizer,
                applyRepoProvider,
                applyPublisher);

        // Verify dry-run preview
        assertThat(applyResult).containsEntry("fqn", "db.schema.orders");
        assertThat(applyResult).containsEntry("dryRun", true);
        assertThat(applyResult).containsEntry("status", "preview");
        assertThat(applyResult).containsKey("changePlan");
        assertThat(applyResult).containsKey("changeCount");
        assertThat(applyResult).containsKey("validationPreview");

        // Verify the changePlan contains the description replace operation
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changePlan =
            (List<Map<String, Object>>) applyResult.get("changePlan");
        assertThat(changePlan)
            .anyMatch(
                e ->
                    "replace".equals(String.valueOf(e.get("op")))
                        && String.valueOf(e.get("path")).contains("description"));

        // No applied/rolledBack/failed in dry-run mode
        assertThat(applyResult).containsEntry("applied", List.of());
        assertThat(applyResult).containsEntry("rolledBack", List.of());
        assertThat(applyResult).containsEntry("failed", List.of());

        // Narrative should mention preview
        String narrative = (String) applyResult.get("narrative");
        assertThat(narrative).contains("Preview");
        assertThat(narrative).contains("Dry Run");
      }
    }

    @Test
    void buildContract_roundTripsThroughYaml() throws Exception {
      // Verify that buildContract() output can be serialized to YAML and parsed back
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      // Serialize to YAML
      String yaml = YAML_MAPPER.writeValueAsString(contract);

      // Parse back
      Map<String, Object> parsed = ApplyDataContractTool.parseContractYaml(yaml);

      // Verify round-trip integrity
      assertThat(parsed).containsEntry("apiVersion", "openmetadata.org/v1alpha1");
      assertThat(parsed).containsEntry("kind", "DataContract");

      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) parsed.get("metadata");
      assertThat(metadata).containsEntry("fqn", "db.schema.orders");
      assertThat(metadata).containsEntry("description", "Orders fact table");

      // Tier should round-trip
      assertThat(parsed).containsEntry("tier", "Tier.Tier1");

      // Tags should round-trip
      @SuppressWarnings("unchecked")
      List<String> tags = (List<String>) parsed.get("tags");
      assertThat(tags).contains("PII.Sensitive");

      // Glossary terms should round-trip
      @SuppressWarnings("unchecked")
      List<String> glossaryTerms = (List<String>) parsed.get("glossaryTerms");
      assertThat(glossaryTerms).contains("Glossary.Revenue");
    }
  }

  // ====================== Partial-Failure Test (R7.10) ======================

  @Nested
  class PartialFailureTest {

    private ApplyDataContractTool tool;

    @BeforeEach
    void setUp() {
      tool = new ApplyDataContractTool();
    }

    @Test
    void execute_patchFailure_allChangesMarkedFailed() throws Exception {
      // R7.10: Partial-failure test — repository.patch() is atomic, so on failure
      // all changes should be in the `failed` list with no partial state.

      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      // Contract that changes the description (will produce a diff)
      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      metadata.put("description", "Updated description");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      params.put("dryRun", false);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        // Set up JsonUtils mocks for diff computation
        ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
        entityJson.put("name", "orders");
        entityJson.put("description", "Orders fact table");
        entityJson.put("fullyQualifiedName", "db.schema.orders");

        jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(
                Map.of(
                    "name",
                    "orders",
                    "description",
                    "Orders fact table",
                    "fullyQualifiedName",
                    "db.schema.orders"));
        jsonMock.when(() -> JsonUtils.getObjectMapper()).thenReturn(OBJECT_MAPPER);
        jsonMock
            .when(() -> JsonUtils.pojoToJson(any()))
            .thenReturn(
                "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"Updated description\"}]");
        jsonMock
            .when(
                () ->
                    JsonUtils.convertValue(
                        any(com.fasterxml.jackson.databind.JsonNode.class), eq(Map.class)))
            .thenReturn(
                Map.of("op", "replace", "path", "/description", "value", "Updated description"));

        // Make repository.patch() throw an exception
        when(mockRepo.patch(any(), anyString(), anyString(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Simulated patch failure"));

        // Use test-friendly execute overload with no-op ChangeEventPublisher
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
        McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
        McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
        McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noOpResolver, noOpAuthorizer, repoProvider, noOpPublisher);

        // Verify failure handling
        assertThat(result).containsEntry("fqn", "db.schema.orders");
        assertThat(result).containsEntry("status", "failed");
        assertThat(result).containsEntry("dryRun", false);

        // All changes should be in failed list
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertThat(failed).isNotEmpty();
        assertThat(failed.get(0)).containsEntry("status", "failed");

        // Applied and rolledBack should be empty (atomic patch)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) result.get("applied");
        assertThat(applied).isEmpty();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rolledBack = (List<Map<String, Object>>) result.get("rolledBack");
        assertThat(rolledBack).isEmpty();

        // Should have a rollback note explaining atomicity
        assertThat(result).containsKey("rollbackNote");
        String rollbackNote = (String) result.get("rollbackNote");
        assertThat(rollbackNote).contains("atomic");
      }
    }

    @Test
    void execute_patchSuccess_allChangesMarkedApplied() throws Exception {
      // Happy path: all changes applied successfully

      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), eq("db.schema.orders"), any(EntityUtil.Fields.class)))
          .thenReturn(table);
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      metadata.put("description", "Updated description");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      params.put("dryRun", false);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        ObjectNode entityJson = OBJECT_MAPPER.createObjectNode();
        entityJson.put("name", "orders");
        entityJson.put("description", "Orders fact table");
        entityJson.put("fullyQualifiedName", "db.schema.orders");

        jsonMock.when(() -> JsonUtils.pojoToJsonNode(any())).thenReturn(entityJson);
        jsonMock
            .when(() -> JsonUtils.getMap(any()))
            .thenReturn(
                Map.of(
                    "name",
                    "orders",
                    "description",
                    "Orders fact table",
                    "fullyQualifiedName",
                    "db.schema.orders"));
        jsonMock.when(() -> JsonUtils.getObjectMapper()).thenReturn(OBJECT_MAPPER);
        jsonMock
            .when(() -> JsonUtils.pojoToJson(any()))
            .thenReturn(
                "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"Updated description\"}]");
        jsonMock
            .when(
                () ->
                    JsonUtils.convertValue(
                        any(com.fasterxml.jackson.databind.JsonNode.class), eq(Map.class)))
            .thenReturn(
                Map.of("op", "replace", "path", "/description", "value", "Updated description"));

        // Successful patch
        @SuppressWarnings("unchecked")
        RestUtil.PatchResponse<EntityInterface> patchResponse = mock(RestUtil.PatchResponse.class);
        when(patchResponse.entity()).thenReturn(table);
        when(patchResponse.changeType())
            .thenReturn(org.openmetadata.schema.type.EventType.ENTITY_UPDATED);
        when(mockRepo.patch(any(), anyString(), anyString(), any(), any(), any(), any()))
            .thenReturn(patchResponse);

        // Use test-friendly execute overload with no-op ChangeEventPublisher
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
        McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
        McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
        McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

        Map<String, Object> result =
            tool.execute(
                params, securityContext, noOpResolver, noOpAuthorizer, repoProvider, noOpPublisher);

        assertThat(result).containsEntry("status", "applied");
        assertThat(result).containsEntry("dryRun", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) result.get("applied");
        assertThat(applied).isNotEmpty();
        assertThat(applied.get(0)).containsEntry("status", "applied");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertThat(failed).isEmpty();
      }
    }
  }

  // ====================== Missing-Entity Test (R7.11) ======================

  @Nested
  class MissingEntityTest {

    private ApplyDataContractTool tool;

    @BeforeEach
    void setUp() {
      tool = new ApplyDataContractTool();
    }

    @Test
    void execute_createIfMissingFalse_throwsError() throws Exception {
      // R7.11: createIfMissing=false → structured error when entity not found

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), anyString(), any(EntityUtil.Fields.class)))
          .thenThrow(new EntityNotFoundException("Entity not found"));
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.nonexistent");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      // createIfMissing defaults to false

      // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
      McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
      McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
      McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
      McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

      assertThatThrownBy(
              () ->
                  tool.execute(
                      params,
                      securityContext,
                      noOpResolver,
                      noOpAuthorizer,
                      repoProvider,
                      noOpPublisher))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not found")
          .hasMessageContaining("createIfMissing=true");
    }

    @Test
    void execute_createIfMissingTrue_returnsEntityNotFoundResult() throws Exception {
      // R7.11: createIfMissing=true → structured result indicating entity not found
      // (automatic creation deferred per implementation note)

      @SuppressWarnings("unchecked")
      EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
      when(mockRepo.getByName(any(), anyString(), any(EntityUtil.Fields.class)))
          .thenThrow(new EntityNotFoundException("Entity not found"));
      when(mockRepo.getFields(anyString())).thenReturn(EntityUtil.Fields.EMPTY_FIELDS);

      Map<String, Object> contractMap = new LinkedHashMap<>();
      contractMap.put("apiVersion", "openmetadata.org/v1alpha1");
      contractMap.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.nonexistent");
      contractMap.put("metadata", metadata);

      String contractYaml = YAML_MAPPER.writeValueAsString(contractMap);

      Map<String, Object> params = new HashMap<>();
      params.put("contractYaml", contractYaml);
      params.put("createIfMissing", true);

      // Use test-friendly overload — inject bridge interfaces, no mockStatic(Entity.class)
      McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;
      McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
      McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
      McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

      Map<String, Object> result =
          tool.execute(
              params, securityContext, noOpResolver, noOpAuthorizer, repoProvider, noOpPublisher);

      // Should return a structured result, not throw
      assertThat(result).containsEntry("fqn", "db.schema.nonexistent");
      assertThat(result).containsEntry("status", "entityNotFound");
      assertThat(result).containsKey("message");
      assertThat(result).containsKey("narrative");

      String message = (String) result.get("message");
      assertThat(message).contains("not found");
      assertThat(message).contains("createIfMissing");
    }
  }

  // ====================== Contract Parsing Tests ======================

  @Nested
  class ContractParsing {

    @Test
    void parseContractYaml_validYaml_returnsMap() throws Exception {
      String yaml =
          """
          apiVersion: openmetadata.org/v1alpha1
          kind: DataContract
          metadata:
            fqn: db.schema.orders
            description: Test table
          tier: Tier.Tier1
          tags:
            - PII.Sensitive
          """;

      Map<String, Object> result = ApplyDataContractTool.parseContractYaml(yaml);

      assertThat(result).containsEntry("apiVersion", "openmetadata.org/v1alpha1");
      assertThat(result).containsEntry("kind", "DataContract");

      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
      assertThat(metadata).containsEntry("fqn", "db.schema.orders");

      assertThat(result).containsEntry("tier", "Tier.Tier1");
    }

    @Test
    void parseContractYaml_invalidYaml_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> ApplyDataContractTool.parseContractYaml("{{invalid"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid contract YAML");
    }

    @Test
    void parseContractYaml_emptyString_throwsIllegalArgumentException() {
      assertThatThrownBy(() -> ApplyDataContractTool.parseContractYaml(""))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ====================== buildDesiredState Tests ======================

  @Nested
  class BuildDesiredState {

    @Test
    void buildDesiredState_preservesUnchangedFields() {
      // Verify that buildDesiredState preserves fields not mentioned in the contract
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      Map<String, Object> contract = new LinkedHashMap<>();
      contract.put("apiVersion", "openmetadata.org/v1alpha1");
      contract.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      // Only change description — other fields should be preserved
      metadata.put("description", "New description");
      contract.put("metadata", metadata);

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        Map<String, Object> entityMap = new LinkedHashMap<>();
        entityMap.put("name", "orders");
        entityMap.put("description", "Orders fact table");
        entityMap.put("fullyQualifiedName", "db.schema.orders");
        entityMap.put("tags", List.of());

        jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(entityMap);

        // Use no-op resolver lambda instead of mockStatic(Entity.class) +
        // defaultEntityReferenceResolver()
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;

        Map<String, Object> desired =
            ApplyDataContractTool.buildDesiredState(contract, table, noOpResolver);

        // Description should be changed
        assertThat(desired).containsEntry("description", "New description");
        // Other fields should be preserved from entityMap
        assertThat(desired).containsEntry("name", "orders");
        assertThat(desired).containsEntry("fullyQualifiedName", "db.schema.orders");
      }
    }

    @Test
    void buildDesiredState_withTier_addsTierTag() {
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      Map<String, Object> contract = new LinkedHashMap<>();
      contract.put("apiVersion", "openmetadata.org/v1alpha1");
      contract.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      contract.put("metadata", metadata);
      contract.put("tier", "Tier.Tier2");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        Map<String, Object> entityMap = new LinkedHashMap<>();
        entityMap.put("name", "orders");
        entityMap.put("tags", List.of());

        jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(entityMap);

        // Use no-op resolver lambda instead of mockStatic(Entity.class) +
        // defaultEntityReferenceResolver()
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;

        Map<String, Object> desired =
            ApplyDataContractTool.buildDesiredState(contract, table, noOpResolver);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) desired.get("tags");
        assertThat(tags).isNotEmpty();

        // Should have a tier tag
        boolean hasTierTag = tags.stream().anyMatch(t -> "Tier.Tier2".equals(t.get("tagFQN")));
        assertThat(hasTierTag).isTrue();
      }
    }

    @Test
    void buildDesiredState_withTags_addsClassificationTags() {
      Table table = buildMockTable("db.schema.orders", "Orders fact table");

      Map<String, Object> contract = new LinkedHashMap<>();
      contract.put("apiVersion", "openmetadata.org/v1alpha1");
      contract.put("kind", "DataContract");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      contract.put("metadata", metadata);
      contract.put("tags", List.of("PII.Sensitive", "DataQuality.Validated"));

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        Map<String, Object> entityMap = new LinkedHashMap<>();
        entityMap.put("name", "orders");
        entityMap.put("tags", List.of());

        jsonMock.when(() -> JsonUtils.getMap(any())).thenReturn(entityMap);

        // Use no-op resolver lambda instead of mockStatic(Entity.class) +
        // defaultEntityReferenceResolver()
        McpEntityBridge.EntityReferenceResolver noOpResolver = (entityType, fqn, include) -> null;

        Map<String, Object> desired =
            ApplyDataContractTool.buildDesiredState(contract, table, noOpResolver);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) desired.get("tags");
        assertThat(tags).hasSizeGreaterThanOrEqualTo(2);

        // Should have the classification tags
        boolean hasPII = tags.stream().anyMatch(t -> "PII.Sensitive".equals(t.get("tagFQN")));
        boolean hasDQ =
            tags.stream().anyMatch(t -> "DataQuality.Validated".equals(t.get("tagFQN")));
        assertThat(hasPII).isTrue();
        assertThat(hasDQ).isTrue();
      }
    }
  }

  // ====================== Narrative Generation Tests ======================

  @Nested
  class NarrativeGeneration {

    @Test
    void generateNarrative_dryRun_includesPreviewWarning() {
      String narrative = ApplyDataContractTool.generateNarrative("db.schema.orders", 3, true);
      assertThat(narrative).contains("Preview");
      assertThat(narrative).contains("Dry Run");
      assertThat(narrative).contains("3 patch operation(s)");
      assertThat(narrative).contains("No changes were applied");
    }

    @Test
    void generateNarrative_applied_includesAppliedMessage() {
      String narrative = ApplyDataContractTool.generateNarrative("db.schema.orders", 2, false);
      assertThat(narrative).contains("Applied");
      assertThat(narrative).contains("2 patch operation(s)");
      assertThat(narrative).contains("generate_data_contract");
    }

    @Test
    void generateContractNarrative_includesEntityAndOwners() {
      Map<String, Object> contract = new LinkedHashMap<>();
      contract.put("apiVersion", "openmetadata.org/v1alpha1");
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("fqn", "db.schema.orders");
      metadata.put("owners", List.of("alice", "bob"));
      contract.put("metadata", metadata);
      contract.put("tier", "Tier.Tier2");
      contract.put("tags", List.of("PII.Sensitive"));
      contract.put("schema", List.of(Map.of("name", "id"), Map.of("name", "name")));

      String narrative = GenerateDataContractTool.generateNarrative("db.schema.orders", contract);

      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("alice");
      assertThat(narrative).contains("bob");
      assertThat(narrative).contains("Tier.Tier2");
      assertThat(narrative).contains("2 column(s)");
      assertThat(narrative).contains("PII.Sensitive");
      assertThat(narrative).contains("apply_data_contract");
    }
  }

  // ====================== composeValidatePatchPreview Tests ======================

  @Nested
  class ComposeValidatePatchPreview {

    @Test
    void composeValidatePatchPreview_returnsBeforeAfterAndDiff() throws Exception {
      // Direct test of the preview composition method with known inputs
      String entityType = "table";
      String fqn = "db.schema.orders";
      String patchString =
          "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"New desc\"}]";

      EntityInterface entity = mock(EntityInterface.class);
      when(entity.getFullyQualifiedName()).thenReturn(fqn);

      ObjectNode beforeNode = OBJECT_MAPPER.createObjectNode();
      beforeNode.put("name", "orders");
      beforeNode.put("description", "Old desc");
      beforeNode.put("fullyQualifiedName", "db.schema.orders");

      ObjectNode afterNode = OBJECT_MAPPER.createObjectNode();
      afterNode.put("name", "orders");
      afterNode.put("description", "New desc");
      afterNode.put("fullyQualifiedName", "db.schema.orders");

      try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

        // Inject no-op lineage provider instead of mockStatic(Entity.class)
        McpEntityBridge.LineageRepositoryProvider noOpLineageProvider = () -> null;

        jakarta.json.JsonValue patchedJsonValue = mock(jakarta.json.JsonValue.class);
        jsonMock
            .when(() -> JsonUtils.applyPatch(any(), any(jakarta.json.JsonPatch.class)))
            .thenReturn(patchedJsonValue);
        jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn(afterNode.toString());
        jsonMock.when(() -> JsonUtils.readTree(anyString())).thenReturn(afterNode);
        jsonMock
            .when(
                () ->
                    JsonUtils.convertValue(
                        any(com.fasterxml.jackson.databind.JsonNode.class), eq(Map.class)))
            .thenReturn(Map.of("name", "orders", "description", "New desc"));

        Map<String, Object> preview =
            ApplyDataContractTool.composeValidatePatchPreview(
                entityType, fqn, patchString, entity, beforeNode, noOpLineageProvider);

        // Verify the preview structure matches ValidatePatchTool output shape
        assertThat(preview).containsKey("beforeSnapshot");
        assertThat(preview).containsKey("afterSnapshot");
        assertThat(preview).containsKey("diff");
        assertThat(preview).containsKey("affectedDownstreamCount");

        // Downstream count should be 0 (null lineage repo)
        assertThat(preview).containsEntry("affectedDownstreamCount", 0);
      }
    }

    @Test
    void composeValidatePatchPreview_onError_returnsErrorMap() throws Exception {
      // When an exception occurs, the method should return a map with an error key
      String entityType = "table";
      String fqn = "db.schema.orders";
      String patchString = "invalid patch json";

      EntityInterface entity = mock(EntityInterface.class);
      when(entity.getFullyQualifiedName()).thenReturn(fqn);

      ObjectNode beforeNode = OBJECT_MAPPER.createObjectNode();
      beforeNode.put("name", "orders");

      // Don't mock JsonUtils — the real call will fail on invalid JSON
      Map<String, Object> preview =
          ApplyDataContractTool.composeValidatePatchPreview(
              entityType, fqn, patchString, entity, beforeNode);

      // Should contain an error key, not throw
      assertThat(preview).containsKey("error");
      assertThat(String.valueOf(preview.get("error"))).contains("Preview failed");
    }
  }

  // ====================== buildContract Unit Tests ======================

  @Nested
  class BuildContract {

    @Test
    void buildContract_extractsTierFromTags() {
      Table table = buildMockTable("db.schema.orders", "Test");
      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);
      assertThat(contract).containsEntry("tier", "Tier.Tier1");
    }

    @Test
    void buildContract_extractsClassificationTags() {
      Table table = buildMockTable("db.schema.orders", "Test");
      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      @SuppressWarnings("unchecked")
      List<String> tags = (List<String>) contract.get("tags");
      assertThat(tags).containsExactly("PII.Sensitive");
    }

    @Test
    void buildContract_extractsGlossaryTerms() {
      Table table = buildMockTable("db.schema.orders", "Test");
      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      @SuppressWarnings("unchecked")
      List<String> terms = (List<String>) contract.get("glossaryTerms");
      assertThat(terms).containsExactly("Glossary.Revenue");
    }

    @Test
    void buildContract_extractsSchemaColumns() {
      Table table = buildMockTable("db.schema.orders", "Test");
      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> schema = (List<Map<String, Object>>) contract.get("schema");
      assertThat(schema).hasSize(2);
      assertThat(schema.get(0)).containsEntry("name", "id");
      assertThat(schema.get(0)).containsEntry("type", "BIGINT");
      assertThat(schema.get(1)).containsEntry("name", "name");
      assertThat(schema.get(1)).containsEntry("type", "VARCHAR");
    }

    @Test
    void buildContract_noTags_omitsTagsAndTierAndGlossary() {
      Table table = mock(Table.class);
      when(table.getFullyQualifiedName()).thenReturn("db.schema.orders");
      when(table.getName()).thenReturn("orders");
      when(table.getDescription()).thenReturn("Test");
      when(table.getTags()).thenReturn(null);
      when(table.getOwners()).thenReturn(null);
      when(table.getDomains()).thenReturn(null);
      when(table.getExtension()).thenReturn(null);
      when(table.getColumns()).thenReturn(null);
      when(table.getTableConstraints()).thenReturn(null);
      when(table.getRetentionPeriod()).thenReturn(null);
      when(table.getSourceUrl()).thenReturn(null);
      when(table.getSchemaDefinition()).thenReturn(null);
      when(table.getDisplayName()).thenReturn(null);

      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      assertThat(contract).doesNotContainKey("tier");
      assertThat(contract).doesNotContainKey("tags");
      assertThat(contract).doesNotContainKey("glossaryTerms");
      assertThat(contract).doesNotContainKey("schema");
    }

    @Test
    void buildContract_withOwners_includesOwnerNames() {
      Table table = buildMockTableWithOwners("db.schema.orders", "Test", List.of("alice", "bob"));
      Map<String, Object> contract = GenerateDataContractTool.buildContract(table);

      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) contract.get("metadata");
      @SuppressWarnings("unchecked")
      List<String> owners = (List<String>) metadata.get("owners");
      assertThat(owners).containsExactly("alice", "bob");
    }
  }
}
