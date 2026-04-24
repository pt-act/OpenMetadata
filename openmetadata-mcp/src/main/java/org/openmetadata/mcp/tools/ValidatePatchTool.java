package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.flipkart.zjsonpatch.JsonDiff;
import com.google.common.annotations.VisibleForTesting;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonPatch;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Dry-run Patch Validator — previews the effect of a JSON Patch without mutating state.
 *
 * <p>Accepts the same input shape as {@code patch_entity} and returns {@code
 * {beforeSnapshot, afterSnapshot, diff, affectedDownstreamCount, warnings[]}}.
 *
 * <p>Spec reference: Expansions Group E9 (R9.1–R9.4).
 */
@Slf4j
public class ValidatePatchTool implements McpTool {

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    return execute(
        params,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultPatchAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultRepositoryProvider(),
        McpEntityBridge.defaultLineageRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity}
   * static method calls and authorizer delegation, eliminating the need for {@code
   * mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.PatchAuthorizer authorizer,
      McpEntityBridge.RepositoryProvider repoProvider,
      McpEntityBridge.LineageRepositoryProvider lineageProvider) {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    String entityType = (String) params.get("entityType");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    String jsonPatchString = (String) params.get("patch");
    if (nullOrEmpty(jsonPatchString)) {
      throw new IllegalArgumentException("Patch cannot be null or empty");
    }

    // Authorize — defensive check: patch_entity delegates auth to repository.patch();
    // we check early since we never call repository.patch() (R9.1 parity, R9.4 non-mutation)
    JsonArray patchArray;
    try {
      patchArray = Json.createReader(new StringReader(jsonPatchString)).readArray();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON Patch: " + e.getMessage(), e);
    }
    JsonPatch jsonPatch = Json.createPatch(patchArray);
    authorizer.authorize(entityType, jsonPatch, fqn);

    LOG.info("validate_patch: dry-run for entity type={}, fqn={}", entityType, fqn);

    // Step 1: Load current entity (R9.3 — load current entity)
    EntityRepository<? extends EntityInterface> repository =
        repoProvider.getEntityRepository(entityType);
    EntityInterface originalEntity = repository.getByName(null, fqn, repository.getFields(".*"));

    // Step 2: Capture beforeSnapshot
    JsonNode beforeNode = JsonUtils.pojoToJsonNode(originalEntity);
    Map<String, Object> beforeSnapshot = JsonUtils.getMap(originalEntity);

    // Step 3: Apply patch in-memory — never writes back (R9.3, R9.4)
    JsonValue patchedJsonValue = JsonUtils.applyPatch(originalEntity, jsonPatch);
    String patchedJsonString = JsonUtils.pojoToJson(patchedJsonValue);
    JsonNode afterNode = JsonUtils.readTree(patchedJsonString);
    @SuppressWarnings("unchecked")
    Map<String, Object> afterSnapshot = JsonUtils.convertValue(afterNode, Map.class);

    // Step 4: Compute JSON diff between before and after (R9.3)
    JsonNode diffNode = JsonDiff.asJson(beforeNode, afterNode);
    List<Map<String, Object>> diff = new ArrayList<>();
    if (diffNode.isArray()) {
      for (JsonNode entry : diffNode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entryMap = JsonUtils.convertValue(entry, Map.class);
        diff.add(entryMap);
      }
    }

    // Step 5: Estimate affectedDownstreamCount via 1-hop lineage (R9.3)
    int affectedDownstreamCount = estimateDownstreamCount(entityType, fqn, lineageProvider);

    // Step 6: Generate semantic warnings for risky patches (R9.6)
    List<String> warnings = generateWarnings(beforeNode, afterNode, diffNode);

    // Build response
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("beforeSnapshot", beforeSnapshot);
    result.put("afterSnapshot", afterSnapshot);
    result.put("diff", diff);
    result.put("affectedDownstreamCount", affectedDownstreamCount);
    result.put(
        "affectedDownstreamCountNote",
        "1-hop lineage estimate, not exhaustive; use change_impact for full analysis");
    if (!warnings.isEmpty()) {
      result.put("warnings", warnings);
    }

    return result;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException(
        "ValidatePatchTool does not support limits enforcement.");
  }

