package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class GetLineageTool implements McpTool {

  // Defaults matching ai-platform GetLineageTool.kt for consistency
  private static final int DEFAULT_DEPTH = 3;
  // Maximum depth to prevent exponential response growth (lineage graphs can explode)
  private static final int MAX_DEPTH = 10;

  /**
   * Production call — creates default bridge interfaces that delegate to {@link
   * org.openmetadata.service.Entity} static methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    return execute(
        params,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultLineageRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link
   * org.openmetadata.service.Entity} static method calls and authorizer delegation, eliminating
   * the need for {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.LineageRepositoryProvider lineageProvider) {
    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }
    String entityType = (String) params.get("entityType");
    if (nullOrEmpty(entityType)) {
      throw new IllegalArgumentException("Parameter 'entityType' is required");
    }

    // Use resolveEntityRef for multi-form entity identification (E1.5)
    // Supports: fqn, fullyQualifiedName, id, entityLink, name+service
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    // Parse and validate upstream depth with default and max limits
    int upstreamDepth = parseDepthParameter(params.get("upstreamDepth"), DEFAULT_DEPTH);
    // Parse and validate downstream depth with default and max limits
    int downstreamDepth = parseDepthParameter(params.get("downstreamDepth"), DEFAULT_DEPTH);

    LOG.info(
        "Getting lineage for entity type: {}, FQN: {}, upstreamDepth: {}, downstreamDepth: {}",
        entityType,
        fqn,
        upstreamDepth,
        downstreamDepth);

    var lineageRepo = lineageProvider.getLineageRepository();
    if (lineageRepo == null) {
      LOG.warn("Lineage repository not initialized — cannot get lineage for '{}'", fqn);
      EnvelopeBuilder envelope =
          EnvelopeBuilder.create()
              .results(List.of())
              .narrative("Could not retrieve lineage: lineage repository not initialized.");
      Map<String, Object> result = new HashMap<>(envelope.build());
      result.put("entityType", entityType);
      result.put("fqn", fqn);
      result.put("upstreamDepth", upstreamDepth);
      result.put("downstreamDepth", downstreamDepth);
      result.put("error", "Lineage repository not initialized");
      return result;
    }

    Map<String, Object> lineageData =
        JsonUtils.getMap(lineageRepo.getByName(entityType, fqn, upstreamDepth, downstreamDepth));

    // Wrap in envelope for consistency with other MCP tools (E1.8)
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(lineageData != null ? List.of(lineageData) : List.of());
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("entityType", entityType);
    result.put("fqn", fqn);
    result.put("upstreamDepth", upstreamDepth);
    result.put("downstreamDepth", downstreamDepth);
    return result;
  }

  /**
   * Parses depth parameter with default value and enforces maximum limit to prevent excessive
   * response sizes that could overwhelm LLM context.
   */
  private static int parseDepthParameter(Object depthObj, int defaultValue) {
    if (depthObj == null) {
      return Math.min(Math.max(defaultValue, 0), MAX_DEPTH);
    }
    int depth = defaultValue;
    if (depthObj instanceof Number number) {
      depth = number.intValue();
    } else if (depthObj instanceof String string) {
      try {
        depth = Integer.parseInt(string);
      } catch (NumberFormatException e) {
        depth = defaultValue;
      }
    }
    // Clamp to 0..MAX_DEPTH — 0 disables that direction, enabling directional-only queries
    return Math.min(Math.max(depth, 0), MAX_DEPTH);
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException("GetLineageTool does not support limits enforcement.");
  }
}
