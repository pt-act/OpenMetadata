package org.openmetadata.mcp.tools;

import static org.openmetadata.service.search.SearchUtils.mapEntityTypesToIndexNames;
import static org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnConstraint;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Agentic Test Author — proposes sensible test cases for a table based on its schema, constraints,
 * profiler data, and lineage.
 *
 * <p>Generates proposals for:
 *
 * <ul>
 *   <li>{@code columnValuesToBeNotNull} for PK and NOT_NULL columns lacking the test
 *   <li>{@code columnValuesToBeUnique} for declared primary-key columns
 *   <li>{@code tableRowCountToBeBetween} using profiler history ±3σ bounds
 *   <li>{@code tableFreshness} using historical update 95th-percentile cadence
 *   <li>Referential-integrity proposals where FK constraints exist
 * </ul>
 *
 * <p>Each proposal includes {@code testDefinitionFqn}, {@code parameters}, {@code rationale}, and
 * {@code confidence}. The tool does NOT apply proposals — the caller must invoke {@code
 * create_test_case} explicitly.
 *
 * <p>Spec reference: Expansions Group E11 (R11.1–R11.3).
 */
@Slf4j
public class SuggestTestCasesTool implements McpTool {

  private static final int MAX_PROPOSALS = 25;
  private static final int PAYLOAD_MAX_BYTES = 8192; // 8 KB

