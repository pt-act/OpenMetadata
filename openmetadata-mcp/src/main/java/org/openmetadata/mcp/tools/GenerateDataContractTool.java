package org.openmetadata.mcp.tools;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Data Contract Generator — emits a YAML contract from a table entity.
 *
 * <p>Reads the current state of a table and produces a portable YAML contract containing schema,
 * owners, tier, tags, glossary terms, SLAs, and quality test cases. The contract can be versioned
 * in Git and later re-applied via {@code apply_data_contract}.
 *
 * <p>Spec reference: Expansions Group E7 (R7.1).
 */
@Slf4j
public class GenerateDataContractTool implements McpTool {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  /**
   * Production call — creates default bridge interfaces that delegate to {@link
   * org.openmetadata.service.Entity} static methods and the real authorizer.
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
        McpEntityBridge.defaultRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link
   * org.openmetadata.service.Entity} static method calls and authorizer delegation, eliminating
   * the need for {@code mockStatic(Entity.class)}. Tests inject no-op or stub lambdas for {@link
   * McpEntityBridge.EntityReferenceResolver}, {@link McpEntityBridge.McpAuthorizer}, and {@link
   * McpEntityBridge.RepositoryProvider}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.RepositoryProvider repositoryProvider)
      throws IOException {

    if (nullOrEmpty(params)) {
      throw new IllegalArgumentException("Parameters cannot be null or empty");
    }

    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    // Authorize read access
    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    LOG.info("generate_data_contract: exporting contract for {}/{}", entityType, fqn);

    // Load the full entity with all fields needed for the contract
    EntityRepository<?> repository = repositoryProvider.getEntityRepository(entityType);
    EntityInterface entity = repository.getByName(null, fqn, repository.getFields(".*"));

    // Build the contract document
    Map<String, Object> contract = buildContract(entity);

    // Serialize to YAML
    String yamlContract = YAML_MAPPER.writeValueAsString(contract);

    // Build narrative
    String narrative = generateNarrative(fqn, contract);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("apiVersion", "openmetadata.org/v1alpha1");
    result.put("kind", "DataContract");
    result.put("contractYaml", yamlContract);
    result.put("narrative", narrative);

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
        "GenerateDataContractTool does not support limits enforcement.");
  }

  /**
   * Builds the contract document from the entity, following the R7.1 YAML schema.
   *
   * <pre>
   * apiVersion: openmetadata.org/v1alpha1
   * kind: DataContract
   * metadata:
   *   fqn: "svc.db.schema.orders"
   *   owners: ["team-a"]
   * schema:
   *   - {name: id, type: BIGINT, constraints: [PRIMARY_KEY]}
   *   - ...
   * tier: Tier.Tier2
   * tags: ["PersonalData.PII"]
   * glossaryTerms: ["Order", "Revenue"]
   * slas:
   *   freshnessHours: 24
   * quality:
   *   - {definition: columnValuesToBeNotNull, column: id}
   * </pre>
   */
  @VisibleForTesting
  static Map<String, Object> buildContract(EntityInterface entity) {
    Map<String, Object> contract = new LinkedHashMap<>();
    contract.put("apiVersion", "openmetadata.org/v1alpha1");
    contract.put("kind", "DataContract");

    // Metadata section
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("fqn", entity.getFullyQualifiedName());

    // Owners
    if (entity.getOwners() != null && !entity.getOwners().isEmpty()) {
      List<String> ownerNames = entity.getOwners().stream().map(EntityReference::getName).toList();
      metadata.put("owners", ownerNames);
    }

    // Description
    if (entity.getDescription() != null && !entity.getDescription().isBlank()) {
      metadata.put("description", entity.getDescription());
    }

    // DisplayName
    if (entity.getDisplayName() != null && !entity.getDisplayName().isBlank()) {
      metadata.put("displayName", entity.getDisplayName());
    }

    // Domains
    if (entity.getDomains() != null && !entity.getDomains().isEmpty()) {
      List<String> domainNames =
          entity.getDomains().stream().map(EntityReference::getFullyQualifiedName).toList();
      metadata.put("domains", domainNames);
    }

    contract.put("metadata", metadata);

    // Schema (columns) — only for table entities
    if (entity instanceof Table table) {
      List<Map<String, Object>> schemaColumns = buildSchemaColumns(table);
      if (!schemaColumns.isEmpty()) {
        contract.put("schema", schemaColumns);
      }

      // Table constraints
      if (table.getTableConstraints() != null && !table.getTableConstraints().isEmpty()) {
        contract.put("tableConstraints", table.getTableConstraints());
      }

      // RetentionPeriod
      if (table.getRetentionPeriod() != null && !table.getRetentionPeriod().isBlank()) {
        contract.put("retentionPeriod", table.getRetentionPeriod());
      }

      // SourceUrl
      if (table.getSourceUrl() != null && !table.getSourceUrl().isBlank()) {
        contract.put("sourceUrl", table.getSourceUrl());
      }

      // SchemaDefinition
      if (table.getSchemaDefinition() != null && !table.getSchemaDefinition().isBlank()) {
        contract.put("schemaDefinition", table.getSchemaDefinition());
      }
    }

    // Tier — extracted from tags (tier tags have tagFQN matching "Tier.TierN")
    String tierFqn = extractTierFqn(entity);
    if (tierFqn != null) {
      contract.put("tier", tierFqn);
    }

    // Tags (non-tier, non-glossary tags = Classification tags)
    List<String> tagFqns = extractClassificationTags(entity);
    if (!tagFqns.isEmpty()) {
      contract.put("tags", tagFqns);
    }

    // Glossary terms (tags with source=Glossary)
    List<String> glossaryTermFqns = extractGlossaryTerms(entity);
    if (!glossaryTermFqns.isEmpty()) {
      contract.put("glossaryTerms", glossaryTermFqns);
    }

    // SLAs — placeholder structure for future extension
    // OM doesn't have a native SLA entity yet; the contract reserves the field
    // for forward compatibility. When SLAs are supported, they'll be populated here.
    Map<String, Object> slas = extractSlas(entity);
    if (slas != null && !slas.isEmpty()) {
      contract.put("slas", slas);
    }

    // Quality — extract test cases associated with this entity
    List<Map<String, Object>> quality = extractQualityTests(entity);
    if (!quality.isEmpty()) {
      contract.put("quality", quality);
    }

    // Extension
    if (entity.getExtension() != null) {
      contract.put("extension", entity.getExtension());
    }

    return contract;
  }

