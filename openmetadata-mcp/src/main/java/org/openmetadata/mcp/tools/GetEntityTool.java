package org.openmetadata.mcp.tools;

import static org.openmetadata.schema.type.MetadataOperation.VIEW_ALL;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class GetEntityTool implements McpTool {

  // Fields to exclude from response to optimize LLM context usage
  // These fields are typically verbose and not useful for LLM understanding
  private static final List<String> EXCLUDE_FIELDS =
      List.of(
          "version",
          "updatedAt",
          "updatedBy",
          "changeDescription",
          "followers",
          "votes",
          "totalVotes",
          "usageSummary",
          "lifeCycle",
          "sourceHash",
          "fqnParts",
          "fqnHash",
          "entityRelationship",
          "processedLineage",
          "upstreamLineage",
          "changeSummary",
          "tierSources",
          "tagSources",
          "descriptionSources",
          "columnDescriptionStatus",
          "descriptionStatus",
          "embeddings",
          "extension");

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {
    return execute(
        params,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultEntityFetcher());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces to bypass {@link Entity}
   * static methods and {@code ResourceContext}/{@code OperationContext} construction. Tests
   * inject no-op authorizer and stub fetcher/resolver lambdas, eliminating the need for {@code
   * mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.EntityFetcher entityFetcher)
      throws IOException {
    String entityType = (String) params.get("entityType");
    // Use resolveEntityRef for multi-form entity identification (E1.5)
    // Supports: fqn, fullyQualifiedName, id, entityLink, name+service
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();
    authorizer.authorize(entityType, VIEW_ALL);
    LOG.info("Getting details for entity type: {}, FQN: {}", entityType, fqn);
    String fields = "*";
    Map<String, Object> entityData =
        JsonUtils.getMap(entityFetcher.getEntityByName(entityType, fqn, fields, null));

    // Clean response to optimize LLM context usage
    return cleanEntityResponse(entityData);
  }

  /**
   * Removes verbose fields from entity response to optimize LLM context. Keeps essential fields
   * while removing metadata that adds little value for LLM understanding.
   */
  private static Map<String, Object> cleanEntityResponse(Map<String, Object> entityData) {
    if (entityData == null) {
      return new HashMap<>();
    }
    Map<String, Object> cleaned = new HashMap<>(entityData);
    EXCLUDE_FIELDS.forEach(cleaned::remove);
    return cleaned;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException("GetEntityTool does not requires limit validation.");
  }
}
