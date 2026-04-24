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
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.AlterView;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.openmetadata.schema.api.lineage.AddLineage;
import org.openmetadata.schema.type.EntitiesEdge;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * SQL → Lineage — parses raw SQL and produces lineage edge proposals.
 *
 * <p>Accepts a SQL string, parses it with JSQLParser, extracts source and target table
 * references, resolves each table against the OpenMetadata catalog via search, assigns a
 * confidence score per R8.3, and returns a lineage plan. When {@code apply=true}, creates
 * lineage edges for all resolved tables with {@code confidence >= 0.8}, and returns
 * low-confidence edges under {@code requiresConfirmation}.
 *
 * <p>Supported SQL shapes (R8.3):
 *
 * <ul>
 *   <li>{@code SELECT … FROM} — identifies source tables; no target unless wrapped in
 *       INSERT/CREATE. Sources reported under {@code sourcesOnly} in the plan.
 *   <li>{@code INSERT INTO … SELECT} — target = insert table, sources = FROM clause
 *   <li>{@code CREATE TABLE … AS SELECT} — target = created table, sources = FROM clause
 *   <li>{@code CREATE OR REPLACE VIEW … AS SELECT} — target = view, sources = FROM clause
 *   <li>CTE inlining: {@code WITH x AS (SELECT …) INSERT INTO dst SELECT … FROM x} — CTE
 *       bodies are expanded to find the real source tables
 *   <li>Set operations (UNION / INTERSECT / EXCEPT) — sources from all branches are
 *       collected
 * </ul>
 *
 * <p>Confidence scores (R8.3):
 *
 * <ul>
 *   <li>1.0 — SQL contains an exact FQN that resolves to a single catalog entity
 *   <li>0.8 — Table name resolves uniquely within {@code defaultService}
 *   <li>0.5 — Table name resolves but multiple matches exist
 *   <li>0.3 — Table name found in SQL but could not be resolved in the catalog
 * </ul>
 *
 * <p>Spec reference: Expansions Group E8 (R8.1–R8.3).
 */
@Slf4j
public class LineageFromSqlTool implements McpTool {