  /** Builds the schema column list for table entities. */
  private static List<Map<String, Object>> buildSchemaColumns(Table table) {
    if (table.getColumns() == null || table.getColumns().isEmpty()) {
      return List.of();
    }

    List<Map<String, Object>> columns = new ArrayList<>();
    for (Column col : table.getColumns()) {
      Map<String, Object> colMap = new LinkedHashMap<>();
      colMap.put("name", col.getName());

      if (col.getDataType() != null) {
        colMap.put("type", col.getDataType().value());
      }

      if (col.getConstraint() != null) {
        colMap.put("constraint", col.getConstraint().value());
      }

      if (col.getDescription() != null && !col.getDescription().isBlank()) {
        colMap.put("description", col.getDescription());
      }

      // Column tags
      if (col.getTags() != null && !col.getTags().isEmpty()) {
        List<String> colTags = col.getTags().stream().map(TagLabel::getTagFQN).toList();
        colMap.put("tags", colTags);
      }

      columns.add(colMap);
    }
    return columns;
  }

  /** Extracts the tier tag FQN from the entity's tags (e.g. "Tier.Tier1"). */
  private static String extractTierFqn(EntityInterface entity) {
    if (entity.getTags() == null) return null;
    return entity.getTags().stream()
        .filter(t -> t.getTagFQN() != null && t.getTagFQN().startsWith("Tier."))
        .map(TagLabel::getTagFQN)
        .findFirst()
        .orElse(null);
  }

  /** Extracts classification (non-tier, non-glossary) tag FQNs. */
  private static List<String> extractClassificationTags(EntityInterface entity) {
    if (entity.getTags() == null) return List.of();
    return entity.getTags().stream()
        .filter(
            t ->
                t.getTagFQN() != null
                    && !t.getTagFQN().startsWith("Tier.")
                    && t.getSource() == TagLabel.TagSource.CLASSIFICATION)
        .map(TagLabel::getTagFQN)
        .toList();
  }

  /** Extracts glossary term tag FQNs (tags with source=Glossary). */
  private static List<String> extractGlossaryTerms(EntityInterface entity) {
    if (entity.getTags() == null) return List.of();
    return entity.getTags().stream()
        .filter(t -> t.getSource() == TagLabel.TagSource.GLOSSARY)
        .map(TagLabel::getTagFQN)
        .toList();
  }

  /**
   * Extracts SLA info from the entity. Currently a placeholder — returns null until OM natively
   * supports SLAs. Reserved for forward compatibility per R7.1 spec.
   */
  private static Map<String, Object> extractSlas(EntityInterface entity) {
    // Placeholder: OM does not yet have a native SLA entity.
    // When supported, extract freshness / latency guarantees here.
    return null;
  }

  /** Extracts quality test case summaries associated with the entity. */
  private static List<Map<String, Object>> extractQualityTests(EntityInterface entity) {
    // Quality tests are accessed via the test suite on the table entity.
    // For now, return an empty list — test case resolution requires
    // a separate query to the test case repository.
    // This will be populated in a follow-up iteration or by
    // apply_data_contract which can cross-reference test definitions.
    return List.of();
  }

  /** Generates a Markdown narrative describing the exported contract. */
  @VisibleForTesting
  static String generateNarrative(String fqn, Map<String, Object> contract) {
    StringBuilder sb = new StringBuilder();
    sb.append("## Data Contract Exported\n\n");
    sb.append("**Entity:** `").append(fqn).append("`\n\n");

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) contract.get("metadata");
    if (metadata != null) {
      if (metadata.containsKey("owners")) {
        sb.append("**Owners:** ").append(metadata.get("owners")).append("\n\n");
      }
      if (metadata.containsKey("domains")) {
        sb.append("**Domains:** ").append(metadata.get("domains")).append("\n\n");
      }
    }

    if (contract.containsKey("tier")) {
      sb.append("**Tier:** ").append(contract.get("tier")).append("\n\n");
    }

    if (contract.containsKey("schema")) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> cols = (List<Map<String, Object>>) contract.get("schema");
      sb.append("**Schema:** ").append(cols.size()).append(" column(s)\n\n");
    }

    if (contract.containsKey("tags")) {
      @SuppressWarnings("unchecked")
      List<String> tags = (List<String>) contract.get("tags");
      sb.append("**Tags:** ").append(tags).append("\n\n");
    }

    if (contract.containsKey("glossaryTerms")) {
      @SuppressWarnings("unchecked")
      List<String> terms = (List<String>) contract.get("glossaryTerms");
      sb.append("**Glossary Terms:** ").append(terms).append("\n\n");
    }

    sb.append(
        "The contract YAML is ready for version control. "
            + "Edit it and apply with `apply_data_contract(dryRun=true)` to preview changes.\n");

    return sb.toString();
  }

  @VisibleForTesting
  static ObjectMapper getYamlMapper() {
    return YAML_MAPPER;
  }
}