  /**
   * Estimates downstream entity count via 1-hop lineage using the default {@link Entity} static
   * method. Production callers should prefer this overload.
   *
   * <p>Capped at 1-hop per spec R9.3 and documented in tools.json so the LLM doesn't treat it as
   * exhaustive.
   */
  @VisibleForTesting
  static int estimateDownstreamCount(String entityType, String fqn) {
    return estimateDownstreamCount(entityType, fqn, Entity::getLineageRepository);
  }

  /**
   * Test-friendly overload — accepts a {@link McpEntityBridge.LineageRepositoryProvider} to
   * eliminate the need for {@code mockStatic(Entity.class)}. Tests inject a lambda that returns
   * null or a mock lineage repository.
   */
  @VisibleForTesting
  static int estimateDownstreamCount(
      String entityType, String fqn, McpEntityBridge.LineageRepositoryProvider lineageProvider) {
    try {
      var lineageRepo = lineageProvider.getLineageRepository();
      if (lineageRepo == null) {
        LOG.warn("Lineage repository not initialized — cannot estimate downstream for '{}'", fqn);
        return 0;
      }
      EntityLineage lineage = lineageRepo.getByName(entityType, fqn, 0, 1);
      return countDownstream(lineage, fqn);
    } catch (Exception e) {
      LOG.warn(
          "Failed to estimate downstream count for '{}': {}",
          fqn,
          e.getClass().getSimpleName() + ": " + e.getMessage());
      return 0;
    }
  }

  /**
   * Counts downstream entities in the lineage result, excluding the source entity.
   *
   * <p>Works directly on {@link EntityLineage#getNodes()} — no Map serialization round-trip.
   */
  @VisibleForTesting
  static int countDownstream(EntityLineage lineage, String sourceFqn) {
    if (lineage == null || lineage.getNodes() == null) {
      return 0;
    }
    return (int)
        lineage.getNodes().stream()
            .filter(ref -> ref != null && !sourceFqn.equals(ref.getFullyQualifiedName()))
            .count();
  }

  /**
   * Generates semantic warnings for risky patches.
   *
   * <p>Detects:
   * <ul>
   *   <li>Removal of all owners ({@code ownerRemoval})
   *   <li>Removal of tier ({@code tierRemoval})
   *   <li>Mass tag removal (≥5 tags removed, {@code massTagRemoval})
   *   <li>Description cleared ({@code descriptionCleared})
   * </ul>
   */
  @VisibleForTesting
  static List<String> generateWarnings(JsonNode beforeNode, JsonNode afterNode, JsonNode diffNode) {
    List<String> warnings = new ArrayList<>();

    // Check for owner removal: owners present before but absent/empty after
    if (hasOwners(beforeNode) && !hasOwners(afterNode)) {
      warnings.add("ownerRemoval: patch removes all owners from this entity");
    }

    // Check for tier removal: tier present before but absent after
    if (hasTier(beforeNode) && !hasTier(afterNode)) {
      warnings.add("tierRemoval: patch removes tier classification from this entity");
    }

    // Check for mass tag removal: count tag removals in the diff
    int tagRemovals = 0;
    if (diffNode.isArray()) {
      for (JsonNode entry : diffNode) {
        String op = entry.path("op").asText("");
        String path = entry.path("path").asText("");
        if ("remove".equals(op) && path.contains("/tags/")) {
          tagRemovals++;
        }
      }
    }
    if (tagRemovals >= 5) {
      warnings.add(
          String.format("massTagRemoval: patch removes %d tags (≥5), verify intent", tagRemovals));
    }

    // Check for description cleared: description present before but empty/absent after
    if (hasDescription(beforeNode) && !hasDescription(afterNode)) {
      warnings.add("descriptionCleared: patch clears the entity description");
    }

    return warnings;
  }

  private static boolean hasOwners(JsonNode node) {
    JsonNode owners = node.path("owners");
    return owners.isArray() && !owners.isEmpty();
  }

  private static boolean hasTier(JsonNode node) {
    JsonNode tier = node.path("tier");
    return !tier.isMissingNode() && !tier.isNull();
  }

  private static boolean hasDescription(JsonNode node) {
    JsonNode desc = node.path("description");
    return !desc.isMissingNode() && !desc.isNull() && !desc.asText("").isBlank();
  }
}
