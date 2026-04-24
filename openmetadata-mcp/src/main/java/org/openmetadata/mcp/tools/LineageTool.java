package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.lineage.AddLineage;
import org.openmetadata.schema.type.EntitiesEdge;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class LineageTool implements McpTool {
  /**
   * Production call — creates default bridge interfaces that delegate to {@link
   * org.openmetadata.service.Entity} static methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> params) {
    return execute(
        params,
        McpEntityBridge.defaultAuthorizer(authorizer, catalogSecurityContext),
        McpEntityBridge.defaultLineageRepositoryProvider(),
        catalogSecurityContext.getUserPrincipal().getName());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link
   * org.openmetadata.service.Entity} static method calls and authorizer delegation, eliminating
   * the need for {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.LineageRepositoryProvider lineageProvider,
      String updatedBy) {
    EntityReference fromEntity =
        JsonUtils.convertValue(params.get("fromEntity"), EntityReference.class);
    EntityReference toEntity =
        JsonUtils.convertValue(params.get("toEntity"), EntityReference.class);

    if (fromEntity == null || fromEntity.getType() == null || fromEntity.getId() == null) {
      throw new IllegalArgumentException(
          "Parameter 'fromEntity' is required and must include 'type' and 'id'");
    }
    if (toEntity == null || toEntity.getType() == null || toEntity.getId() == null) {
      throw new IllegalArgumentException(
          "Parameter 'toEntity' is required and must include 'type' and 'id'");
    }

    authorizer.authorize(fromEntity.getType(), MetadataOperation.EDIT_LINEAGE);
    authorizer.authorize(toEntity.getType(), MetadataOperation.EDIT_LINEAGE);

    LOG.info(
        "Creating lineage edge from {}.{} to {}.{}",
        fromEntity.getType(),
        fromEntity.getName(),
        toEntity.getType(),
        toEntity.getName());

    var lineageRepo = lineageProvider.getLineageRepository();
    if (lineageRepo == null) {
      LOG.warn("Lineage repository not initialized — cannot add lineage edge");
      Map<String, Object> errorResult = new HashMap<>();
      errorResult.put("error", "Lineage repository not initialized");
      EnvelopeBuilder envelope =
          EnvelopeBuilder.create()
              .results(List.of(errorResult))
              .narrative("Could not create lineage edge: lineage repository not initialized.");
      Map<String, Object> result = new HashMap<>(envelope.build());
      // Backward-compat fields kept for existing consumers (same as success path)
      result.put("fromEntity", Map.of("type", fromEntity.getType(), "name", fromEntity.getName()));
      result.put("toEntity", Map.of("type", toEntity.getType(), "name", toEntity.getName()));
      return result;
    }

    AddLineage lineage =
        new AddLineage()
            .withEdge(new EntitiesEdge().withFromEntity(fromEntity).withToEntity(toEntity));
    lineageRepo.addLineage(lineage, updatedBy);

    return buildLineageResponse(fromEntity, toEntity);
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> map)
      throws IOException {
    throw new UnsupportedOperationException("LineageTool does not require limit validation.");
  }

  /**
   * Builds the lineage creation response envelope. Extracted as a static method for unit testing
   * since LineageRepository has a static initializer that requires a running search client.
   *
   * @param fromEntity the source entity reference
   * @param toEntity the target entity reference
   * @return envelope map with results, narrative, and backward-compat fields
   */
  @VisibleForTesting
  static Map<String, Object> buildLineageResponse(
      EntityReference fromEntity, EntityReference toEntity) {
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(List.of())
            .narrative(
                String.format(
                    "Created lineage edge from %s.%s to %s.%s.",
                    fromEntity.getType(),
                    fromEntity.getName(),
                    toEntity.getType(),
                    toEntity.getName()));
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("fromEntity", Map.of("type", fromEntity.getType(), "name", fromEntity.getName()));
    result.put("toEntity", Map.of("type", toEntity.getType(), "name", toEntity.getName()));
    return result;
  }
}
