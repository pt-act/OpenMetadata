/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.mcp.tools;

import static org.openmetadata.service.search.SearchUtils.mapEntityTypesToIndexNames;
import static org.openmetadata.service.security.DefaultAuthorizer.getSubjectContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.search.SearchRequest;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

/**
 * Governance Coverage Scanner — reports what percentage of data assets have key governance
 * attributes populated (owner, tier, domain, description, PII tags).
 *
 * <p>Uses OpenSearch aggregation queries to compute per-attribute coverage percentages, grouped
 * by domain or service scope. Includes PII candidate detection via regex on column names lacking
 * PII tags, and a gap report with top offenders.
 *
 * <p>Spec reference: Expansions Group E6 (R6.1–R6.2).
 */
@Slf4j
public class ScanGovernanceCoverageTool implements McpTool {

  private static final int MAX_PAYLOAD_BYTES = 8 * 1024; // 8 KB
  private static final int MAX_TOP_OFFENDERS = 10;

  /** Per-user rate limit: minimum milliseconds between consecutive calls. 5 minutes. */
  @VisibleForTesting static final long RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L;

  /** Tracks the last call timestamp per user for rate limiting. */
  @VisibleForTesting
  static final ConcurrentHashMap<String, Long> USER_LAST_CALL_MS = new ConcurrentHashMap<>();

  /**
   * Atomically checks and acquires the per-user rate limit.
   *
   * <p>Returns {@code null} if this call is allowed (first call or cooldown has elapsed), or the
   * timestamp of the original call if the user is within the cooldown window. Uses {@code
   * ConcurrentHashMap.compute()} to guarantee that exactly one concurrent caller per user gets
   * {@code null}.
   *
   * <p>Returning the blocking timestamp directly (instead of a boolean) avoids a TOCTOU race where
   * the caller would need a second map lookup to compute remaining-seconds.
   */
  @VisibleForTesting
  static Long tryAcquireRateLimit(String userId) {
    long now = System.currentTimeMillis();
    Long[] blockedAt = {null};
    USER_LAST_CALL_MS.compute(
        userId,
        (k, lastCall) -> {
          if (lastCall != null && (now - lastCall) < RATE_LIMIT_COOLDOWN_MS) {
            blockedAt[0] = lastCall; // signal rejection
            return lastCall; // don't update timestamp
          }
          return now; // record this call
        });
    return blockedAt[0]; // null = allowed, non-null = blocked (value = original call timestamp)
  }

  /** Evicts stale entries from the rate limit map to prevent unbounded memory growth. */
  @VisibleForTesting
  static void evictStaleEntries(long now) {
    USER_LAST_CALL_MS.entrySet().removeIf(e -> (now - e.getValue()) > RATE_LIMIT_COOLDOWN_MS * 2);
  }

  /** Governance attributes to check. */
  private static final List<String> GOVERNANCE_ATTRIBUTES =
      List.of("owner", "tier", "domain", "description", "piiTags");

  /**
   * PII candidate regex patterns matched against column names. Column names matching these
   * patterns that lack PII tags are flagged as candidates. Designed for high specificity (low
   * false-positive rate).
   */
  private static final List<Pattern> PII_PATTERNS =
      List.of(
          Pattern.compile("(?i)(email|e_mail|email_address)"),
          Pattern.compile("(?i)(ssn|social_security|social_sec)"),
          Pattern.compile("(?i)(phone|phone_number|telephone|mobile|cell_number)"),
          Pattern.compile("(?i)(passport|passport_number|passport_no)"),
          Pattern.compile("(?i)(credit_card|card_number|cc_number|ccn)"),
          Pattern.compile("(?i)(dob|date_of_birth|birth_date)"),
          Pattern.compile("(?i)(sin|national_insurance|tax_id|taxpayer_id)"),
          Pattern.compile("(?i)(ip_address|ipv4|ipv6)"),
          Pattern.compile("(?i)(password|passwd|pwd)"),
          Pattern.compile("(?i)(account_number|bank_account|routing_number)"));

