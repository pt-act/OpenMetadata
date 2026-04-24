package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonPatch;
import java.io.StringReader;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.change.ChangeSource;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

@Slf4j
public class PatchEntityTool implements McpTool {

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    return execute(
        params,
        securityContext,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultPatchAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultRepositoryProvider(),
        McpEntityBridge.defaultChangeEventPublisher());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity} static
   * method calls and {@link McpChangeEventUtil} calls, eliminating the need for {@code
   * mockStatic(Entity.class)} or {@code mockStatic(McpChangeEventUtil.class)}. Tests inject no-op
   * or stub lambdas for {@link McpEntityBridge.EntityReferenceResolver}, {@link
   * McpEntityBridge.PatchAuthorizer}, {@link McpEntityBridge.RepositoryProvider}, and {@link
   * McpEntityBridge.ChangeEventPublisher}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.PatchAuthorizer patchAuthorizer,
      McpEntityBridge.RepositoryProvider repoProvider,
      McpEntityBridge.ChangeEventPublisher changeEventPublisher) {
    String entityType = (String) params.get("entityType");
    // Use resolveEntityRef for multi-form entity identification (E1.5)
    // Supports: fqn, fullyQualifiedName, id, entityLink, name+service
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();
    String jsonPatchString = (String) params.get("patch");
    if (nullOrEmpty(jsonPatchString)) {
      throw new IllegalArgumentException("Patch cannot be null or empty");
    }

    JsonArray patchArray = Json.createReader(new StringReader(jsonPatchString)).readArray();
    JsonPatch jsonPatch = Json.createPatch(patchArray);

    // Validate If the User Can Perform the Patch Operation
    patchAuthorizer.authorize(entityType, jsonPatch, fqn);

    EntityRepository<? extends EntityInterface> repository =
        repoProvider.getEntityRepository(entityType);

    String userName = securityContext.getUserPrincipal().getName();
    String impersonatedBy = ImpersonationContext.getImpersonatedBy();
    RestUtil.PatchResponse<? extends EntityInterface> response =
        repository.patch(null, fqn, userName, jsonPatch, ChangeSource.MANUAL, null, impersonatedBy);
    changeEventPublisher.publishChangeEvent(response.entity(), response.changeType(), userName);
    return JsonUtils.convertValue(response, Map.class);
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException("PatchEntityTool does not support limits enforcement.");
  }
}