  // ====================== Production overloads ======================

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
        McpEntityBridge.defaultEntityFetcher(),
        McpEntityBridge.defaultSearchRepositoryProvider());
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "SuggestTestCasesTool does not require limit validation.");
  }

  // ====================== Test-friendly overload ======================

  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.RepositoryProvider repoProvider,
      McpEntityBridge.EntityFetcher entityFetcher,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider)
      throws IOException {

    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    LOG.info("suggest_test_cases: generating proposals for {}/{}", entityType, fqn);

    // Step 1: Load the full table entity with columns and constraints
    Table table = loadTable(fqn, repoProvider);
    if (table == null) {
      return buildErrorResult(fqn, entityType, "Entity not found or not a table: " + fqn);
    }

    // Step 2: Find existing test case definitions for this table to avoid duplicates
    Set<String> existingTestDefs =
        findExistingTestCaseDefinitions(fqn, entityType, securityContext, searchRepoProvider);

    // Step 3: Generate proposals
    List<Map<String, Object>> proposals = new ArrayList<>();

    // R11.3a: columnValuesToBeNotNull for PK/NOT_NULL columns
    proposals.addAll(proposeNotNullTests(table, fqn, existingTestDefs));

    // R11.3b: columnValuesToBeUnique for declared PKs
    proposals.addAll(proposeUniqueTests(table, fqn, existingTestDefs));

    // R11.3d: tableRowCountToBeBetween using profiler history
    proposals.addAll(proposeRowCountTests(table, fqn, existingTestDefs, entityFetcher));

    // R11.3c: tableFreshness using historical update cadence
    proposals.addAll(proposeFreshnessTests(table, fqn, existingTestDefs, entityFetcher));

    // R11.3e: Referential-integrity where FK constraints exist
    proposals.addAll(
        proposeReferentialIntegrityTests(table, fqn, existingTestDefs, searchRepoProvider));

    // Cap proposals
    if (proposals.size() > MAX_PROPOSALS) {
      proposals = proposals.subList(0, MAX_PROPOSALS);
    }

    // Step 4: Build result
    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("fqn", fqn);
    resultData.put("entityType", entityType);
    resultData.put("proposalCount", proposals.size());
    resultData.put("proposals", proposals);
    resultData.put(
        "note",
        "These are suggestions only. Use create_test_case to apply any proposal explicitly.");

    String narrative = generateNarrative(fqn, table, proposals);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(resultData)).narrative(narrative);
    Map<String, Object> result = new LinkedHashMap<>(envelope.build());
    result.put("fqn", fqn);
    result.put("entityType", entityType);

    // Enforce byte cap
    result = enforceByteCap(result);

    LOG.info("suggest_test_cases: generated {} proposals for {}", proposals.size(), fqn);

    return result;
  }

  // ====================== Step 1: Load table ======================

  private Table loadTable(String fqn, McpEntityBridge.RepositoryProvider repoProvider) {
    try {
      EntityRepository<?> repository = repoProvider.getEntityRepository(Entity.TABLE);
      Object entity =
          repository.getByName(null, fqn, repository.getFields("columns,tableConstraints,profile"));
      if (entity instanceof Table table) {
        return table;
      }
      // POJO → re-read as Table if needed
      if (entity != null) {
        return JsonUtils.readValue(JsonUtils.pojoToJson(entity), Table.class);
      }
    } catch (Exception e) {
      LOG.warn("Failed to load table '{}': {}", fqn, e.getMessage());
    }
    return null;
  }

  // ====================== Step 2: Find existing test definitions ======================

  /**
   * Finds test definition names already in use for this table's test cases via search.
   * Uses the same search-and-extract pattern as ChangeImpactTool.
   */
  private Set<String> findExistingTestCaseDefinitions(
      String fqn,
      String entityType,
      CatalogSecurityContext securityContext,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {

    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo == null) {
        LOG.warn("Search repository not initialized — cannot find existing tests for '{}'", fqn);
        return Set.of();
      }

      // Search for test cases whose entityLink references this entity
      String entityLinkPattern = "<#E::" + entityType + "::" + fqn;
      String queryFilter =
          "{\"bool\":{\"must\":["
              + "{\"term\":{\"entityType\":\"testCase\"}},"
              + "{\"wildcard\":{\"entityLink\":\""
              + escapeJson(entityLinkPattern)
              + "*\"}}"
              + "]}}";

      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames("testCase")))
              .withQueryFilter(queryFilter)
              .withSize(100)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(false);

      SubjectContext subjectContext = getSubjectContext(securityContext);
      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) {
        return Set.of();
      }

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      // Extract test definition names from hits
      // OpenSearch response has hits.hits[]._source.testDefinition.name
      // Defensive: navigate structure step-by-step with logging on unexpected shapes
      Set<String> testDefs = new java.util.HashSet<>();
      Object hitsObj = searchResponse.get("hits");
      if (!(hitsObj instanceof Map<?, ?> hitsMap)) {
        LOG.debug("Unexpected search response structure for '{}': 'hits' is not a map", fqn);
        return testDefs;
      }
      Object hitsList = hitsMap.get("hits");
      if (!(hitsList instanceof List<?> hitItems)) {
        LOG.debug("Unexpected search response structure for '{}': 'hits.hits' is not a list", fqn);
        return testDefs;
      }
      for (Object item : hitItems) {
        if (!(item instanceof Map<?, ?> hit)) continue;
        Object source = hit.get("_source") != null ? hit.get("_source") : hit.get("source");
        if (!(source instanceof Map<?, ?> sourceMap)) continue;
        Object testDef = sourceMap.get("testDefinition");
        if (!(testDef instanceof Map<?, ?> testDefMap)) continue;
        Object name = testDefMap.get("name");
        if (name != null) testDefs.add(name.toString());
      }
      LOG.debug(
          "Found {} existing test definition(s) for '{}': {}", testDefs.size(), fqn, testDefs);
      return testDefs;
    } catch (Exception e) {
      LOG.debug("Could not fetch existing test cases for '{}': {}", fqn, e.getMessage());
    }
    return Set.of();
  }

  // ====================== R11.3a: NOT-NULL proposals ======================

  /**
   * Proposes {@code columnValuesToBeNotNull} for columns with constraint NOT_NULL or
   * PRIMARY_KEY that don't already have such a test.
   *
   * <p>Note: {@code existingTestDefs} contains test-definition names only (not column-level
   * detail), so column-level dedup is not possible here. A future iteration should extract
   * column names from existing test-case entityLinks to skip columns that already have the test.
   */
  @SuppressWarnings("unused") // existingTestDefs reserved for future column-level dedup
  @VisibleForTesting
  static List<Map<String, Object>> proposeNotNullTests(
      Table table, String fqn, Set<String> existingTestDefs) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    if (table.getColumns() == null) return proposals;

    for (Column col : table.getColumns()) {
      boolean isNotNull = col.getConstraint() == ColumnConstraint.NOT_NULL;
      boolean isPK =
          col.getConstraint() == ColumnConstraint.PRIMARY_KEY
              || isColumnInPrimaryKey(table, col.getName());

      if (isNotNull || isPK) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("testDefinitionFqn", "columnValuesToBeNotNull");
        proposal.put("columnName", col.getName());

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("columnName", col.getName());
        proposal.put("parameters", parameters);

        String rationale;
        double confidence;
        if (isPK || col.getConstraint() == ColumnConstraint.PRIMARY_KEY) {
          rationale =
              String.format(
                  "Column '%s' is part of the primary key and should never be null", col.getName());
          confidence = 0.95;
        } else {
          rationale =
              String.format(
                  "Column '%s' has a NOT NULL constraint and should be validated", col.getName());
          confidence = 0.9;
        }
        proposal.put("rationale", rationale);
        proposal.put("confidence", confidence);
        proposal.put("entityLink", buildColumnEntityLink(fqn, col.getName()));
        proposals.add(proposal);
      }
    }
    return proposals;
  }

  // ====================== R11.3b: UNIQUE proposals ======================

  /**
   * Proposes {@code columnValuesToBeUnique} for declared primary-key columns.
   *
   * <p>Does NOT short-circuit on {@code existingTestDefs} because uniqueness is a per-column
   * concern — a composite PK (id, org_id) may have one column with the test already but the
   * other still needs it. The suggestion model lets the caller skip proposals they already have.
   *
   * <p>Column-level dedup limitation: {@code existingTestDefs} contains definition names only,
   * not column-level detail. See {@link #proposeNotNullTests} for the same caveat.
   */
  @SuppressWarnings("unused") // existingTestDefs reserved for future column-level dedup
  @VisibleForTesting
  static List<Map<String, Object>> proposeUniqueTests(
      Table table, String fqn, Set<String> existingTestDefs) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    if (table.getColumns() == null) return proposals;
    // Note: Do not short-circuit on existingTestDefs.contains("columnValuesToBeUnique") —
    // uniqueness is per-column; a composite PK (id, org_id) may have one column with the test
    // already but the other still needs it. The suggestion model lets the user skip.

    List<String> pkColumns = getPrimaryKeyColumns(table);
    for (String colName : pkColumns) {
      Map<String, Object> proposal = new LinkedHashMap<>();
      proposal.put("testDefinitionFqn", "columnValuesToBeUnique");
      proposal.put("columnName", colName);

      Map<String, Object> parameters = new LinkedHashMap<>();
      parameters.put("columnName", colName);
      proposal.put("parameters", parameters);

      proposal.put(
          "rationale",
          String.format(
              "Column '%s' is a declared primary key and its values must be unique", colName));
      proposal.put("confidence", 0.95);
      proposal.put("entityLink", buildColumnEntityLink(fqn, colName));
      proposals.add(proposal);
    }
    return proposals;
  }

  // ====================== R11.3d: ROW COUNT proposals ======================

  /**
   * Proposes {@code tableRowCountToBeBetween} using profiler statistics ±3σ.
   *
   * <p>If profiler data is unavailable, the proposal has lower confidence and generic bounds.
   */
  @VisibleForTesting
  static List<Map<String, Object>> proposeRowCountTests(
      Table table,
      String fqn,
      Set<String> existingTestDefs,
      McpEntityBridge.EntityFetcher entityFetcher) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    if (existingTestDefs.contains("tableRowCountToBeBetween")) return proposals;

    // Try to read profiler statistics from the table entity
    Long rowCount = null;
    Double stddev = null;

    try {
      Object entityObj =
          entityFetcher.getEntityByName(Entity.TABLE, fqn, "profile", Include.NON_DELETED);
      if (entityObj != null) {
        Map<String, Object> entityMap =
            JsonUtils.readValue(JsonUtils.pojoToJson(entityObj), Map.class);
        Object profile = entityMap.get("profile");
        if (profile instanceof Map<?, ?> profileMap) {
          Object rowCountObj = profileMap.get("rowCount");
          if (rowCountObj instanceof Number n) {
            rowCount = n.longValue();
          }
          Object stddevObj = profileMap.get("rowCountStdDev");
          if (stddevObj instanceof Number n) {
            stddev = n.doubleValue();
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not read profiler data for '{}': {}", fqn, e.getMessage());
    }

    Map<String, Object> proposal = new LinkedHashMap<>();
    proposal.put("testDefinitionFqn", "tableRowCountToBeBetween");

    Map<String, Object> parameters = new LinkedHashMap<>();
    double confidence;

    if (rowCount != null && stddev != null && stddev > 0) {
      // ±3σ bounds
      long lowerBound = Math.max(0, (long) (rowCount - 3 * stddev));
      long upperBound = (long) (rowCount + 3 * stddev);
      parameters.put("minValue", lowerBound);
      parameters.put("maxValue", upperBound);
      proposal.put(
          "rationale",
          String.format(
              "Based on profiler data (row count: %d, σ: %.1f), row count should be between %d and %d (±3σ)",
              rowCount, stddev, lowerBound, upperBound));
      confidence = 0.85;
    } else if (rowCount != null) {
      // Only row count available — use ±50% as a rough heuristic
      long lowerBound = Math.max(0, (long) (rowCount * 0.5));
      long upperBound = (long) (rowCount * 1.5);
      parameters.put("minValue", lowerBound);
      parameters.put("maxValue", upperBound);
      proposal.put(
          "rationale",
          String.format(
              "Based on profiler row count (%d), suggest range of ±50%% (no stddev available)",
              rowCount));
      confidence = 0.6;
    } else {
      // No profiler data — propose without bounds (user must fill in)
      proposal.put(
          "rationale",
          "No profiler data available; suggest setting row count bounds based on domain knowledge");
      confidence = 0.3;
    }

    proposal.put("parameters", parameters);
    proposal.put("confidence", confidence);
    proposal.put("entityLink", buildTableEntityLink(fqn));
    proposals.add(proposal);
    return proposals;
  }

  // ====================== R11.3c: FRESHNESS proposals ======================

  /**
   * Proposes {@code tableFreshness} using historical update 95th-percentile cadence.
   *
   * <p>If no update history is available, proposes with a generic 24h window at lower confidence.
   */
  @VisibleForTesting
  static List<Map<String, Object>> proposeFreshnessTests(
      Table table,
      String fqn,
      Set<String> existingTestDefs,
      McpEntityBridge.EntityFetcher entityFetcher) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    if (existingTestDefs.contains("tableFreshness")) return proposals;

    // Try to read update cadence from profile/change events
    Long updateCadenceHours = null;

    try {
      Object entityObj =
          entityFetcher.getEntityByName(
              Entity.TABLE, fqn, "profile,changeSummary", Include.NON_DELETED);
      if (entityObj != null) {
        Map<String, Object> entityMap =
            JsonUtils.readValue(JsonUtils.pojoToJson(entityObj), Map.class);
        Object profile = entityMap.get("profile");
        if (profile instanceof Map<?, ?> profileMap) {
          Object cadenceObj = profileMap.get("updateCadenceHours");
          if (cadenceObj instanceof Number n) {
            updateCadenceHours = n.longValue();
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not read cadence data for '{}': {}", fqn, e.getMessage());
    }

    Map<String, Object> proposal = new LinkedHashMap<>();
    proposal.put("testDefinitionFqn", "tableFreshness");

    Map<String, Object> parameters = new LinkedHashMap<>();
    double confidence;

    if (updateCadenceHours != null && updateCadenceHours > 0) {
      parameters.put("freshnessThreshold", updateCadenceHours * 1.5); // 1.5× the 95th percentile
      proposal.put(
          "rationale",
          String.format(
              "Based on historical update cadence (%dh 95th percentile), table should refresh within %dh",
              updateCadenceHours, (long) (updateCadenceHours * 1.5)));
      confidence = 0.8;
    } else {
      // Default 24h freshness
      parameters.put("freshnessThreshold", 36); // 1.5 × 24h
      proposal.put(
          "rationale",
          "No update cadence data available; suggest 36h freshness threshold (1.5× default 24h)");
      confidence = 0.4;
    }

    proposal.put("parameters", parameters);
    proposal.put("confidence", confidence);
    proposal.put("entityLink", buildTableEntityLink(fqn));
    proposals.add(proposal);
    return proposals;
  }

  // ====================== R11.3e: REFERENTIAL INTEGRITY proposals ======================

  /**
   * Proposes referential-integrity tests where FK constraints exist.
   *
   * <p>Uses table-level FK constraints to generate RI proposals.
   */
  @VisibleForTesting
  static List<Map<String, Object>> proposeReferentialIntegrityTests(
      Table table,
      String fqn,
      Set<String> existingTestDefs,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    // Note: RI proposals use tableCustomSQLQuery as the test definition, so dedup on that
    // name would suppress ALL custom SQL tests — too aggressive. The caller can judge
    // whether an existing custom SQL test already covers the FK relationship.

    // Find FK constraints that reference other tables
    List<TableConstraint> fkConstraints = getForeignKeyConstraints(table);
    if (fkConstraints.isEmpty()) return proposals;

    for (TableConstraint fk : fkConstraints) {
      List<String> fkColumns = fk.getColumns();
      if (fkColumns == null || fkColumns.isEmpty()) continue;

      Map<String, Object> proposal = new LinkedHashMap<>();
      proposal.put("testDefinitionFqn", "tableCustomSQLQuery");

      Map<String, Object> parameters = new LinkedHashMap<>();
      parameters.put("sql", buildRISql(fqn, fkColumns));
      proposal.put("parameters", parameters);

      proposal.put(
          "rationale",
          String.format(
              "Foreign key on columns %s suggests referential integrity should be validated",
              fkColumns));
      proposal.put("confidence", 0.7);
      proposal.put("entityLink", buildTableEntityLink(fqn));
      proposals.add(proposal);
    }
    return proposals;
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(String fqn, Table table, List<Map<String, Object>> proposals) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Test Case Suggestions: ").append(fqn).append("\n\n");

    if (proposals.isEmpty()) {
      sb.append("No new test case proposals — all common tests appear to be already defined.");
      return sb.toString();
    }

    sb.append("**").append(proposals.size()).append(" proposal(s)** generated:\n\n");

    // Group by test definition
    Map<String, List<Map<String, Object>>> grouped =
        proposals.stream()
            .collect(
                Collectors.groupingBy(
                    p -> (String) p.getOrDefault("testDefinitionFqn", "unknown"),
                    LinkedHashMap::new,
                    Collectors.toList()));

    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      String testDef = entry.getKey();
      List<Map<String, Object>> group = entry.getValue();
      sb.append("### ").append(testDef).append(" (").append(group.size()).append(")\n");

      for (Map<String, Object> p : group) {
        String col = (String) p.getOrDefault("columnName", "(table-level)");
        Double conf = (Double) p.getOrDefault("confidence", 0.0);
        sb.append("- **")
            .append(col)
            .append("** (confidence: ")
            .append(String.format("%.0f%%", conf * 100))
            .append("): ")
            .append(p.getOrDefault("rationale", ""))
            .append("\n");
      }
      sb.append("\n");
    }

    sb.append(
        "Use `create_test_case` to apply any proposal. "
            + "Adjust parameters (especially bounds) before applying.\n");

    return sb.toString();
  }

  // ====================== Byte cap enforcement ======================

  /** Enforces the <8KB byte cap on the serialized response. */
  @VisibleForTesting
  static Map<String, Object> enforceByteCap(Map<String, Object> result) {
    String json = JsonUtils.pojoToJson(result);
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length <= PAYLOAD_MAX_BYTES) {
      return result;
    }

    // Strategy: truncate proposals list
    List<String> warnings = new ArrayList<>();
    Object resultsObj = result.get("results");
    if (resultsObj instanceof List<?> resultsList && !resultsList.isEmpty()) {
      Object firstResult = resultsList.get(0);
      if (firstResult instanceof Map<?, ?> resultMap) {
        @SuppressWarnings("unchecked")
        Map<String, Object> impactResult = (Map<String, Object>) resultMap;
        Object proposalsObj = impactResult.get("proposals");
        if (proposalsObj instanceof List<?> proposals && proposals.size() > 10) {
          int originalSize = proposals.size();
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> proposalsList = (List<Map<String, Object>>) proposals;
          impactResult.put("proposals", proposalsList.subList(0, 10));
          impactResult.put("proposalCount", 10);
          warnings.add(
              String.format(
                  "truncated:proposals list from %d to 10 entries (payload >8KB)", originalSize));
        }
      }
    }

    if (!warnings.isEmpty()) {
      @SuppressWarnings("unchecked")
      List<String> existingWarnings =
          result.get("warnings") instanceof List
              ? new ArrayList<>((List<String>) result.get("warnings"))
              : new ArrayList<>();
      existingWarnings.addAll(warnings);
      result.put("warnings", existingWarnings);
    }

    return result;
  }

  // ====================== Helper methods ======================

  /** Checks if a column name appears in any PRIMARY_KEY table constraint. */
  @VisibleForTesting
  static boolean isColumnInPrimaryKey(Table table, String columnName) {
    if (table.getTableConstraints() == null) return false;
    for (TableConstraint tc : table.getTableConstraints()) {
      if (tc.getConstraintType() == TableConstraint.ConstraintType.PRIMARY_KEY) {
        if (tc.getColumns() != null && tc.getColumns().contains(columnName)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns the list of column names that are part of the primary key. */
  @VisibleForTesting
  static List<String> getPrimaryKeyColumns(Table table) {
    List<String> pkColumns = new ArrayList<>();
    if (table.getTableConstraints() == null) return pkColumns;
    for (TableConstraint tc : table.getTableConstraints()) {
      if (tc.getConstraintType() == TableConstraint.ConstraintType.PRIMARY_KEY
          && tc.getColumns() != null) {
        pkColumns.addAll(tc.getColumns());
      }
    }
    return pkColumns;
  }

  /** Returns FK constraints from the table. */
  @VisibleForTesting
  static List<TableConstraint> getForeignKeyConstraints(Table table) {
    List<TableConstraint> fkConstraints = new ArrayList<>();
    if (table.getTableConstraints() == null) return fkConstraints;
    for (TableConstraint tc : table.getTableConstraints()) {
      if (tc.getConstraintType() == TableConstraint.ConstraintType.FOREIGN_KEY) {
        fkConstraints.add(tc);
      }
    }
    return fkConstraints;
  }

  /** Builds a column-level entity link string for create_test_case. */
  @VisibleForTesting
  static String buildColumnEntityLink(String fqn, String columnName) {
    return "<#E::table::" + fqn + "::columns::" + columnName + ">";
  }

  /** Builds a table-level entity link string. */
  @VisibleForTesting
  static String buildTableEntityLink(String fqn) {
    return "<#E::table::" + fqn + ">";
  }

  /** Generates a template RI SQL query for FK columns. */
  @VisibleForTesting
  static String buildRISql(String fqn, List<String> fkColumns) {
    String columnList = String.join(", ", fkColumns);
    return String.format(
        "SELECT * FROM %s WHERE %s IS NULL OR %s NOT IN (SELECT DISTINCT %s FROM referenced_table)",
        fqn.replace(".", "_"), columnList, columnList, columnList);
  }

  /** Escapes special characters for JSON string values. */
  private static String escapeJson(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  /** Builds an error result envelope. */
  private Map<String, Object> buildErrorResult(String fqn, String entityType, String message) {
    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("fqn", fqn);
    resultData.put("entityType", entityType);
    resultData.put("error", message);
    resultData.put("proposalCount", 0);
    resultData.put("proposals", List.of());

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(List.of(resultData))
            .narrative("Error: " + message)
            .warnings(List.of(message));
    Map<String, Object> result = new LinkedHashMap<>(envelope.build());
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    return result;
  }
}