  private static final int MAX_SQL_LENGTH = 4000;

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {

    return execute(
        securityContext,
        params,
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultSearchRepositoryProvider(),
        McpEntityBridge.defaultLineageRepositoryProvider(),
        (entityType) ->
            authorizer.authorize(
                securityContext,
                new OperationContext(entityType, MetadataOperation.EDIT_LINEAGE),
                new ResourceContext<>(entityType)),
        null);
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "LineageFromSqlTool does not require limit validation.");
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity} static
   * method calls, eliminating the need for {@code mockStatic(Entity.class)}. Tests inject no-op or
   * stub lambdas for {@link McpEntityBridge.EntityReferenceResolver}, {@link
   * McpEntityBridge.SearchRepositoryProvider}, {@link McpEntityBridge.LineageRepositoryProvider},
   * {@link LineageAuthorizer}, and {@link LineageAppender}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      CatalogSecurityContext securityContext,
      Map<String, Object> params,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider,
      McpEntityBridge.LineageRepositoryProvider lineageRepoProvider,
      LineageAuthorizer lineageAuthorizer,
      LineageAppender lineageAppender)
      throws IOException {

    // ── Parameter extraction ──
    String sql = extractRequiredString(params, "sql");
    String defaultService =
        params.containsKey("defaultService") ? String.valueOf(params.get("defaultService")) : null;
    boolean apply = parseBooleanParam(params, "apply", false);

    // ── R8.7: Reject oversized / unparsable SQL ──
    if (sql.length() > MAX_SQL_LENGTH) {
      return errorResult(
          String.format(
              "SQL exceeds maximum length of %d characters (got %d). "
                  + "Break the SQL into smaller statements or shorten identifiers.",
              MAX_SQL_LENGTH, sql.length()));
    }

    // ── Parse SQL ──
    List<Statement> statements;
    try {
      statements = CCJSqlParserUtil.parseStatements(sql);
    } catch (JSQLParserException e) {
      String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
      return errorResult(
          String.format("Could not parse SQL: %s", detail != null ? detail : "syntax error"));
    }

    if (nullOrEmpty(statements)) {
      return errorResult("No parseable SQL statements found in the input.");
    }

    // ── Extract lineage edges from parsed SQL ──
    List<SqlLineageEdge> rawEdges = new ArrayList<>();
    List<String> sourcesOnly = new ArrayList<>();
    for (Statement stmt : statements) {
      extractLineageEdges(stmt, rawEdges, sourcesOnly);
    }

    if (rawEdges.isEmpty() && sourcesOnly.isEmpty()) {
      Map<String, Object> result = new LinkedHashMap<>(createEmptyEnvelope());
      result.put("sqlShape", "unknown");
      result.put("plan", List.of());
      result.put("edgeCount", 0);
      result.put("narrative", "No lineage relationships could be extracted from the SQL.");
      return result;
    }

    // ── Resolve tables and assign confidence scores ──
    List<ResolvedLineageEdge> resolvedEdges = new ArrayList<>();
    for (SqlLineageEdge raw : rawEdges) {
      ResolvedTable resolvedTarget =
          resolveTable(raw.targetTable, defaultService, referenceResolver, searchRepoProvider);
      List<ResolvedTable> resolvedSources = new ArrayList<>();
      for (String sourceName : raw.sourceTables) {
        resolvedSources.add(
            resolveTable(sourceName, defaultService, referenceResolver, searchRepoProvider));
      }
      resolvedEdges.add(
          new ResolvedLineageEdge(raw.sqlShape, resolvedTarget, resolvedSources, raw.cteName));
    }

    // Resolve bare-source-only tables
    List<ResolvedTable> resolvedSourcesOnly = new ArrayList<>();
    for (String sourceName : sourcesOnly) {
      resolvedSourcesOnly.add(
          resolveTable(sourceName, defaultService, referenceResolver, searchRepoProvider));
    }

    // ── Build lineage plan ──
    List<Map<String, Object>> plan = buildPlan(resolvedEdges, resolvedSourcesOnly);

    // ── Apply if requested (R8.6) ──
    List<Map<String, Object>> applied = new ArrayList<>();
    List<Map<String, Object>> requiresConfirmation = new ArrayList<>();

    if (apply) {
      String updatedBy = securityContext.getUserPrincipal().getName();
      ApplyResult applyResult =
          applyHighConfidenceEdges(
              plan, updatedBy, lineageAuthorizer, lineageAppender, lineageRepoProvider);
      if (applyResult.error != null) {
        return errorResult(applyResult.error);
      }
      applied = applyResult.applied;
      requiresConfirmation = applyResult.requiresConfirmation;
    }

    // ── Build response ──
    return buildResponse(
        plan, applied, requiresConfirmation, resolvedEdges, resolvedSourcesOnly, apply);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SQL Parsing (R8.3)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Extracts lineage edges from a parsed SQL statement. Handles SELECT, INSERT, CREATE TABLE AS,
   * CREATE/ALTER VIEW, CTE inlining, and set operations (UNION/INTERSECT/EXCEPT).
   *
   * <p>Bare SELECTs (no INSERT/CREATE wrapper) add their source tables to {@code sourcesOnly}
   * instead of creating from→to edges, since there is no target table.
   */
  @VisibleForTesting
  void extractLineageEdges(Statement stmt, List<SqlLineageEdge> edges, List<String> sourcesOnly) {
    if (stmt instanceof Select select) {
      extractFromSelect(select, edges, sourcesOnly);
    } else if (stmt instanceof Insert insert) {
      extractFromInsert(insert, edges);
    } else if (stmt instanceof CreateTable createTable) {
      extractFromCreateTable(createTable, edges);
    } else if (stmt instanceof CreateView createView) {
      extractFromCreateView(createView, edges);
    } else if (stmt instanceof AlterView alterView) {
      extractFromAlterView(alterView, edges);
    }
    // Other statement types (UPDATE, DELETE, etc.) are not lineage-relevant per spec
  }

  /** Extracts source tables from a bare SELECT (no target — reports sources only). */
  private void extractFromSelect(
      Select select, List<SqlLineageEdge> edges, List<String> sourcesOnly) {
    // Process CTEs first — they may expand to real source tables
    Map<String, List<String>> cteBodies = extractCteBodies(select.getWithItemsList());

    List<String> sourceTables = new ArrayList<>();
    collectSourcesFromSelect(select, sourceTables);

    // Inline CTE references: replace CTE names with their underlying source tables
    List<String> inlinedSources = inlineCteReferences(sourceTables, cteBodies);

    if (!inlinedSources.isEmpty()) {
      // Bare SELECT has no target — just report sources
      sourcesOnly.addAll(inlinedSources);
    }
  }

  /** Extracts target + source tables from INSERT INTO … SELECT. */
  private void extractFromInsert(Insert insert, List<SqlLineageEdge> edges) {
    String targetTable = getTableName(insert.getTable());
    if (targetTable == null) return;

    List<String> sourceTables = new ArrayList<>();
    Map<String, List<String>> cteBodies = new HashMap<>();

    // CTEs on the INSERT statement itself
    if (insert.getWithItemsList() != null) {
      cteBodies = extractCteBodyList(insert.getWithItemsList());
    }

    // If the INSERT has a SELECT, extract sources from it
    Select selectBody = insert.getSelect();
    if (selectBody != null) {
      // Also process CTEs from the select
      if (selectBody.getWithItemsList() != null) {
        cteBodies.putAll(extractCteBodyList(selectBody.getWithItemsList()));
      }
      collectSourcesFromSelect(selectBody, sourceTables);
    }

    // Inline CTE references
    List<String> inlinedSources = inlineCteReferences(sourceTables, cteBodies);
    edges.add(new SqlLineageEdge("INSERT", targetTable, inlinedSources));
  }

  /** Extracts target + source tables from CREATE TABLE … AS SELECT. */
  private void extractFromCreateTable(CreateTable createTable, List<SqlLineageEdge> edges) {
    String targetTable = getTableName(createTable.getTable());
    if (targetTable == null) return;

    List<String> sourceTables = new ArrayList<>();
    Select selectBody = createTable.getSelect();
    if (selectBody != null) {
      Map<String, List<String>> cteBodies = extractCteBodies(selectBody.getWithItemsList());
      collectSourcesFromSelect(selectBody, sourceTables);
      sourceTables = inlineCteReferences(sourceTables, cteBodies);
    }

    if (!sourceTables.isEmpty()) {
      edges.add(new SqlLineageEdge("CREATE_TABLE_AS", targetTable, sourceTables));
    }
  }

  /** Extracts target + source tables from CREATE VIEW … AS SELECT. */
  private void extractFromCreateView(CreateView createView, List<SqlLineageEdge> edges) {
    String targetTable = getTableName(createView.getView());
    if (targetTable == null) return;

    List<String> sourceTables = new ArrayList<>();
    Select selectBody = createView.getSelect();
    if (selectBody != null) {
      Map<String, List<String>> cteBodies = extractCteBodies(selectBody.getWithItemsList());
      collectSourcesFromSelect(selectBody, sourceTables);
      sourceTables = inlineCteReferences(sourceTables, cteBodies);
    }

    edges.add(new SqlLineageEdge("CREATE_VIEW_AS", targetTable, sourceTables));
  }

  /** Extracts target + source tables from ALTER VIEW … AS SELECT. */
  private void extractFromAlterView(AlterView alterView, List<SqlLineageEdge> edges) {
    String targetTable = getTableName(alterView.getView());
    if (targetTable == null) return;

    List<String> sourceTables = new ArrayList<>();
    Select selectBody = alterView.getSelect();
    if (selectBody != null) {
      Map<String, List<String>> cteBodies = extractCteBodies(selectBody.getWithItemsList());
      collectSourcesFromSelect(selectBody, sourceTables);
      sourceTables = inlineCteReferences(sourceTables, cteBodies);
    }

    edges.add(new SqlLineageEdge("ALTER_VIEW_AS", targetTable, sourceTables));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Source table collection (handles PlainSelect, SetOperationList, ParenthesedSelect)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Collects source table names from a {@link Select} object. Handles PlainSelect, SetOperationList
   * (UNION/INTERSECT/EXCEPT), and ParenthesedSelect (subqueries).
   */
  private void collectSourcesFromSelect(Select select, List<String> sourceTables) {
    if (select instanceof PlainSelect plainSelect) {
      collectSourceTables(plainSelect, sourceTables);
    } else if (select instanceof SetOperationList setOpList) {
      // UNION / INTERSECT / EXCEPT — collect from all branches
      for (Select branch : setOpList.getSelects()) {
        collectSourcesFromSelect(branch, sourceTables);
      }
    } else if (select instanceof ParenthesedSelect parenSelect) {
      // Parenthesed select — recurse into inner select
      Select inner = parenSelect.getSelect();
      if (inner != null) {
        collectSourcesFromSelect(inner, sourceTables);
      }
    }
  }

  /** Collects source table names from a PlainSelect's FROM clause and JOINs. */
  private void collectSourceTables(PlainSelect plainSelect, List<String> sourceTables) {
    // FROM clause
    FromItem fromItem = plainSelect.getFromItem();
    collectFromItem(fromItem, sourceTables);

    // JOINs
    if (plainSelect.getJoins() != null) {
      for (var join : plainSelect.getJoins()) {
        collectFromItem(join.getRightItem(), sourceTables);
      }
    }
  }

  /** Recursively collects table names from a FromItem (handles subqueries). */
  private void collectFromItem(FromItem fromItem, List<String> sourceTables) {
    if (fromItem instanceof Table table) {
      String name = getTableName(table);
      if (name != null) {
        sourceTables.add(name);
      }
    } else if (fromItem instanceof ParenthesedSelect parenSelect) {
      // Subquery in FROM — recurse into inner select
      Select inner = parenSelect.getSelect();
      if (inner != null) {
        collectSourcesFromSelect(inner, sourceTables);
      }
    }
    // LateralSubSelect, TableFunction and other exotic FromItem types are ignored
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CTE handling
  // ═══════════════════════════════════════════════════════════════════════════

  /** Extracts CTE bodies from a WithItem list, mapping CTE name → underlying source tables. */
  private Map<String, List<String>> extractCteBodies(List<WithItem> withItems) {
    if (withItems == null || withItems.isEmpty()) return new HashMap<>();
    return extractCteBodyList(withItems);
  }

  private Map<String, List<String>> extractCteBodyList(List<WithItem> withItems) {
    Map<String, List<String>> cteBodies = new HashMap<>();
    for (WithItem withItem : withItems) {
      // WithItem.getAlias() provides the CTE name in JSQLParser 4.9
      net.sf.jsqlparser.expression.Alias alias = withItem.getAlias();
      if (alias == null || alias.getName() == null || alias.getName().isBlank()) {
        LOG.debug("Skipping CTE with blank/null alias in SQL lineage extraction");
        continue;
      }
      String cteName = alias.getName();
      // WithItem extends ParenthesedSelect in JSQLParser 4.9.
      // getPlainSelect() may return a ParenthesedSelect (not PlainSelect), so use
      // getSelect() which always returns the inner Select regardless of type.
      List<String> cteSources = new ArrayList<>();
      Select innerSelect = withItem.getSelect();
      if (innerSelect != null) {
        collectSourcesFromSelect(innerSelect, cteSources);
      } else {
        // Fallback: try getPlainSelect() for simple CTEs
        PlainSelect plainSelect = withItem.getPlainSelect();
        if (plainSelect != null) {
          collectSourceTables(plainSelect, cteSources);
        }
      }
      cteBodies.put(cteName.toLowerCase(), cteSources);
    }
    return cteBodies;
  }

  /** Replaces CTE references with the CTE's underlying source tables. */
  private List<String> inlineCteReferences(
      List<String> sourceTables, Map<String, List<String>> cteBodies) {
    List<String> inlined = new ArrayList<>();
    for (String source : sourceTables) {
      String lowerSource = source.toLowerCase();
      if (cteBodies.containsKey(lowerSource)) {
        inlined.addAll(cteBodies.get(lowerSource));
      } else {
        inlined.add(source);
      }
    }
    return inlined;
  }

  /** Extracts the table name string from a JSQLParser Table object, including schema prefix. */
  private String getTableName(Table table) {
    if (table == null) return null;
    String schema = table.getSchemaName();
    String name = table.getName();
    if (name == null || name.isBlank()) return null;
    if (schema != null && !schema.isBlank()) {
      return schema + "." + name;
    }
    return name;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Table Resolution & Confidence Scoring (R8.4)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Resolves a table name from SQL to an OpenMetadata entity reference with confidence score.
   *
   * <p>Confidence scoring per R8.3:
   *
   * <ul>
   *   <li>1.0 — exact FQN match
   *   <li>0.8 — unique match within defaultService
   *   <li>0.5 — multiple matches found
   *   <li>0.3 — table name found in SQL but could not be resolved
   * </ul>
   *
   * @param referenceResolver resolves entity references by name — tests inject stubs to bypass
   *     {@code Entity.getEntityReferenceByName()}
   * @param searchRepoProvider provides the search repository — tests inject stubs to bypass
   *     {@code Entity.getSearchRepository()}
   */
  @VisibleForTesting
  ResolvedTable resolveTable(
      String tableName,
      String defaultService,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {
    if (tableName == null || tableName.isBlank()) {
      return new ResolvedTable(tableName, null, 0.3, "Table name is blank");
    }

    // Strategy 1: Try as exact FQN (e.g., "postgres.mydb.myschema.orders")
    try {
      EntityReference ref =
          referenceResolver.getEntityReferenceByName("table", tableName, Include.NON_DELETED);
      if (ref != null) {
        return new ResolvedTable(tableName, ref, 1.0, "Exact FQN match");
      }
    } catch (Exception e) {
      // Not an exact FQN — fall through
    }

    // Strategy 2: If defaultService is provided, try service.tableName
    if (defaultService != null && !defaultService.isBlank()) {
      String[] fqnCandidates = buildFqnCandidates(tableName, defaultService);
      List<EntityReference> matches = new ArrayList<>();
      for (String candidate : fqnCandidates) {
        try {
          EntityReference ref =
              referenceResolver.getEntityReferenceByName("table", candidate, Include.NON_DELETED);
          if (ref != null) {
            matches.add(ref);
          }
        } catch (Exception e) {
          // Not found — continue
        }
      }

      if (matches.size() == 1) {
        return new ResolvedTable(
            tableName, matches.get(0), 0.8, "Unique match within defaultService");
      } else if (matches.size() > 1) {
        return new ResolvedTable(
            tableName, matches.get(0), 0.5, "Multiple matches found within defaultService");
      }
    }

    // Strategy 3: Search across all services (lower confidence)
    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo != null) {
        String simpleName =
            tableName.contains(".")
                ? tableName.substring(tableName.lastIndexOf('.') + 1)
                : tableName;

        jakarta.ws.rs.core.Response response =
            searchRepo.search(
                new org.openmetadata.schema.search.SearchRequest()
                    .withQuery(simpleName)
                    .withIndex(searchRepo.getIndexOrAliasName("table"))
                    .withSize(5)
                    .withFrom(0)
                    .withFetchSource(true)
                    .withDeleted(false),
                null);

        if (response != null && response.getEntity() instanceof String responseStr) {
          com.fasterxml.jackson.databind.JsonNode node =
              org.openmetadata.schema.utils.JsonUtils.readTree(responseStr);
          com.fasterxml.jackson.databind.JsonNode hits = node.at("/hits/hits");
          if (hits.isArray() && !hits.isEmpty()) {
            List<EntityReference> found = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode hit : hits) {
              com.fasterxml.jackson.databind.JsonNode source = hit.get("_source");
              if (source != null && source.has("fullyQualifiedName")) {
                String fqn = source.get("fullyQualifiedName").asText();
                if (fqn.endsWith("." + simpleName) || fqn.equals(simpleName)) {
                  try {
                    EntityReference ref =
                        referenceResolver.getEntityReferenceByName(
                            "table", fqn, Include.NON_DELETED);
                    if (ref != null) {
                      found.add(ref);
                    }
                  } catch (Exception e) {
                    // Skip
                  }
                }
              }
            }
            if (found.size() == 1) {
              return new ResolvedTable(tableName, found.get(0), 0.5, "Unique search match");
            } else if (!found.isEmpty()) {
              return new ResolvedTable(
                  tableName, found.get(0), 0.5, "Multiple search matches found");
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Search resolution failed for table '{}': {}", tableName, e.getMessage());
    }

    // Unresolvable
    return new ResolvedTable(tableName, null, 0.3, "Table not found in catalog");
  }

  /**
   * Builds candidate FQNs by prepending the defaultService name.
   *
   * <p>For a table name like "myschema.orders" with defaultService "postgres", generates:
   * "postgres.myschema.orders"
   *
   * <p>Note: OpenMetadata table FQNs are typically service.database.schema.table (4 parts). For a
   * bare name like "orders", this generates only "postgres.orders" which won't match. In that
   * case, Strategy 3 (search-based resolution) provides the fallback.
   */
  @VisibleForTesting
  String[] buildFqnCandidates(String tableName, String defaultService) {
    return new String[] {defaultService + "." + tableName};
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Plan & Response Building
  // ═══════════════════════════════════════════════════════════════════════════

  /** Builds the lineage plan — an array of proposed edges with source/target details and confidence. */
  @VisibleForTesting
  List<Map<String, Object>> buildPlan(
      List<ResolvedLineageEdge> resolvedEdges, List<ResolvedTable> sourcesOnly) {
    List<Map<String, Object>> plan = new ArrayList<>();

    // Add from→to edges for statements with both source and target
    for (ResolvedLineageEdge edge : resolvedEdges) {
      for (ResolvedTable source : edge.sources) {
        Map<String, Object> planEntry = new LinkedHashMap<>();
        planEntry.put("sqlShape", edge.sqlShape);
        planEntry.put("from", serializeResolvedTable(source));
        planEntry.put("to", serializeResolvedTable(edge.target));
        planEntry.put("confidence", Math.min(source.confidence, edge.target.confidence));
        planEntry.put(
            "confidenceNote",
            String.format(
                "source=%.1f(%s), target=%.1f(%s)",
                source.confidence,
                source.resolutionNote,
                edge.target.confidence,
                edge.target.resolutionNote));
        if (edge.cteName != null) {
          planEntry.put("viaCte", edge.cteName);
        }
        plan.add(planEntry);
      }

      // Edge with sources but target is null (shouldn't happen now, but defensive)
      if (edge.sources.isEmpty() && edge.target.entityRef != null) {
        Map<String, Object> planEntry = new LinkedHashMap<>();
        planEntry.put("sqlShape", edge.sqlShape);
        planEntry.put("from", Map.of("tableName", "unknown", "confidence", 0.0));
        planEntry.put("to", serializeResolvedTable(edge.target));
        planEntry.put("confidence", edge.target.confidence);
        planEntry.put("confidenceNote", "No source tables extracted");
        plan.add(planEntry);
      }
    }

    // Add sourcesOnly entries (bare SELECT — no target, reported for discovery)
    if (!sourcesOnly.isEmpty()) {
      Map<String, Object> sourcesEntry = new LinkedHashMap<>();
      sourcesEntry.put("sqlShape", "SELECT");
      sourcesEntry.put("from", null);
      sourcesEntry.put("to", null);
      List<Map<String, Object>> sourceList = new ArrayList<>();
      for (ResolvedTable rt : sourcesOnly) {
        sourceList.add(serializeResolvedTable(rt));
      }
      sourcesEntry.put("sourcesOnly", sourceList);
      sourcesEntry.put("confidence", 0.0);
      sourcesEntry.put("confidenceNote", "Bare SELECT — sources identified but no target");
      plan.add(sourcesEntry);
    }

    return plan;
  }

  /** Serializes a ResolvedTable to a map for the response. */
  private Map<String, Object> serializeResolvedTable(ResolvedTable rt) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("tableName", rt.tableName);
    map.put("confidence", rt.confidence);
    map.put("resolutionNote", rt.resolutionNote);
    if (rt.entityRef != null) {
      map.put("id", rt.entityRef.getId().toString());
      map.put("entityType", rt.entityRef.getType());
      map.put("fullyQualifiedName", rt.entityRef.getName());
    }
    return map;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Apply logic (R8.6)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Test-friendly core — accepts functional interfaces for authorization, lineage edge
   * creation, and lineage repository access, completely bypassing static-initializer and
   * Entity-repository NPEs. When {@code lineageAppender} is null, the method lazily fetches the
   * lineage repository via {@code lineageRepoProvider} only when high-confidence edges exist.
   *
   * <p>Tests inject a no-op {@link LineageAuthorizer}, a capturing {@link LineageAppender},
   * and a stub {@link McpEntityBridge.LineageRepositoryProvider}, avoiding any need for
   * {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  ApplyResult applyHighConfidenceEdges(
      List<Map<String, Object>> plan,
      String updatedBy,
      LineageAuthorizer lineageAuthorizer,
      LineageAppender lineageAppender,
      McpEntityBridge.LineageRepositoryProvider lineageRepoProvider) {

    List<Map<String, Object>> applied = new ArrayList<>();
    List<Map<String, Object>> requiresConfirmation = new ArrayList<>();

    // First pass: classify edges into high-confidence (candidates for apply) and
    // low-confidence (requiresConfirmation), without touching lineageRepo yet.
    // This lets us skip the lineageRepo fetch entirely if no edges qualify.
    List<Map<String, Object>> highConfEdges = new ArrayList<>();

    for (Map<String, Object> edgeSpec : plan) {
      @SuppressWarnings("unchecked")
      Map<String, Object> toSpec = (Map<String, Object>) edgeSpec.get("to");

      // Skip sourcesOnly entries (no target) — they can't produce lineage edges
      if (toSpec == null) {
        continue;
      }

      double confidence = ((Number) edgeSpec.getOrDefault("confidence", 0.0)).doubleValue();
      @SuppressWarnings("unchecked")
      Map<String, Object> fromSpec = (Map<String, Object>) edgeSpec.get("from");

      if (confidence >= 0.8 && fromSpec.get("id") != null && toSpec.get("id") != null) {
        highConfEdges.add(edgeSpec);
      } else {
        // Low-confidence or missing IDs: return under requiresConfirmation
        Map<String, Object> confirmEdge = new LinkedHashMap<>(edgeSpec);
        confirmEdge.put("status", "requiresConfirmation");
        requiresConfirmation.add(confirmEdge);
      }
    }

    // Lazily fetch lineageRepo only when there are high-confidence edges to apply
    if (!highConfEdges.isEmpty()) {
      // If no appender was injected, lazily fetch the production repo
      final LineageAppender effectiveAppender;
      if (lineageAppender != null) {
        effectiveAppender = lineageAppender;
      } else {
        var lineageRepo = lineageRepoProvider.getLineageRepository();
        if (lineageRepo == null) {
          return ApplyResult.error(
              "Lineage repository not initialized — cannot create lineage edges.");
        }
        effectiveAppender = lineageRepo::addLineage;
      }

      for (Map<String, Object> edgeSpec : highConfEdges) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fromSpec = (Map<String, Object>) edgeSpec.get("from");
        @SuppressWarnings("unchecked")
        Map<String, Object> toSpec = (Map<String, Object>) edgeSpec.get("to");

        try {
          String fromType = String.valueOf(fromSpec.get("entityType"));
          String toType = String.valueOf(toSpec.get("entityType"));

          // Authorize via the injected functional interface (bypasses ResourceContext in tests)
          lineageAuthorizer.authorize(fromType);
          lineageAuthorizer.authorize(toType);

          EntityReference fromRef =
              new EntityReference()
                  .withId(java.util.UUID.fromString(String.valueOf(fromSpec.get("id"))))
                  .withType(fromType)
                  .withName(String.valueOf(fromSpec.get("fullyQualifiedName")));
          EntityReference toRef =
              new EntityReference()
                  .withId(java.util.UUID.fromString(String.valueOf(toSpec.get("id"))))
                  .withType(toType)
                  .withName(String.valueOf(toSpec.get("fullyQualifiedName")));

          AddLineage lineage =
              new AddLineage()
                  .withEdge(new EntitiesEdge().withFromEntity(fromRef).withToEntity(toRef));
          effectiveAppender.append(lineage, updatedBy);

          Map<String, Object> appliedEdge = new LinkedHashMap<>(edgeSpec);
          appliedEdge.put("status", "applied");
          applied.add(appliedEdge);
        } catch (Exception e) {
          LOG.warn("Failed to create lineage edge: {}", e.getMessage());
          Map<String, Object> failedEdge = new LinkedHashMap<>(edgeSpec);
          failedEdge.put("status", "failed");
          failedEdge.put("error", e.getMessage());
          applied.add(failedEdge);
        }
      }
    }

    return ApplyResult.success(applied, requiresConfirmation);
  }

  /** Builds the final response envelope. */
  private Map<String, Object> buildResponse(
      List<Map<String, Object>> plan,
      List<Map<String, Object>> applied,
      List<Map<String, Object>> requiresConfirmation,
      List<ResolvedLineageEdge> resolvedEdges,
      List<ResolvedTable> sourcesOnly,
      boolean wasApplied) {

    String primaryShape;
    if (!resolvedEdges.isEmpty()) {
      primaryShape = resolvedEdges.get(0).sqlShape;
    } else if (!sourcesOnly.isEmpty()) {
      primaryShape = "SELECT";
    } else {
      primaryShape = "unknown";
    }

    // Count only real edges (not sourcesOnly entries)
    long realEdgeCount =
        plan.stream().filter(e -> e.get("from") != null && e.get("to") != null).count();

    Map<String, Object> result = new LinkedHashMap<>(createEmptyEnvelope());
    result.put("sqlShape", primaryShape);
    result.put("plan", plan);
    result.put("edgeCount", realEdgeCount);

    if (!sourcesOnly.isEmpty()) {
      result.put("sourcesOnlyCount", sourcesOnly.size());
    }

    if (wasApplied) {
      result.put("apply", true);
      result.put("applied", applied);
      result.put("appliedCount", applied.size());
      result.put("requiresConfirmation", requiresConfirmation);
      result.put("requiresConfirmationCount", requiresConfirmation.size());
    } else {
      result.put("apply", false);
    }

    // Narrative
    String narrative =
        generateNarrative(plan, applied, requiresConfirmation, primaryShape, wasApplied);
    result.put("narrative", narrative);

    return result;
  }

  /** Generates a Markdown narrative summarizing the lineage extraction result. */
  @VisibleForTesting
  String generateNarrative(
      List<Map<String, Object>> plan,
      List<Map<String, Object>> applied,
      List<Map<String, Object>> requiresConfirmation,
      String sqlShape,
      boolean wasApplied) {

    // Count only real edges
    long realEdges =
        plan.stream().filter(e -> e.get("from") != null && e.get("to") != null).count();
    long sourcesOnlyCount =
        plan.stream()
            .filter(e -> "SELECT".equals(e.get("sqlShape")) && e.get("from") == null)
            .count();

    StringBuilder sb = new StringBuilder();
    sb.append("## SQL → Lineage Analysis\n\n");
    sb.append(String.format("**SQL shape:** %s\n", sqlShape));
    sb.append(String.format("**Edges found:** %d\n", realEdges));
    if (sourcesOnlyCount > 0) {
      sb.append(String.format("**Source tables discovered (no target):** %d\n", sourcesOnlyCount));
    }
    sb.append("\n");

    if (realEdges == 0 && sourcesOnlyCount == 0) {
      sb.append("No lineage edges could be extracted from the SQL.\n");
      return sb.toString();
    }

    // Summarize confidence distribution for real edges only
    long highConfidence =
        plan.stream()
            .filter(e -> e.get("from") != null && e.get("to") != null)
            .filter(e -> ((Number) e.getOrDefault("confidence", 0.0)).doubleValue() >= 0.8)
            .count();
    long lowConfidence = realEdges - highConfidence;
    if (realEdges > 0) {
      sb.append(String.format("- %d high-confidence edges (≥ 0.8)\n", highConfidence));
      if (lowConfidence > 0) {
        sb.append(
            String.format(
                "- %d low-confidence edges (< 0.8) — require manual confirmation\n",
                lowConfidence));
      }
      sb.append("\n");
    }

    if (wasApplied) {
      long appliedCount = applied.stream().filter(e -> "applied".equals(e.get("status"))).count();
      long failedCount = applied.stream().filter(e -> "failed".equals(e.get("status"))).count();
      sb.append(
          String.format("**Applied:** %d edges created, %d failed\n", appliedCount, failedCount));
      if (!requiresConfirmation.isEmpty()) {
        sb.append(
            String.format(
                "**Requires confirmation:** %d edges skipped (confidence < 0.8). "
                    + "Resolve these manually via `create_lineage`.\n",
                requiresConfirmation.size()));
      }
    } else if (realEdges > 0) {
      sb.append("Set `apply=true` to create lineage edges for high-confidence matches (≥ 0.8). ");
      sb.append("Low-confidence edges will be returned under `requiresConfirmation`.\n");
    }

    // Cap at 1200 chars
    if (sb.length() > 1200) {
      sb.setLength(1197);
      sb.append("...");
    }
    return sb.toString();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════════

  private String extractRequiredString(Map<String, Object> params, String key) {
    Object value = params.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' is required and cannot be empty", key));
    }
    return value.toString();
  }

  private boolean parseBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
    Object value = params.get(key);
    if (value == null) return defaultValue;
    if (value instanceof Boolean b) return b;
    if (value instanceof String s) return "true".equalsIgnoreCase(s);
    return defaultValue;
  }

  private Map<String, Object> errorResult(String message) {
    return EnvelopeBuilder.create()
        .results(List.of())
        .warning(message)
        .narrative(String.format("⚠️ %s", message))
        .build();
  }

  /** Creates an empty envelope base map for consistent response shapes. */
  private Map<String, Object> createEmptyEnvelope() {
    return EnvelopeBuilder.create().results(List.of()).build();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Internal model classes
  // ═══════════════════════════════════════════════════════════════════════════

  /** Raw lineage edge extracted from SQL before resolution. */
  @VisibleForTesting
  static class SqlLineageEdge {
    final String sqlShape;
    final String targetTable; // null for bare SELECT
    final List<String> sourceTables;
    final String cteName; // non-null if target was a CTE name

    SqlLineageEdge(String sqlShape, String targetTable, List<String> sourceTables) {
      this(sqlShape, targetTable, sourceTables, null);
    }

    SqlLineageEdge(String sqlShape, String targetTable, List<String> sourceTables, String cteName) {
      this.sqlShape = sqlShape;
      this.targetTable = targetTable;
      this.sourceTables = sourceTables;
      this.cteName = cteName;
    }
  }

  /** Table name resolved against the catalog with confidence score. */
  @VisibleForTesting
  static class ResolvedTable {
    final String tableName;
    final EntityReference entityRef; // null if unresolvable
    final double confidence;
    final String resolutionNote;

    ResolvedTable(
        String tableName, EntityReference entityRef, double confidence, String resolutionNote) {
      this.tableName = tableName;
      this.entityRef = entityRef;
      this.confidence = confidence;
      this.resolutionNote = resolutionNote;
    }
  }

  /** Lineage edge with resolved source and target tables. */
  @VisibleForTesting
  static class ResolvedLineageEdge {
    final String sqlShape;
    final ResolvedTable target;
    final List<ResolvedTable> sources;
    final String cteName;

    ResolvedLineageEdge(
        String sqlShape, ResolvedTable target, List<ResolvedTable> sources, String cteName) {
      this.sqlShape = sqlShape;
      this.target = target;
      this.sources = sources;
      this.cteName = cteName;
    }
  }

  /**
   * Functional interface for authorizing lineage edit operations. Allows tests to inject a
   * no-op without constructing {@code OperationContext}/{@code ResourceContext}, which require
   * {@code Entity.getEntityRepository()} to be initialized.
   *
   * <p>Production implementation: {@code (entityType) -> authorizer.authorize(securityContext,
   * new OperationContext(entityType, EDIT_LINEAGE), new ResourceContext<>(entityType))}
   *
   * <p>Note: This is a specialized single-operation version of {@link
   * McpEntityBridge.McpAuthorizer}. New tools should prefer {@link McpEntityBridge.McpAuthorizer}
   * which accepts the operation parameter. This interface is retained for backward compatibility
   * with existing tests.
   */
  @FunctionalInterface
  @VisibleForTesting
  interface LineageAuthorizer {
    void authorize(String entityType) throws Exception;
  }

  /**
   * Functional interface for creating lineage edges. Allows tests to inject a mock without
   * referencing {@code LineageRepository.class} directly, sidestepping its static initializer
   * ({@code Entity.getSearchRepository().getSearchClient()} which is null at test time).
   */
  @FunctionalInterface
  @VisibleForTesting
  interface LineageAppender {
    void append(AddLineage lineage, String updatedBy) throws Exception;
  }

  /** Result of applying high-confidence lineage edges. */
  @VisibleForTesting
  static class ApplyResult {
    final List<Map<String, Object>> applied;
    final List<Map<String, Object>> requiresConfirmation;

    /** Non-null when a fatal error prevented apply (e.g., lineageRepo not initialized). */
    final String error;

    ApplyResult(
        List<Map<String, Object>> applied,
        List<Map<String, Object>> requiresConfirmation,
        String error) {
      this.applied = applied;
      this.requiresConfirmation = requiresConfirmation;
      this.error = error;
    }

    static ApplyResult error(String error) {
      return new ApplyResult(List.of(), List.of(), error);
    }

    static ApplyResult success(
        List<Map<String, Object>> applied, List<Map<String, Object>> requiresConfirmation) {
      return new ApplyResult(applied, requiresConfirmation, null);
    }
  }
}
