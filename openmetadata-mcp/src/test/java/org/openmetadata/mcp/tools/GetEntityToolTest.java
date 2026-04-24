package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;

/**
 * Unit tests for GetEntityTool.
 *
 * <p>Tests verify:
 * - Successful entity lookup with fqn parameter
 * - fullyQualifiedName alias resolves correctly
 * - Missing fqn/fullyQualifiedName throws IllegalArgumentException
 * - Verbose fields are excluded from response
 *
 * <p>All tests use {@link McpEntityBridge} functional interfaces instead of {@code
 * mockStatic(Entity.class)}, eliminating the need to mock Entity static initializers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetEntityToolTest {

  private GetEntityTool tool;

  @BeforeEach
  void setUp() {
    tool = new GetEntityTool();
  }

  @Test
  void execute_withFqn_returnsEntityDetails() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");

    Object entityPojo = new Object();
    Map<String, Object> entityData =
        Map.of("name", "table", "fullyQualifiedName", "db.schema.table");
    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    // Inject functional interfaces — no mockStatic needed
    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
    McpEntityBridge.EntityFetcher fetcher = (entityType, fqn, fields, include) -> entityPojo;

    // Stub JsonUtils.getMap for the entity POJO
    Map<String, Object> result;
    try (var jsonMock = org.mockito.Mockito.mockStatic(JsonUtils.class)) {
      jsonMock.when(() -> JsonUtils.getMap(entityPojo)).thenReturn(entityData);

      result = tool.execute(params, resolver, noOpAuthorizer, fetcher);
    }

    assertThat(result).containsEntry("name", "table");
  }

  @Test
  void execute_withFullyQualifiedNameAlias_resolvesCorrectly() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fullyQualifiedName", "db.schema.table");

    Object entityPojo = new Object();
    Map<String, Object> entityData = Map.of("name", "table");
    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
    McpEntityBridge.EntityFetcher fetcher = (entityType, fqn, fields, include) -> entityPojo;

    Map<String, Object> result;
    try (var jsonMock = org.mockito.Mockito.mockStatic(JsonUtils.class)) {
      jsonMock.when(() -> JsonUtils.getMap(entityPojo)).thenReturn(entityData);

      result = tool.execute(params, resolver, noOpAuthorizer, fetcher);
    }

    assertThat(result).containsEntry("name", "table");
  }

  @Test
  void execute_missingFqnAndAlias_throwsIllegalArgumentException() {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");

    // Use safe lambda instead of defaultEntityReferenceResolver() — avoids calling Entity
    // static methods even though this test never reaches the resolver (throws at resolveFqn)
    assertThatThrownBy(
            () ->
                tool.execute(
                    params,
                    (entityType, fqn, include) -> null,
                    (entityType, op) -> {},
                    (entityType, fqn, fields, include) -> null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fqn")
        .hasMessageContaining("fullyQualifiedName");
  }

  @Test
  void execute_excludesVerboseFields() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");

    Object entityPojo = new Object();
    Map<String, Object> entityData = new HashMap<>();
    entityData.put("name", "table");
    entityData.put("version", "1.0");
    entityData.put("updatedAt", "2024-01-01");
    entityData.put("changeDescription", "something");
    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.McpAuthorizer noOpAuthorizer = (entityType, op) -> {};
    McpEntityBridge.EntityFetcher fetcher = (entityType, fqn, fields, include) -> entityPojo;

    Map<String, Object> result;
    try (var jsonMock = org.mockito.Mockito.mockStatic(JsonUtils.class)) {
      jsonMock.when(() -> JsonUtils.getMap(entityPojo)).thenReturn(entityData);

      result = tool.execute(params, resolver, noOpAuthorizer, fetcher);
    }

    assertThat(result).containsEntry("name", "table");
    assertThat(result).doesNotContainKey("version");
    assertThat(result).doesNotContainKey("updatedAt");
    assertThat(result).doesNotContainKey("changeDescription");
  }

  @Test
  void execute_authorizerIsCalledWithCorrectEntityType() throws IOException {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    // Capturing authorizer — verifies the entity type and operation
    String[] capturedEntityType = {null};
    MetadataOperation[] capturedOp = {null};
    McpEntityBridge.McpAuthorizer capturingAuthorizer =
        (entityType, op) -> {
          capturedEntityType[0] = entityType;
          capturedOp[0] = op;
        };

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;
    McpEntityBridge.EntityFetcher fetcher =
        (entityType, fqn, fields, include) -> Map.of("name", "table");

    try (var jsonMock = org.mockito.Mockito.mockStatic(JsonUtils.class)) {
      jsonMock
          .when(() -> JsonUtils.getMap(Map.of("name", "table")))
          .thenReturn(Map.of("name", "table"));

      tool.execute(params, resolver, capturingAuthorizer, fetcher);
    }

    assertThat(capturedEntityType[0]).isEqualTo("table");
    assertThat(capturedOp[0]).isEqualTo(MetadataOperation.VIEW_ALL);
  }

  @Test
  void execute_authorizerThrows_propagatesException() {
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.table");

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    McpEntityBridge.McpAuthorizer denyingAuthorizer =
        (entityType, op) -> {
          throw new RuntimeException("Permission denied: VIEW_ALL on " + entityType);
        };

    McpEntityBridge.EntityReferenceResolver resolver = (entityType, fqn, include) -> entityRef;

    assertThatThrownBy(
            () ->
                tool.execute(
                    params,
                    resolver,
                    denyingAuthorizer,
                    (entityType, fqn, fields, include) -> new Object()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Permission denied");
  }
}