  /** Words that should NOT trigger PII detection even if they contain a PII substring. */
  private static final List<Pattern> PII_EXCLUSION_PATTERNS =
      List.of(
          Pattern.compile("(?i)email_template"),
          Pattern.compile("(?i)phone_type"),
          Pattern.compile("(?i)password_policy"),
          Pattern.compile("(?i)ip_address_range"));

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
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultSearchRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link
   * org.openmetadata.service.Entity} static method calls and authorizer delegation, eliminating
   * the need for {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider)
      throws IOException {

    // Parse scope — optional domain/service filter
    String scopeType = null;
    String scopeValue = null;
    Object scopeObj = params.get("scope");
    if (scopeObj instanceof Map<?, ?> scopeMap) {
      scopeType = (String) scopeMap.get("type");
      scopeValue = (String) scopeMap.get("value");
    } else if (scopeObj instanceof String scopeStr && !scopeStr.isBlank()) {
      scopeType = "domain";
      scopeValue = scopeStr;
    }

    String entityType = (String) params.getOrDefault("entityType", "table");

    // Authorize first — rate limit only applies to authenticated users
    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    // Rate limit: one call per user per 5-minute window
    String userId = securityContext.getUserPrincipal().getName();
    Long blockedAt = tryAcquireRateLimit(userId);
    if (blockedAt != null) {
      long remainingSeconds =
          Math.max(1, (RATE_LIMIT_COOLDOWN_MS - (System.currentTimeMillis() - blockedAt)) / 1000);
      return Map.of(
          "error",
          "Rate limit exceeded: please wait "
              + remainingSeconds
              + "s before calling scan_governance_coverage again",
          "retryAfterSeconds",
          remainingSeconds,
          "statusCode",
          429);
    }

    // Evict stale entries to prevent unbounded memory growth
    evictStaleEntries(System.currentTimeMillis());

    LOG.info(
        "Scanning governance coverage: entityType={}, scope={}/{}",
        entityType,
        scopeType,
        scopeValue);

    // Step 1: Compute coverage percentages per attribute
    Map<String, Double> coverage = new LinkedHashMap<>();
    Map<String, Map<String, Object>> gaps = new LinkedHashMap<>();

    var searchRepo = searchRepoProvider.getSearchRepository();

    for (String attribute : GOVERNANCE_ATTRIBUTES) {
      CoverageResult attrResult =
          computeCoverage(
              searchRepo, securityContext, entityType, attribute, scopeType, scopeValue);
      coverage.put(attribute, attrResult.coveragePercent);

      // Only include gap details for attributes below 100%
      if (attrResult.missingCount > 0) {
        Map<String, Object> gapInfo = new LinkedHashMap<>();
        gapInfo.put("missingCount", attrResult.missingCount);
        gapInfo.put("presentCount", attrResult.presentCount);
        gapInfo.put("topOffenders", attrResult.topOffenders);
        gaps.put(attribute, gapInfo);
      }
    }

    // Step 2: PII candidate detection
    List<Map<String, Object>> piiCandidates =
        detectPiiCandidates(searchRepo, securityContext, entityType, scopeType, scopeValue);

    if (!piiCandidates.isEmpty()) {
      Map<String, Object> piiGap = new LinkedHashMap<>();
      piiGap.put("missingCount", piiCandidates.size());
      piiGap.put("candidateColumns", piiCandidates);
      gaps.put("piiTags", piiGap);
    }

    // Step 3: Build response
    Map<String, Object> scopeMap = null;
    if (scopeType != null && scopeValue != null) {
      scopeMap = new LinkedHashMap<>();
      scopeMap.put("type", scopeType);
      scopeMap.put("value", scopeValue);
    }

    // Narrative
    String narrative =
        generateNarrative(coverage, scopeType, scopeValue, entityType, piiCandidates);

    // Results entry: minimal — detailed data lives at top level per spec R6.1
    Map<String, Object> resultEntry = new LinkedHashMap<>();
    resultEntry.put("type", "governanceCoverageSummary");

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(List.of(resultEntry))
            .pagination(0, 1, 1)
            .narrative(narrative);

    Map<String, Object> result = new LinkedHashMap<>(envelope.build());
    // Hoist key fields to top level per spec R6.1
    result.put("attributes", GOVERNANCE_ATTRIBUTES);
    result.put("coverage", coverage);
    result.put("gaps", gaps);
    result.put("entityType", entityType);
    if (scopeMap != null) {
      result.put("scope", scopeMap);
    }

    // Enforce byte cap
    result = enforceByteCap(result);

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
        "ScanGovernanceCoverageTool does not support limits enforcement.");
  }

  // ====================== Coverage computation ======================

  /** Holds coverage computation results for a single attribute. */
  @VisibleForTesting
  static class CoverageResult {
    double coveragePercent;
    int presentCount;
    int missingCount;
    List<Map<String, Object>> topOffenders = new ArrayList<>();
  }

  /**
   * Computes coverage for a single governance attribute using OpenSearch queries.
   *
   * <p>Strategy: Run two queries — one for entities WITH the attribute, one for entities WITHOUT.
   * Then compute coverage percentage. For missing entities, fetch top offenders.
   */
  @VisibleForTesting
  CoverageResult computeCoverage(
      SearchRepository searchRepo,
      CatalogSecurityContext securityContext,
      String entityType,
      String attribute,
      String scopeType,
      String scopeValue) {

    CoverageResult covResult = new CoverageResult();

    if (searchRepo == null) {
      LOG.warn("Search repository not initialized — cannot compute coverage for {}", attribute);
      return covResult;
    }

    try {
      SubjectContext subjectContext = getSubjectContext(securityContext);

      // Query for total count in scope
      String totalQueryFilter = buildScopeFilter(entityType, scopeType, scopeValue);
      int total = countEntities(searchRepo, subjectContext, entityType, totalQueryFilter);

      if (total == 0) {
        return covResult;
      }

      // Query for count WITH the attribute present
      String presentQueryFilter =
          buildAttributePresentFilter(entityType, attribute, scopeType, scopeValue);
      int present = countEntities(searchRepo, subjectContext, entityType, presentQueryFilter);

      covResult.presentCount = present;
      covResult.missingCount = total - present;
      covResult.coveragePercent = (double) present / total;

      // Round to 4 decimal places (e.g. 0.8200)
      covResult.coveragePercent = Math.round(covResult.coveragePercent * 10000.0) / 10000.0;

      // Fetch top offenders (entities missing the attribute)
      if (covResult.missingCount > 0) {
        String missingQueryFilter =
            buildAttributeMissingFilter(entityType, attribute, scopeType, scopeValue);
        covResult.topOffenders =
            searchTopOffenders(
                searchRepo, subjectContext, entityType, missingQueryFilter, MAX_TOP_OFFENDERS);
      }
    } catch (Exception e) {
      LOG.warn("Failed to compute coverage for attribute '{}': {}", attribute, e.getMessage());
    }

    return covResult;
  }

  // ====================== PII candidate detection (R6.2) ======================

  /**
   * Detects columns that likely contain PII based on name patterns but lack PII tags.
   *
   * <p>Searches for table columns whose names match PII regex patterns but do not have PII-related
   * tags. Returns candidate columns without applying tags — this is a read-only detection.
   */
  @VisibleForTesting
  List<Map<String, Object>> detectPiiCandidates(
      SearchRepository searchRepo,
      CatalogSecurityContext securityContext,
      String entityType,
      String scopeType,
      String scopeValue) {

    List<Map<String, Object>> candidates = new ArrayList<>();

    if (searchRepo == null) {
      LOG.warn("Search repository not initialized — cannot detect PII candidates");
      return candidates;
    }

    // Only table entities have columns for PII detection
    if (!"table".equals(entityType)) {
      return candidates;
    }

    try {

      // Search for tables in scope, fetching column info
      StringBuilder mustClauses = new StringBuilder();
      mustClauses.append("{\"term\":{\"entityType\":\"table\"}}");

      if ("domain".equals(scopeType) && scopeValue != null) {
        mustClauses
            .append(",{\"term\":{\"domains.name\":\"")
            .append(escapeJson(scopeValue))
            .append("\"}}");
      } else if ("service".equals(scopeType) && scopeValue != null) {
        mustClauses
            .append(",{\"term\":{\"service.name\":\"")
            .append(escapeJson(scopeValue))
            .append("\"}}");
      }

      String queryFilter =
          "{\"bool\":{\"must\":["
              + mustClauses
              + "],\"must_not\":[{\"term\":{\"deleted\":true}}]}}";

      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(entityType)))
              .withQueryFilter(queryFilter)
              .withSize(100) // Sample up to 100 tables for PII detection
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(false);

      SubjectContext subjectContext = getSubjectContext(securityContext);
      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) {
        return candidates;
      }

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      Map<String, Object> topHits = safeGetMap(searchResponse.get("hits"));
      if (topHits == null) {
        return candidates;
      }

      List<Object> hits = safeGetList(topHits.get("hits"));
      if (hits == null) {
        return candidates;
      }

      for (Object hitObj : hits) {
        Map<String, Object> hit = safeGetMap(hitObj);
        if (hit == null) continue;

        Map<String, Object> source = safeGetMap(hit.get("_source"));
        if (source == null) continue;

        String tableFqn = (String) source.get("fullyQualifiedName");
        if (tableFqn == null) continue;

        // Check table-level tags for PII
        boolean tableHasPiiTags = hasPiiTags(source.get("tags"));

        // Check columns for PII candidates
        Object columnsObj = source.get("columns");
        if (columnsObj instanceof List<?> columns) {
          for (Object colObj : columns) {
            if (!(colObj instanceof Map<?, ?> column)) continue;
            String colName = (String) ((Map<?, ?>) column).get("name");
            if (colName == null) continue;

            // Skip if excluded by exclusion patterns
            if (matchesAnyPattern(colName, PII_EXCLUSION_PATTERNS)) continue;

            // Check if column name matches PII patterns
            if (matchesAnyPattern(colName, PII_PATTERNS)) {
              // Check if column has PII tags
              boolean colHasPiiTags = hasPiiTags(((Map<?, ?>) column).get("tags"));

              // If neither the column nor the table has PII tags, flag it
              if (!colHasPiiTags && !tableHasPiiTags) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("tableFqn", tableFqn);
                candidate.put("columnName", colName);
                candidate.put("matchedPattern", getMatchedPatternName(colName));
                candidates.add(candidate);

                if (candidates.size() >= MAX_TOP_OFFENDERS) {
                  return candidates;
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to detect PII candidates: {}", e.getMessage());
    }

    return candidates;
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(
      Map<String, Double> coverage,
      String scopeType,
      String scopeValue,
      String entityType,
      List<Map<String, Object>> piiCandidates) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Governance Coverage Report\n\n");

    if (scopeType != null && scopeValue != null) {
      sb.append("**Scope:** ").append(scopeType).append(": `").append(scopeValue).append("`\n\n");
    }

    sb.append("**Entity type:** ").append(entityType).append("\n\n");

    // Find lowest-coverage attribute
    String lowestAttr = null;
    double lowestValue = 1.1;
    for (Map.Entry<String, Double> entry : coverage.entrySet()) {
      if (entry.getValue() < lowestValue) {
        lowestValue = entry.getValue();
        lowestAttr = entry.getKey();
      }
    }

    // Coverage summary
    sb.append("### Coverage Summary\n\n");
    sb.append("| Attribute | Coverage |\n");
    sb.append("|-----------|----------|\n");
    for (Map.Entry<String, Double> entry : coverage.entrySet()) {
      String pct = String.format("%.0f%%", entry.getValue() * 100);
      sb.append("| ").append(entry.getKey()).append(" | ").append(pct).append(" |\n");
    }
    sb.append("\n");

    // Check if all values are zero (search repo unavailable or no entities)
    boolean allZero = true;
    for (Double val : coverage.values()) {
      if (val > 0.0) {
        allZero = false;
        break;
      }
    }
    if (allZero) {
      sb.append(
          "No coverage data available — search repository may not be initialized or no entities found.\n");
      return sb.toString();
    } else if (lowestAttr != null && lowestValue < 1.0) {
      sb.append("**Lowest coverage:** `")
          .append(lowestAttr)
          .append("` at ")
          .append(String.format("%.0f%%", lowestValue * 100))
          .append(".\n\n");
    } else {
      sb.append("All governance attributes are at 100% coverage! 🎉\n");
      return sb.toString();
    }

    // PII candidates
    if (!piiCandidates.isEmpty()) {
      sb.append("### PII Candidates\n\n");
      sb.append(piiCandidates.size())
          .append(" column(s) match PII name patterns but lack PII tags:\n\n");
      int shown = Math.min(piiCandidates.size(), 5);
      for (int i = 0; i < shown; i++) {
        Map<String, Object> c = piiCandidates.get(i);
        sb.append("- `")
            .append(c.get("tableFqn"))
            .append(".")
            .append(c.get("columnName"))
            .append("` (matched: ")
            .append(c.get("matchedPattern"))
            .append(")\n");
      }
      if (piiCandidates.size() > shown) {
        sb.append("... and ").append(piiCandidates.size() - shown).append(" more.\n");
      }
      sb.append("\n");
    }

    sb.append("### Recommendation\n");
    sb.append("Use `find_unowned_assets` to prioritize ownership assignment for the `owner` gap, ");
    sb.append("and `suggest_owner_for` to identify the best owner candidate. ");
    sb.append("For PII candidates, apply appropriate PII tags via `patch_entity`.");

    return sb.toString();
  }

  // ====================== Query builders ======================

  /** Builds a scope filter clause (base filter for entityType + optional domain/service). */
  private String buildScopeFilter(String entityType, String scopeType, String scopeValue) {
    StringBuilder must = new StringBuilder();
    must.append("{\"term\":{\"entityType\":\"").append(escapeJson(entityType)).append("\"}}");

    if ("domain".equals(scopeType) && scopeValue != null) {
      must.append(",{\"term\":{\"domains.name\":\"").append(escapeJson(scopeValue)).append("\"}}");
    } else if ("service".equals(scopeType) && scopeValue != null) {
      must.append(",{\"term\":{\"service.name\":\"").append(escapeJson(scopeValue)).append("\"}}");
    }

    return "{\"bool\":{\"must\":[" + must + "],\"must_not\":[{\"term\":{\"deleted\":true}}]}}";
  }

  /**
   * Builds a filter for entities that HAVE a given governance attribute populated.
   *
   * <p>Each attribute requires a different OpenSearch query strategy:
   * <ul>
   *   <li>owner: nested query — owners array has at least one element</li>
   *   <li>tier: term exists — tier.tagFQN is present</li>
   *   <li>domain: nested query — domains array has at least one element</li>
   *   <li>description: exists query — description field is present and non-empty</li>
   *   <li>piiTags: term query — tags contain PII classification</li>
   * </ul>
   */
  private String buildAttributePresentFilter(
      String entityType, String attribute, String scopeType, String scopeValue) {

    // Start with scope filter
    String scopeFilter = buildScopeFilter(entityType, scopeType, scopeValue);

    // Add attribute-present condition
    String presentClause =
        switch (attribute) {
          case "owner" -> ",{\"nested\":{\"path\":\"owners\",\"query\":{\"match_all\":{}}}}";
          case "tier" -> ",{\"exists\":{\"field\":\"tier.tagFQN\"}}";
          case "domain" -> ",{\"nested\":{\"path\":\"domains\",\"query\":{\"match_all\":{}}}}";
          case "description" -> ",{\"exists\":{\"field\":\"description\"}}";
          case "piiTags" -> ",{\"prefix\":{\"tags.tagFQN\":\"PII\"}}";
          default -> "";
        };

    // Insert present clause into the must array of the scope filter.
    // CAUTION: This relies on the exact JSON structure produced by buildScopeFilter() —
    // the must array closes with ],"must_not". If that format changes, this will silently
    // produce malformed JSON.
    return scopeFilter.replace("],\"must_not\"", presentClause + "],\"must_not\"");
  }

  /** Builds a filter for entities MISSING a given governance attribute. */
  private String buildAttributeMissingFilter(
      String entityType, String attribute, String scopeType, String scopeValue) {

    // Start with scope filter
    String scopeFilter = buildScopeFilter(entityType, scopeType, scopeValue);

    // Add attribute-missing condition (must_not)
    String missingClause =
        switch (attribute) {
          case "owner" -> ",{\"nested\":{\"path\":\"owners\",\"query\":{\"match_all\":{}}}}";
          case "tier" -> ",{\"exists\":{\"field\":\"tier.tagFQN\"}}";
          case "domain" -> ",{\"nested\":{\"path\":\"domains\",\"query\":{\"match_all\":{}}}}";
          case "description" -> ",{\"exists\":{\"field\":\"description\"}}";
          case "piiTags" -> ",{\"prefix\":{\"tags.tagFQN\":\"PII\"}}";
          default -> "";
        };

    // Insert into must_not array.
    // CAUTION: This relies on the exact JSON structure produced by buildScopeFilter() —
    // the must_not array contains [{"term":{"deleted":true}}]. If that format changes,
    // this will silently produce malformed JSON.
    return scopeFilter.replace(
        "[{\"term\":{\"deleted\":true}}]", "{\"term\":{\"deleted\":true}}" + missingClause);
  }

  // ====================== Search helpers ======================

  /** Counts entities matching a query filter by running a size=0 search. */
  private int countEntities(
      SearchRepository searchRepo,
      SubjectContext subjectContext,
      String entityType,
      String queryFilter) {
    try {
      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(entityType)))
              .withQueryFilter(queryFilter)
              .withSize(0)
              .withFrom(0)
              .withFetchSource(false)
              .withDeleted(false);

      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) return 0;

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      Map<String, Object> topHits = safeGetMap(searchResponse.get("hits"));
      if (topHits == null) return 0;

      Object totalObj = topHits.get("total");
      if (totalObj instanceof Map<?, ?> totalMap) {
        Object valueObj = totalMap.get("value");
        if (valueObj instanceof Number num) return num.intValue();
      } else if (totalObj instanceof Number num) {
        return num.intValue();
      }
      return 0;
    } catch (Exception e) {
      LOG.warn("Failed to count entities: {}", e.getMessage());
      return 0;
    }
  }

  /** Searches for top-offender entities missing an attribute. */
  private List<Map<String, Object>> searchTopOffenders(
      SearchRepository searchRepo,
      SubjectContext subjectContext,
      String entityType,
      String queryFilter,
      int limit) {
    List<Map<String, Object>> offenders = new ArrayList<>();
    try {
      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(entityType)))
              .withQueryFilter(queryFilter)
              .withSize(limit)
              .withFrom(0)
              .withFetchSource(true)
              .withDeleted(false);

      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) return offenders;

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      Map<String, Object> topHits = safeGetMap(searchResponse.get("hits"));
      if (topHits == null) return offenders;

      List<Object> hits = safeGetList(topHits.get("hits"));
      if (hits == null) return offenders;

      for (Object hitObj : hits) {
        Map<String, Object> hit = safeGetMap(hitObj);
        if (hit == null) continue;

        Map<String, Object> source = safeGetMap(hit.get("_source"));
        if (source == null) continue;

        Map<String, Object> offender = new LinkedHashMap<>();
        offender.put("fullyQualifiedName", source.get("fullyQualifiedName"));
        offender.put("entityType", source.get("entityType"));
        offender.put("name", source.get("name"));
        if (source.get("service") instanceof Map<?, ?> svc) {
          offender.put("service", svc.get("name"));
        }
        offenders.add(offender);
      }
    } catch (Exception e) {
      LOG.warn("Failed to search top offenders: {}", e.getMessage());
    }
    return offenders;
  }

  // ====================== PII detection helpers ======================

  /** Checks if a tags object contains PII-related tags. */
  @VisibleForTesting
  static boolean hasPiiTags(Object tagsObj) {
    if (!(tagsObj instanceof List<?> tags)) return false;
    for (Object tagObj : tags) {
      if (!(tagObj instanceof Map<?, ?> tag)) continue;
      Object tagFqn = tag.get("tagFQN");
      if (tagFqn instanceof String fqn && fqn.startsWith("PII")) {
        return true;
      }
    }
    return false;
  }

  /** Checks if a string matches any of the given patterns. */
  private static boolean matchesAnyPattern(String value, List<Pattern> patterns) {
    for (Pattern pattern : patterns) {
      if (pattern.matcher(value).find()) {
        return true;
      }
    }
    return false;
  }

  /** Returns the name of the first PII pattern that matches the column name. */
  private static String getMatchedPatternName(String colName) {
    for (Pattern pattern : PII_PATTERNS) {
      if (pattern.matcher(colName).find()) {
        // Extract a human-readable name from the pattern
        String patternStr = pattern.pattern();
        // Remove the leading (?i) case-insensitive flag
        if (patternStr.startsWith("(?i)")) {
          patternStr = patternStr.substring(4);
        }
        return patternStr;
      }
    }
    return "unknown";
  }

  // ====================== Byte cap enforcement ======================

  @VisibleForTesting
  static Map<String, Object> enforceByteCap(Map<String, Object> result) {
    String json = JsonUtils.pojoToJson(result);
    if (json == null
        || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES) {
      return result;
    }

    // Truncate topOffenders lists and PII candidate list
    boolean truncated = false;

    Object gapsObj = result.get("gaps");
    if (gapsObj instanceof Map<?, ?> gaps) {
      for (Map.Entry<?, ?> entry : gaps.entrySet()) {
        if (entry.getValue() instanceof Map<?, ?> gap) {
          Object topObj = gap.get("topOffenders");
          if (topObj instanceof List<?> list && list.size() > 3) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> truncatedList =
                new ArrayList<>(((List<Map<String, Object>>) list).subList(0, 3));
            ((Map) gap).put("topOffenders", truncatedList);
            truncated = true;
          }
          Object candidatesObj = gap.get("candidateColumns");
          if (candidatesObj instanceof List<?> list && list.size() > 5) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> truncatedList =
                new ArrayList<>(((List<Map<String, Object>>) list).subList(0, 5));
            ((Map) gap).put("candidateColumns", truncatedList);
            truncated = true;
          }
        }
      }
    }

    if (truncated) {
      @SuppressWarnings("unchecked")
      List<String> warnings =
          result.get("warnings") instanceof List
              ? new ArrayList<>((List<String>) result.get("warnings"))
              : new ArrayList<>();
      warnings.add("gapDetailsTruncated: payload exceeded 8KB, top offenders/candidates truncated");
      result.put("warnings", warnings);
    }

    return result;
  }

  // ====================== Utility methods ======================

  @SuppressWarnings("unchecked")
  private static Map<String, Object> safeGetMap(Object obj) {
    return (obj instanceof Map) ? (Map<String, Object>) obj : null;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> safeGetList(Object obj) {
    return (obj instanceof List) ? (List<Object>) obj : null;
  }

  private static String escapeJson(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
