package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Stewardship Copilot — drafts a JSONPatch for ownership assignment, never applies it.
 *
 * <p>Returns a review-ready JSONPatch document that adds or replaces owners on the target entity.
 * The consumer (human or LLM agent) must explicitly call {@code patch_entity} to apply.
 *
 * <p>Spec reference: Expansions Group E5 (R5.1, R5.4).
 */
@Slf4j
public class DraftOwnershipPatchTool implements McpTool {

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
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext));
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
      McpEntityBridge.McpAuthorizer authorizer)
      throws IOException {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    // Parse owner specification
    Object ownerObj = params.get("owner");
    if (ownerObj == null) {
      throw new IllegalArgumentException(
          "Parameter 'owner' is required. Provide {name, type, id} or a name string.");
    }

    OwnerSpec ownerSpec = parseOwnerSpec(ownerObj);

    // Parse mode: "add" (append to existing owners) or "replace" (overwrite)
    String mode = (String) params.getOrDefault("mode", "add");
    if (!"add".equals(mode) && !"replace".equals(mode)) {
      throw new IllegalArgumentException(
          "Parameter 'mode' must be 'add' or 'replace', got: " + mode);
    }

    LOG.info(
        "Drafting ownership patch for {}/{}: owner={}, mode={}",
        entityType,
        fqn,
        ownerSpec.name,
        mode);

    // Step 1: Resolve the owner entity reference (user or team) by name
    OwnerEntityRef ownerEntityRef = resolveOwnerEntity(ownerSpec, referenceResolver);

    // Step 2: Build JSONPatch
    String jsonPatch = buildOwnershipPatch(ownerEntityRef, mode);

    // Warnings for unresolved owners
    List<String> warnings = new ArrayList<>();
    if (ownerEntityRef.id == null) {
      warnings.add(
          "ownerUnresolved: could not resolve '"
              + ownerSpec.name
              + "' to a UUID — patch may fail on apply");
    }

    // Step 3: Build response
    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("fqn", fqn);
    resultData.put("entityType", entityType);
    resultData.put("owner", ownerSpec.toMap());
    resultData.put("mode", mode);
    resultData.put("patch", jsonPatch);
    resultData.put("applied", false);
    resultData.put(
        "instruction",
        "Review the patch above. To apply, call patch_entity with entityType='"
            + entityType
            + "', fqn='"
            + fqn
            + "', and the patch value shown above.");

    // Narrative
    String narrative = generateNarrative(fqn, ownerSpec, mode);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(resultData)).narrative(narrative);

    Map<String, Object> result = new HashMap<>(envelope.build());
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("mode", mode);
    result.put("applied", false);
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
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "DraftOwnershipPatchTool does not support limits enforcement.");
  }

  // ====================== Owner spec parsing ======================

  /** Parses the owner parameter into a structured spec. */
  @VisibleForTesting
  static OwnerSpec parseOwnerSpec(Object ownerObj) {
    if (ownerObj instanceof Map<?, ?> ownerMap) {
      String name = (String) ownerMap.get("name");
      Object typeObj = ownerMap.get("type");
      String type = (typeObj instanceof String t) ? t : "user";
      String id = (String) ownerMap.get("id");
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException(
            "Owner 'name' is required when providing owner as an object.");
      }
      return new OwnerSpec(name, type, id);
    } else if (ownerObj instanceof String name && !name.isBlank()) {
      // Shorthand: just a name string, default type=user
      return new OwnerSpec(name, "user", null);
    } else {
      throw new IllegalArgumentException(
          "Parameter 'owner' must be a {name, type?, id?} object or a name string.");
    }
  }

  /** Resolves an owner name to a user/team entity reference with UUID. */
  private OwnerEntityRef resolveOwnerEntity(
      OwnerSpec ownerSpec, McpEntityBridge.EntityReferenceResolver referenceResolver) {
    // Try to resolve by name to get the UUID
    try {
      String entityType = "team".equalsIgnoreCase(ownerSpec.type) ? Entity.TEAM : Entity.USER;
      EntityReference ref =
          referenceResolver.getEntityReferenceByName(
              entityType, ownerSpec.name, Include.NON_DELETED);
      if (ref != null) {
        return new OwnerEntityRef(ownerSpec.name, ownerSpec.type, ref.getId().toString());
      }
    } catch (Exception e) {
      LOG.debug("Could not resolve owner '{}' by name: {}", ownerSpec.name, e.getMessage());
    }

    // If ID was provided directly, use it
    if (ownerSpec.id != null) {
      return new OwnerEntityRef(ownerSpec.name, ownerSpec.type, ownerSpec.id);
    }

    // Cannot resolve — return with null ID and a warning in the response
    LOG.warn("Could not resolve owner '{}' to a UUID. Patch may fail on apply.", ownerSpec.name);
    return new OwnerEntityRef(ownerSpec.name, ownerSpec.type, null);
  }

  // ====================== JSONPatch generation (R5.4) ======================

  /**
   * Builds a JSONPatch string for the ownership change.
   *
   * <p>Mode "add": appends the owner to the /owners array via {@code /owners/-}.
   * Mode "replace": replaces the entire /owners array with just this owner.
   */
  @VisibleForTesting
  static String buildOwnershipPatch(OwnerEntityRef ownerRef, String mode) {
    Map<String, Object> ownerValue = new LinkedHashMap<>();
    if (ownerRef.id != null) {
      ownerValue.put("id", ownerRef.id);
    }
    ownerValue.put("type", ownerRef.type);
    ownerValue.put("name", ownerRef.name);

    List<Map<String, Object>> patchOps = new ArrayList<>();

    if ("replace".equals(mode)) {
      // Replace entire owners array with just this owner
      Map<String, Object> op = new LinkedHashMap<>();
      op.put("op", "add");
      op.put("path", "/owners");
      op.put("value", List.of(ownerValue));
      patchOps.add(op);
    } else {
      // Append to existing owners array
      Map<String, Object> op = new LinkedHashMap<>();
      op.put("op", "add");
      op.put("path", "/owners/-");
      op.put("value", ownerValue);
      patchOps.add(op);
    }

    return JsonUtils.pojoToJson(patchOps);
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(String fqn, OwnerSpec ownerSpec, String mode) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Draft Ownership Patch\n\n");
    sb.append("**Entity:** `").append(fqn).append("`\n\n");
    sb.append("**Owner:** ")
        .append(ownerSpec.name)
        .append(" (")
        .append(ownerSpec.type)
        .append(")\n\n");
    sb.append("**Mode:** ")
        .append("add".equals(mode) ? "Add (append to existing)" : "Replace (overwrite existing)")
        .append("\n\n");
    sb.append(
        "⚠️ **This patch has NOT been applied.** Review it carefully, then use `patch_entity` to apply.\n\n");

    if ("replace".equals(mode)) {
      sb.append("**Warning:** Replace mode will remove all existing owners. ");
      sb.append("Use 'add' mode if you want to keep current owners.\n");
    }

    return sb.toString();
  }

  // ====================== Inner models ======================

  @VisibleForTesting
  static class OwnerSpec {
    final String name;
    final String type;
    final String id;

    OwnerSpec(String name, String type, String id) {
      this.name = name;
      this.type = type;
      this.id = id;
    }

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("name", name);
      map.put("type", type);
      if (id != null) map.put("id", id);
      return map;
    }
  }

  @VisibleForTesting
  static class OwnerEntityRef {
    final String name;
    final String type;
    final String id;

    OwnerEntityRef(String name, String type, String id) {
      this.name = name;
      this.type = type;
      this.id = id;
    }
  }
}
