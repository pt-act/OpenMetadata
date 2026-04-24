package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.flipkart.zjsonpatch.JsonDiff;
import com.google.common.annotations.VisibleForTesting;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonPatch;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.change.ChangeSource;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

/**
 * Data Contract Applier — parses a YAML contract and computes/applies required patches.
 *
 * <p>Takes a data contract YAML (as produced by {@code generate_data_contract}), computes the
 * JSON Patch operations needed to bring the live entity into conformance with the contract, and
 * optionally applies them with rollback support.
 *
 * <p>Spec reference: Expansions Group E7 (R7.2–R7.7).
 */
@Slf4j
public class ApplyDataContractTool implements McpTool {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

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
        securityContext,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultRepositoryProvider(),
        McpEntityBridge.defaultChangeEventPublisher());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity} static
   * method calls and {@link McpChangeEventUtil} calls, eliminating the need for {@code
   * mockStatic(Entity.class)} or {@code mockStatic(McpChangeEventUtil.class)}. Tests inject no-op
   * or stub lambdas for {@link McpEntityBridge.EntityReferenceResolver}, {@link
   * McpEntityBridge.McpAuthorizer}, {@link McpEntityBridge.RepositoryProvider}, and {@link
   * McpEntityBridge.ChangeEventPublisher}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.RepositoryProvider repoProvider,
      McpEntityBridge.ChangeEventPublisher changeEventPublisher)
      throws IOException {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    // Parse the contract YAML
    String contractYaml = (String) params.get("contractYaml");
    if (nullOrEmpty(contractYaml)) {
      throw new IllegalArgumentException("Parameter 'contractYaml' is required");
    }

    Map<String, Object> contract = parseContractYaml(contractYaml);

    // Extract FQN from contract metadata
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) contract.get("metadata");
    if (metadata == null || metadata.get("fqn") == null) {
      throw new IllegalArgumentException(
          "Contract must contain 'metadata.fqn' identifying the target entity");
    }

    String fqn = (String) metadata.get("fqn");
    String entityType = (String) params.getOrDefault("entityType", "table");

    // Parse dryRun flag (default: true — safe preview mode per R7.5)
    boolean dryRun =
        params.get("dryRun") instanceof Boolean b
            ? b
            : params.get("dryRun") instanceof String s ? Boolean.parseBoolean(s) : true;

    // Parse createIfMissing flag (default: false per R7.7)
    boolean createIfMissing =
        params.get("createIfMissing") instanceof Boolean b
            ? b
            : params.get("createIfMissing") instanceof String s ? Boolean.parseBoolean(s) : false;

    LOG.info(
        "apply_data_contract: fqn={}, dryRun={}, createIfMissing={}", fqn, dryRun, createIfMissing);

    // Resolve the target entity
    EntityInterface entity;
    EntityRepository<? extends EntityInterface> repository =
        repoProvider.getEntityRepository(entityType);
    try {
      entity = repository.getByName(null, fqn, repository.getFields(".*"));
    } catch (EntityNotFoundException e) {
      if (createIfMissing) {
        // R7.7: createIfMissing=true — create the entity from contract metadata
        // For now, emit a structured result indicating creation is needed.
        // Full entity creation is deferred to a follow-up iteration since
        // it requires service/schema resolution and is high-risk.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn", fqn);
        result.put("entityType", entityType);
        result.put("dryRun", dryRun);
        result.put("status", "entityNotFound");
        result.put(
            "message",
            "Entity '"
                + fqn
                + "' not found. createIfMissing=true, but automatic entity "
                + "creation from contract is not yet supported. Create the entity first, "
                + "then re-apply the contract.");
        result.put(
            "narrative",
            "## Entity Not Found\n\nThe contract references `"
                + fqn
                + "`, which does not exist in the catalog. "
                + "Create the entity first, then re-apply the contract.\n");
        return result;
      } else {
        throw new IllegalArgumentException(
            "Entity '" + fqn + "' not found. Set createIfMissing=true to allow creation.");
      }
    }

    // Authorize edit access
    authorizer.authorize(entityType, MetadataOperation.EDIT_ALL);

    // Step 1: Build the desired state from the contract
    Map<String, Object> desiredState = buildDesiredState(contract, entity, referenceResolver);

    // Step 2: Compute the JSON Patch (diff from current to desired)
    JsonNode beforeNode = JsonUtils.pojoToJsonNode(entity);
    JsonNode desiredNode = JsonUtils.getObjectMapper().valueToTree(desiredState);
    JsonNode diffNode = JsonDiff.asJson(beforeNode, desiredNode);

    List<Map<String, Object>> changePlan = new ArrayList<>();
    if (diffNode.isArray()) {
      for (JsonNode entry : diffNode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entryMap = JsonUtils.convertValue(entry, Map.class);
        changePlan.add(entryMap);
      }
    }

    // Step 3: If no changes needed, return early
    if (changePlan.isEmpty()) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("fqn", fqn);
      result.put("entityType", entityType);
      result.put("dryRun", dryRun);
      result.put("status", "noChangesNeeded");
      result.put("changePlan", List.of());
      result.put("applied", List.of());
      result.put("rolledBack", List.of());
      result.put("failed", List.of());
      result.put(
          "narrative",
          "## Contract Already Conforms\n\nEntity `"
              + fqn
              + "` already matches the contract. No changes needed.\n");
      return result;
    }

    // Step 4: Dry-run preview using validate_patch (R7.4)
    if (dryRun) {
      // For dry-run, compose a validate_patch call for each change
      String patchString = JsonUtils.pojoToJson(changePlan);
      Map<String, Object> validationPreview =
          composeValidatePatchPreview(entityType, fqn, patchString, entity, beforeNode);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("fqn", fqn);
      result.put("entityType", entityType);
      result.put("dryRun", true);
      result.put("status", "preview");
      result.put("changePlan", changePlan);
      result.put("changeCount", changePlan.size());
      result.put("validationPreview", validationPreview);
      result.put("applied", List.of());
      result.put("rolledBack", List.of());
      result.put("failed", List.of());
      result.put("narrative", generateNarrative(fqn, changePlan.size(), true));
      return result;
    }

    // Step 5: Apply with rollback support (R7.6)
    List<Map<String, Object>> applied = new ArrayList<>();
    List<Map<String, Object>> rolledBack = new ArrayList<>();
    List<Map<String, Object>> failed = new ArrayList<>();

    try {
      String patchString = JsonUtils.pojoToJson(changePlan);
      JsonArray patchArray = Json.createReader(new StringReader(patchString)).readArray();
      JsonPatch jsonPatch = Json.createPatch(patchArray);

      String userName = securityContext.getUserPrincipal().getName();
      String impersonatedBy = ImpersonationContext.getImpersonatedBy();

      RestUtil.PatchResponse<? extends EntityInterface> response =
          repository.patch(
              null, fqn, userName, jsonPatch, ChangeSource.MANUAL, null, impersonatedBy);
      changeEventPublisher.publishChangeEvent(response.entity(), response.changeType(), userName);

      // All changes applied successfully
      for (Map<String, Object> change : changePlan) {
        Map<String, Object> appliedEntry = new LinkedHashMap<>(change);
        appliedEntry.put("status", "applied");
        applied.add(appliedEntry);
      }

    } catch (Exception e) {
      LOG.error("apply_data_contract: partial failure for '{}': {}", fqn, e.getMessage(), e);

      // R7.6: On any failure, attempt rollback of already-applied changes
      // Since JSON Patch is atomic at the repository level (single patch call),
      // either all ops succeed or the whole patch fails.
      // In the failure case, the entity should still be in its original state.
      for (Map<String, Object> change : changePlan) {
        Map<String, Object> failedEntry = new LinkedHashMap<>(change);
        failedEntry.put("status", "failed");
        failedEntry.put("error", e.getMessage());
        failed.add(failedEntry);
      }

      // No partial rollback needed — repository.patch() is atomic
      LOG.info("apply_data_contract: patch was atomic, no partial state to roll back");
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("dryRun", false);
    result.put("status", failed.isEmpty() ? "applied" : "failed");
    result.put("changePlan", changePlan);
    result.put("changeCount", changePlan.size());
    result.put("applied", applied);
    result.put("rolledBack", rolledBack);
    result.put("failed", failed);
    result.put("narrative", generateNarrative(fqn, changePlan.size(), false));

    if (!failed.isEmpty()) {
      result.put(
          "rollbackNote",
          "repository.patch() is atomic — either all changes apply or none do. "
              + "No inconsistent catalog state is possible.");
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
        "ApplyDataContractTool does not support limits enforcement.");
  }

  /**
   * Parses a YAML contract string into a Map.
   */
  @VisibleForTesting
  static Map<String, Object> parseContractYaml(String contractYaml) {
    try {
      return YAML_MAPPER.readValue(contractYaml, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid contract YAML: " + e.getMessage(), e);
    }
  }

  /**
   * Builds the desired entity state from the contract, preserving fields not mentioned in the
   * contract. This allows partial contracts that only specify the fields they want to change.
   *
   * @param contract the parsed contract YAML map
   * @param currentEntity the current entity from the catalog
   * @param referenceResolver resolves entity references by name — tests inject stubs to bypass
   *     {@code Entity.getEntityReferenceByName()}
   */
  @VisibleForTesting
  @SuppressWarnings("unchecked")
  static Map<String, Object> buildDesiredState(
      Map<String, Object> contract,
      EntityInterface currentEntity,
      McpEntityBridge.EntityReferenceResolver referenceResolver) {

    // Start from current entity as a Map to preserve unchanged fields
    Map<String, Object> desired = new LinkedHashMap<>(JsonUtils.getMap(currentEntity));

    Map<String, Object> metadata = (Map<String, Object>) contract.get("metadata");

    // Apply metadata fields
    if (metadata != null) {
      // Description
      if (metadata.containsKey("description")) {
        desired.put("description", metadata.get("description"));
      }

      // DisplayName
      if (metadata.containsKey("displayName")) {
        desired.put("displayName", metadata.get("displayName"));
      }

      // Owners — resolve owner names to EntityReference objects
      if (metadata.containsKey("owners")) {
        Object ownersObj = metadata.get("owners");
        if (ownersObj instanceof List<?> ownerNames && !ownerNames.isEmpty()) {
          List<Map<String, Object>> ownerRefs = new ArrayList<>();
          for (Object ownerNameObj : ownerNames) {
            String ownerName = ownerNameObj.toString();
            EntityReference ref = resolveOwnerRef(ownerName, referenceResolver);
            if (ref != null) {
              Map<String, Object> refMap = new LinkedHashMap<>();
              refMap.put("id", ref.getId().toString());
              refMap.put("type", ref.getType());
              refMap.put("name", ref.getName());
              ownerRefs.add(refMap);
            } else {
              // Owner not found — include a placeholder with a warning
              Map<String, Object> refMap = new LinkedHashMap<>();
              refMap.put("name", ownerName);
              refMap.put("type", "user");
              ownerRefs.add(refMap);
            }
          }
          desired.put("owners", ownerRefs);
        } else if (ownersObj instanceof List<?> && ((List<?>) ownersObj).isEmpty()) {
          // Empty owners list — explicitly remove owners
          desired.remove("owners");
        }
      }

      // Domains — resolve domain names to EntityReference objects
      if (metadata.containsKey("domains")) {
        Object domainsObj = metadata.get("domains");
        if (domainsObj instanceof List<?> domainFqns && !domainFqns.isEmpty()) {
          List<Map<String, Object>> domainRefs = new ArrayList<>();
          for (Object domainFqnObj : domainFqns) {
            String domainFqn = domainFqnObj.toString();
            EntityReference ref = resolveEntityRefByName("domain", domainFqn, referenceResolver);
            if (ref != null) {
              Map<String, Object> refMap = new LinkedHashMap<>();
              refMap.put("id", ref.getId().toString());
              refMap.put("type", ref.getType());
              refMap.put("name", ref.getName());
              refMap.put("fullyQualifiedName", ref.getFullyQualifiedName());
              domainRefs.add(refMap);
            }
          }
          desired.put("domains", domainRefs);
        }
      }
    }

    // Tier — add as a tag
    if (contract.containsKey("tier")) {
      String tierFqn = (String) contract.get("tier");
      addOrReplaceTierTag(desired, tierFqn);
    }

    // Tags — add classification tags
    if (contract.containsKey("tags")) {
      Object tagsObj = contract.get("tags");
      if (tagsObj instanceof List<?> tagFqns) {
        addOrReplaceClassificationTags(desired, tagFqns.stream().map(Object::toString).toList());
      }
    }

    // Glossary terms — add glossary term tags
    if (contract.containsKey("glossaryTerms")) {
      Object termsObj = contract.get("glossaryTerms");
      if (termsObj instanceof List<?> termFqns) {
        addOrReplaceGlossaryTerms(desired, termFqns.stream().map(Object::toString).toList());
      }
    }

    // Table-specific fields
    if (contract.containsKey("retentionPeriod")) {
      desired.put("retentionPeriod", contract.get("retentionPeriod"));
    }

    if (contract.containsKey("sourceUrl")) {
      desired.put("sourceUrl", contract.get("sourceUrl"));
    }

    if (contract.containsKey("schemaDefinition")) {
      desired.put("schemaDefinition", contract.get("schemaDefinition"));
    }

    // Extension
    if (contract.containsKey("extension")) {
      desired.put("extension", contract.get("extension"));
    }

    return desired;
  }

  /**
   * Composes a validate_patch preview for the given patch string.
   * Reuses ValidatePatchTool's logic for the dry-run diff preview (R7.4).
   *
   * <p>Production callers should use {@link #composeValidatePatchPreview(String, String, String,
   * EntityInterface, JsonNode)} which delegates to {@link McpEntityBridge#defaultLineageRepositoryProvider()}.
   */
  @VisibleForTesting
  static Map<String, Object> composeValidatePatchPreview(
      String entityType,
      String fqn,
      String patchString,
      EntityInterface entity,
      JsonNode beforeNode) {
    return composeValidatePatchPreview(
        entityType,
        fqn,
        patchString,
        entity,
        beforeNode,
        McpEntityBridge.defaultLineageRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts a {@link McpEntityBridge.LineageRepositoryProvider} to
   * eliminate the need for {@code mockStatic(Entity.class)}. Tests inject a lambda that returns
   * null to bypass the lineage repository lookup entirely.
   */
  @VisibleForTesting
  static Map<String, Object> composeValidatePatchPreview(
      String entityType,
      String fqn,
      String patchString,
      EntityInterface entity,
      JsonNode beforeNode,
      McpEntityBridge.LineageRepositoryProvider lineageProvider) {
    try {
      // Apply patch in-memory for preview (never writes back)
      // Follows the same pattern as ValidatePatchTool: apply to EntityInterface, not JsonNode
      JsonArray patchArray = Json.createReader(new StringReader(patchString)).readArray();
      jakarta.json.JsonPatch jsonPatch = Json.createPatch(patchArray);
      jakarta.json.JsonValue patchedValue = JsonUtils.applyPatch(entity, jsonPatch);
      String patchedJson = JsonUtils.pojoToJson(patchedValue);
      JsonNode afterNode = JsonUtils.readTree(patchedJson);

      // Compute diff
      JsonNode diffNode = JsonDiff.asJson(beforeNode, afterNode);
      List<Map<String, Object>> diff = new ArrayList<>();
      if (diffNode.isArray()) {
        for (JsonNode entry : diffNode) {
          @SuppressWarnings("unchecked")
          Map<String, Object> entryMap = JsonUtils.convertValue(entry, Map.class);
          diff.add(entryMap);
        }
      }

      // Generate warnings
      List<String> warnings = ValidatePatchTool.generateWarnings(beforeNode, afterNode, diffNode);

      // Estimate downstream count using the injected lineage provider
      int downstreamCount =
          ValidatePatchTool.estimateDownstreamCount(entityType, fqn, lineageProvider);

      Map<String, Object> preview = new LinkedHashMap<>();
      preview.put("beforeSnapshot", JsonUtils.convertValue(beforeNode, Map.class));
      preview.put("afterSnapshot", JsonUtils.convertValue(afterNode, Map.class));
      preview.put("diff", diff);
      preview.put("affectedDownstreamCount", downstreamCount);
      if (!warnings.isEmpty()) {
        preview.put("warnings", warnings);
      }
      return preview;

    } catch (Exception e) {
      LOG.warn("composeValidatePatchPreview failed: {}", e.getMessage());
      Map<String, Object> preview = new LinkedHashMap<>();
      preview.put("error", "Preview failed: " + e.getMessage());
      return preview;
    }
  }

  // ====================== Helper methods ======================

  /** Resolves an owner name to an EntityReference (user or team). */
  private static EntityReference resolveOwnerRef(
      String ownerName, McpEntityBridge.EntityReferenceResolver referenceResolver) {
    // Try user first
    EntityReference ref = resolveEntityRefByName("user", ownerName, referenceResolver);
    if (ref != null) return ref;
    // Then try team
    return resolveEntityRefByName("team", ownerName, referenceResolver);
  }

  /** Resolves an entity by name, returning null if not found (never throws). */
  private static EntityReference resolveEntityRefByName(
      String entityType, String name, McpEntityBridge.EntityReferenceResolver referenceResolver) {
    try {
      return referenceResolver.getEntityReferenceByName(entityType, name, Include.NON_DELETED);
    } catch (Exception e) {
      LOG.debug("Could not resolve {} '{}': {}", entityType, name, e.getMessage());
      return null;
    }
  }

  /** Adds or replaces the tier tag in the desired state's tags list. */
  @SuppressWarnings("unchecked")
  private static void addOrReplaceTierTag(Map<String, Object> desired, String tierFqn) {
    List<Map<String, Object>> tags = getOrCreateTagsList(desired);

    // Remove existing tier tag
    tags.removeIf(
        t -> {
          Object tagFqn = t.get("tagFQN");
          return tagFqn != null && tagFqn.toString().startsWith("Tier.");
        });

    // Add new tier tag
    Map<String, Object> tierTag = new LinkedHashMap<>();
    tierTag.put("tagFQN", tierFqn);
    tierTag.put("name", tierFqn.substring(tierFqn.lastIndexOf('.') + 1));
    tierTag.put("source", TagLabel.TagSource.CLASSIFICATION.value());
    tierTag.put("labelType", "Manual");
    tierTag.put("state", "Confirmed");
    tags.add(tierTag);

    desired.put("tags", tags);
  }

  /** Adds or replaces classification tags (non-tier, non-glossary). */
  @SuppressWarnings("unchecked")
  private static void addOrReplaceClassificationTags(
      Map<String, Object> desired, List<String> tagFqns) {
    List<Map<String, Object>> tags = getOrCreateTagsList(desired);

    // Remove existing classification tags (non-Tier, non-Glossary)
    tags.removeIf(
        t -> {
          Object tagFqn = t.get("tagFQN");
          Object source = t.get("source");
          return tagFqn != null
              && !tagFqn.toString().startsWith("Tier.")
              && !TagLabel.TagSource.GLOSSARY.value().equals(String.valueOf(source));
        });

    // Add new classification tags
    for (String tagFqn : tagFqns) {
      Map<String, Object> tag = new LinkedHashMap<>();
      tag.put("tagFQN", tagFqn);
      tag.put("name", tagFqn.substring(tagFqn.lastIndexOf('.') + 1));
      tag.put("source", TagLabel.TagSource.CLASSIFICATION.value());
      tag.put("labelType", "Manual");
      tag.put("state", "Confirmed");
      tags.add(tag);
    }

    desired.put("tags", tags);
  }

  /** Adds or replaces glossary term tags. */
  @SuppressWarnings("unchecked")
  private static void addOrReplaceGlossaryTerms(
      Map<String, Object> desired, List<String> termFqns) {
    List<Map<String, Object>> tags = getOrCreateTagsList(desired);

    // Remove existing glossary tags
    tags.removeIf(t -> TagLabel.TagSource.GLOSSARY.value().equals(String.valueOf(t.get("source"))));

    // Add new glossary term tags
    for (String termFqn : termFqns) {
      Map<String, Object> term = new LinkedHashMap<>();
      term.put("tagFQN", termFqn);
      term.put("name", termFqn.substring(termFqn.lastIndexOf('.') + 1));
      term.put("source", TagLabel.TagSource.GLOSSARY.value());
      term.put("labelType", "Manual");
      term.put("state", "Confirmed");
      tags.add(term);
    }

    desired.put("tags", tags);
  }

  /** Gets the existing tags list from the desired state, or creates one if absent. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> getOrCreateTagsList(Map<String, Object> desired) {
    Object existing = desired.get("tags");
    if (existing instanceof List<?> list) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
      }
      return result;
    }
    return new ArrayList<>();
  }

  /** Generates a Markdown narrative for the apply result. */
  @VisibleForTesting
  static String generateNarrative(String fqn, int changeCount, boolean dryRun) {
    StringBuilder sb = new StringBuilder();
    if (dryRun) {
      sb.append("## Data Contract Preview (Dry Run)\n\n");
    } else {
      sb.append("## Data Contract Applied\n\n");
    }
    sb.append("**Entity:** `").append(fqn).append("`\n\n");
    sb.append("**Changes:** ").append(changeCount).append(" patch operation(s)\n\n");

    if (dryRun) {
      sb.append("⚠️ **No changes were applied.** Set `dryRun=false` to apply these changes.\n\n");
      sb.append(
          "Review the `validationPreview` field for the full before/after diff and warnings.\n");
    } else {
      sb.append(
          "Changes have been applied to the catalog. "
              + "Use `generate_data_contract` to verify the resulting state.\n");
    }

    return sb.toString();
  }

  @VisibleForTesting
  static ObjectMapper getYamlMapper() {
    return YAML_MAPPER;
  }
}
