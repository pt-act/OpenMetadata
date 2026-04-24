package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.type.change.ChangeSource;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

/**
 * Tests that PatchEntityTool correctly threads ImpersonationContext through to the repository
 * and publishes change events with the caller's userName.
 *
 * <p>All tests use {@link McpEntityBridge.EntityReferenceResolver}, {@link
 * McpEntityBridge.PatchAuthorizer}, {@link McpEntityBridge.RepositoryProvider}, and {@link
 * McpEntityBridge.ChangeEventPublisher} functional interfaces instead of {@code
 * mockStatic(Entity.class)} or {@code mockStatic(McpChangeEventUtil.class)}, eliminating the need
 * to mock Entity static initializers. The {@code Entity.getEntityReferenceByName()}, {@code
 * Entity.getEntityRepository()}, and {@code McpChangeEventUtil.publishChangeEvent()} calls are never
 * invoked because injected lambdas bypass them entirely.
 */
class PatchEntityToolTest {

  private CatalogSecurityContext securityContext;
  private Principal principal;

  @BeforeEach
  void setUp() {
    securityContext = mock(CatalogSecurityContext.class);
    principal = mock(Principal.class);
    when(principal.getName()).thenReturn("alice");
    when(securityContext.getUserPrincipal()).thenReturn(principal);
  }

  @AfterEach
  void clearImpersonationContext() {
    ImpersonationContext.clear();
  }

  @Test
  void execute_passesImpersonationContextToRepository() {
    ImpersonationContext.setImpersonatedBy("McpApplicationBot");

    @SuppressWarnings("unchecked")
    EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
    EntityInterface mockEntity = mock(EntityInterface.class);
    RestUtil.PatchResponse<EntityInterface> patchResponse =
        new RestUtil.PatchResponse<>(Response.Status.OK, mockEntity, EventType.ENTITY_UPDATED);
    when(mockRepo.patch(any(), any(String.class), any(), any(), any(), any(), any()))
        .thenReturn(patchResponse);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.test_table");

    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.test_table");
    params.put("patch", "[]");

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.EntityReferenceResolver referenceResolver =
        (entityType, fqn, include) -> entityRef;
    McpEntityBridge.PatchAuthorizer noOpAuthorizer = (entityType, jsonPatch, fqn1) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
      jsonMock
          .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
          .thenReturn(Map.of("status", "OK"));

      new PatchEntityTool()
          .execute(
              params,
              securityContext,
              referenceResolver,
              noOpAuthorizer,
              repoProvider,
              noOpPublisher);
    }

    ArgumentCaptor<String> impersonatedByCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockRepo)
        .patch(
            isNull(),
            any(String.class),
            eq("alice"),
            any(),
            eq(ChangeSource.MANUAL),
            isNull(),
            impersonatedByCaptor.capture());

    assertThat(impersonatedByCaptor.getValue())
        .as("impersonatedBy passed to repository must equal what was set in ImpersonationContext")
        .isEqualTo("McpApplicationBot");
  }

  @Test
  void execute_withNoImpersonationContext_passesNullImpersonatedBy() {
    @SuppressWarnings("unchecked")
    EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
    EntityInterface mockEntity = mock(EntityInterface.class);
    RestUtil.PatchResponse<EntityInterface> patchResponse =
        new RestUtil.PatchResponse<>(Response.Status.OK, mockEntity, EventType.ENTITY_UPDATED);
    when(mockRepo.patch(any(), any(String.class), any(), any(), any(), any(), any()))
        .thenReturn(patchResponse);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.test_table");

    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.test_table");
    params.put("patch", "[]");

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.EntityReferenceResolver referenceResolver =
        (entityType, fqn, include) -> entityRef;
    McpEntityBridge.PatchAuthorizer noOpAuthorizer = (entityType, jsonPatch, fqn1) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
      jsonMock
          .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
          .thenReturn(Map.of("status", "OK"));

      new PatchEntityTool()
          .execute(
              params,
              securityContext,
              referenceResolver,
              noOpAuthorizer,
              repoProvider,
              noOpPublisher);
    }

    ArgumentCaptor<String> impersonatedByCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockRepo)
        .patch(
            isNull(),
            any(String.class),
            eq("alice"),
            any(),
            eq(ChangeSource.MANUAL),
            isNull(),
            impersonatedByCaptor.capture());

    assertThat(impersonatedByCaptor.getValue())
        .as("impersonatedBy must be null when ImpersonationContext is not set")
        .isNull();
  }

  @Test
  void execute_publishesChangeEventWithCallerUserName() {
    ImpersonationContext.setImpersonatedBy("McpApplicationBot");

    @SuppressWarnings("unchecked")
    EntityRepository<EntityInterface> mockRepo = mock(EntityRepository.class);
    EntityInterface mockEntity = mock(EntityInterface.class);
    RestUtil.PatchResponse<EntityInterface> patchResponse =
        new RestUtil.PatchResponse<>(Response.Status.OK, mockEntity, EventType.ENTITY_UPDATED);
    when(mockRepo.patch(any(), any(String.class), any(), any(), any(), any(), any()))
        .thenReturn(patchResponse);

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.test_table");

    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.test_table");
    params.put("patch", "[]");

    // Capture change event publication
    AtomicReference<EntityInterface> capturedEntity = new AtomicReference<>();
    AtomicReference<EventType> capturedChangeType = new AtomicReference<>();
    AtomicReference<String> capturedUserName = new AtomicReference<>();

    McpEntityBridge.EntityReferenceResolver referenceResolver =
        (entityType, fqn, include) -> entityRef;
    McpEntityBridge.PatchAuthorizer noOpAuthorizer = (entityType, jsonPatch, fqn1) -> {};
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> mockRepo;
    McpEntityBridge.ChangeEventPublisher capturingPublisher =
        (entity, changeType, userName) -> {
          capturedEntity.set(entity);
          capturedChangeType.set(changeType);
          capturedUserName.set(userName);
        };

    try (MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {
      jsonMock
          .when(() -> JsonUtils.convertValue(any(), eq(Map.class)))
          .thenReturn(Map.of("status", "OK"));

      new PatchEntityTool()
          .execute(
              params,
              securityContext,
              referenceResolver,
              noOpAuthorizer,
              repoProvider,
              capturingPublisher);
    }

    assertThat(capturedEntity.get()).isSameAs(mockEntity);
    assertThat(capturedChangeType.get()).isEqualTo(EventType.ENTITY_UPDATED);
    assertThat(capturedUserName.get()).isEqualTo("alice");
  }

  @Test
  void execute_nullPatch_throwsIllegalArgumentException() {
    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.test_table");

    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fqn", "db.schema.test_table");
    params.put("patch", null);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.EntityReferenceResolver referenceResolver =
        (entityType, fqn, include) -> entityRef;
    McpEntityBridge.PatchAuthorizer noOpAuthorizer = (entityType, jsonPatch, fqn1) -> {};
    McpEntityBridge.RepositoryProvider repoProvider =
        (entityType) -> mock(org.openmetadata.service.jdbi3.EntityRepository.class);
    McpEntityBridge.ChangeEventPublisher noOpPublisher = (entity, changeType, userName) -> {};

    assertThatThrownBy(
            () ->
                new PatchEntityTool()
                    .execute(
                        params,
                        securityContext,
                        referenceResolver,
                        noOpAuthorizer,
                        repoProvider,
                        noOpPublisher))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Patch cannot be null or empty");
  }

  @Test
  void execute_withLimits_throwsUnsupportedOperationException() {
    assertThatThrownBy(() -> new PatchEntityTool().execute(null, null, securityContext, Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
