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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Cost × Freshness Ranking — returns top-N tables ranked by {@code costScore × (1 +
 * stalenessScore)} using signals already present on entities ({@code usageSummary}, {@code
 * profile}, {@code updatedAt}).
 *
 * <h3>Scoring formula</h3>
 *
 * <ul>
 *   <li><b>costScore</b> = usageRank × (1 + sizeWeight), where:
 *       <ul>
 *         <li>usageRank = {@code usageSummary.weeklyStats.percentileRank / 100} (falls back to
 *             dailyStats, then 0)
 *         <li>sizeWeight = {@code log10(profile.sizeInByte + 1) / 10} (0 if no size data)
 *       </ul>
 *   <li><b>stalenessScore</b> = {@code min(daysSinceUpdate / 30, 5.0) / 5.0} — saturates at 1.0
 *       after 150 days
 *   <li><b>priorityScore</b> = {@code costScore × (1 + stalenessScore)} — high-cost + stale
 *       tables score highest
 * </ul>
 *
 * <p>Tables missing cost or usage data are returned under {@code insufficientSignal} with counts,
 * not silently dropped (R10.2).
 *
 * <p>Spec reference: Expansions Group E10 (R10.1–R10.5).
 */
@Slf4j
public class RankAssetsByCostTool implements McpTool {

  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_PAYLOAD_BYTES = 8 * 1024; // 8 KB
  private static final int SEARCH_PAGE_SIZE = 200;

  /** Per-user rate limit: minimum milliseconds between consecutive calls. 5 minutes. */
  @VisibleForTesting static final long RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L;

  /** Tracks the last call timestamp per user for rate limiting. */
  @VisibleForTesting
  static final ConcurrentHashMap<String, Long> USER_LAST_CALL_MS = new ConcurrentHashMap<>();

  // ====================== Production overloads ======================

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

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "RankAssetsByCostTool does not support limits enforcement.");
  }

  // ====================== Test-friendly overload ======================

  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider)
      throws IOException {

    String entityType = (String) params.getOrDefault("entityType", "table");

    // Authorize first
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
              + "s before calling rank_assets_by_cost again",
          "retryAfterSeconds",
          remainingSeconds,
          "statusCode",
          429);
    }

    // Evict stale entries to prevent unbounded memory growth
    evictStaleEntries(System.currentTimeMillis());

    // Parse parameters
    int limit = parseLimit(params);
    double minStalenessDays = parseMinStalenessDays(params);
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

    LOG.info(
        "rank_assets_by_cost: entityType={}, scope={}/{}, limit={}, minStalenessDays={}",
        entityType,
        scopeType,
        scopeValue,
        limit,
        minStalenessDays);

    // Step 1: Search for table entities with usage/profile/updatedAt data
    var searchRepo = searchRepoProvider.getSearchRepository();
    if (searchRepo == null) {
      return buildErrorResult("Search repository not initialized");
    }

    List<ScoredAsset> scoredAssets = new ArrayList<>();
    List<Map<String, Object>> insufficientSignal = new ArrayList<>();
    int totalScanned = 0;

    // Paginate through search results
    int from = 0;
    while (from < MAX_LIMIT * 2) { // Scan up to 2× max limit for coverage
      SearchResultPage page =
          searchEntities(
              searchRepo,
              securityContext,
              entityType,
              scopeType,
              scopeValue,
              from,
              SEARCH_PAGE_SIZE);

      if (page.hits.isEmpty()) break;
      totalScanned += page.hits.size();

      for (Map<String, Object> source : page.hits) {
        ScoredAsset asset = scoreAsset(source);
        if (asset.hasSufficientSignal) {
          // Apply minStalenessDays filter
          if (asset.stalenessDays >= minStalenessDays) {
            scoredAssets.add(asset);
          }
        } else {
          insufficientSignal.add(buildInsufficientSignalEntry(source, asset));
        }
      }

      if (page.hits.size() < SEARCH_PAGE_SIZE) break;
      from += SEARCH_PAGE_SIZE;
    }

    // Step 2: Sort by priorityScore descending
    scoredAssets.sort(Comparator.comparingDouble((ScoredAsset a) -> a.priorityScore).reversed());

    // Step 3: Cap at limit
    if (scoredAssets.size() > limit) {
      scoredAssets = scoredAssets.subList(0, limit);
    }

    // Step 4: Build result
    List<Map<String, Object>> rankedList = new ArrayList<>();
    for (ScoredAsset asset : scoredAssets) {
      rankedList.add(asset.toMap());
    }

    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("type", "costFreshnessRanking");
    resultData.put("entityType", entityType);
    resultData.put("limit", limit);
    resultData.put("minStalenessDays", minStalenessDays);
    if (scopeType != null && scopeValue != null) {
      Map<String, Object> scopeMap = new LinkedHashMap<>();
      scopeMap.put("type", scopeType);
      scopeMap.put("value", scopeValue);
      resultData.put("scope", scopeMap);
    }
    resultData.put("totalScanned", totalScanned);
    resultData.put("rankedCount", rankedList.size());
    resultData.put("insufficientSignalCount", insufficientSignal.size());

    // Scoring formula documentation (R10.6: documented in response)
    Map<String, Object> scoringFormula = new LinkedHashMap<>();
    scoringFormula.put("costScore", "usageRank × sizeWeight");
    scoringFormula.put("usageRank", "usageSummary.weeklyStats.percentileRank / 100");
    scoringFormula.put("sizeWeight", "log10(profile.sizeInByte + 1) / 10 (0 if no size data)");
    scoringFormula.put("stalenessScore", "min(daysSinceUpdate / 30, 5.0) / 5.0");
    scoringFormula.put("priorityScore", "costScore × (1 + stalenessScore)");
    scoringFormula.put(
        "caveat",
        "Cost is a proxy based on usage rank + storage size. Actual cost depends on compute, "
            + "egress, and platform pricing not captured here.");
    resultData.put("scoringFormula", scoringFormula);

    String narrative =
        generateNarrative(
            rankedList, insufficientSignal.size(), totalScanned, scopeType, scopeValue);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(resultData)).narrative(narrative);

    Map<String, Object> result = new LinkedHashMap<>(envelope.build());
    result.put("ranked", rankedList);
    result.put("insufficientSignal", insufficientSignal);
    result.put("entityType", entityType);
    if (scopeType != null && scopeValue != null) {
      result.put("scope", Map.of("type", scopeType, "value", scopeValue));
    }

    // Enforce byte cap
    result = enforceByteCap(result);

    LOG.info(
        "rank_assets_by_cost: ranked {} assets ({} insufficient signal) out of {} scanned",
        rankedList.size(),
        insufficientSignal.size(),
        totalScanned);

    return result;
  }

  // ====================== Scoring logic (R10.1) ======================

  /**
   * Scores a single asset based on cost and staleness signals extracted from its OpenSearch
   * document.
   *
   * <p>Extracts:
   *
   * <ul>
   *   <li>{@code usageSummary.weeklyStats.percentileRank} (falls back to dailyStats)
   *   <li>{@code profile.sizeInByte}
   *   <li>{@code updatedAt} (epoch millis)
   * </ul>
   */
  @VisibleForTesting
  static ScoredAsset scoreAsset(Map<String, Object> source) {
    ScoredAsset asset = new ScoredAsset();
    asset.fqn = (String) source.get("fullyQualifiedName");
    asset.name = (String) source.get("name");

    // Extract service name
    Object serviceObj = source.get("service");
    if (serviceObj instanceof Map<?, ?> svc) {
      asset.service = (String) svc.get("name");
    }

    // Extract domain name (first domain)
    Object domainsObj = source.get("domains");
    if (domainsObj instanceof List<?> domains && !domains.isEmpty()) {
      Object first = domains.get(0);
      if (first instanceof Map<?, ?> dom) {
        asset.domain = (String) dom.get("name");
      }
    }

    // Extract tier
    Object tierObj = source.get("tier");
    if (tierObj instanceof Map<?, ?> tier) {
      asset.tier = (String) tier.get("tagFQN");
    }

    // ---- Cost score: usageRank × sizeWeight ----

    // Usage rank from usageSummary
    double usageRank = 0.0;
    boolean hasUsageData = false;
    Object usageSummaryObj = source.get("usageSummary");
    if (usageSummaryObj instanceof Map<?, ?> usageSummary) {
      Double rank = extractPercentileRank(usageSummary);
      if (rank != null) {
        usageRank = rank / 100.0;
        hasUsageData = true;
      }
    }

    // Size weight from profile
    double sizeWeight = 0.0;
    boolean hasSizeData = false;
    Object profileObj = source.get("profile");
    if (profileObj instanceof Map<?, ?> profile) {
      Object sizeInByteObj = profile.get("sizeInByte");
      if (sizeInByteObj instanceof Number n && n.doubleValue() > 0) {
        sizeWeight = Math.log10(n.doubleValue() + 1) / 10.0;
        hasSizeData = true;
      }
    }

    asset.costScore = usageRank * (1.0 + sizeWeight); // usage is primary, size is bonus
    asset.hasUsageData = hasUsageData;
    asset.hasSizeData = hasSizeData;

    // ---- Staleness score ----

    long now = System.currentTimeMillis();
    asset.stalenessDays = 0.0;
    Object updatedAtObj = source.get("updatedAt");
    if (updatedAtObj instanceof Number n) {
      long updatedAtMs = n.longValue();
      asset.stalenessDays = (now - updatedAtMs) / (1000.0 * 60 * 60 * 24);
    } else {
      // No updatedAt → assume very stale to flag the gap
      asset.stalenessDays = 999.0;
    }

    asset.stalenessScore = Math.min(asset.stalenessDays / 30.0, 5.0) / 5.0;

    // ---- Priority score: costScore × (1 + stalenessScore) ----

    asset.priorityScore = asset.costScore * (1.0 + asset.stalenessScore);

    // ---- Sufficient signal? ----
    // Usage is the primary cost signal; without it the costScore is 0 and ranking is meaningless.
    // Size-only assets go to insufficientSignal so the user knows to run usage ingestion.
    asset.hasSufficientSignal = hasUsageData;

    return asset;
  }

  /**
   * Extracts the best available percentileRank from usageSummary, preferring weeklyStats over
   * dailyStats.
   */
  @VisibleForTesting
  static Double extractPercentileRank(Map<?, ?> usageSummary) {
    // Prefer weeklyStats (7-day rolling window, more stable)
    Object weeklyObj = usageSummary.get("weeklyStats");
    if (weeklyObj instanceof Map<?, ?> weekly) {
      Object rank = weekly.get("percentileRank");
      if (rank instanceof Number n && n.doubleValue() > 0) {
        return n.doubleValue();
      }
    }
    // Fall back to dailyStats
    Object dailyObj = usageSummary.get("dailyStats");
    if (dailyObj instanceof Map<?, ?> daily) {
      Object rank = daily.get("percentileRank");
      if (rank instanceof Number n && n.doubleValue() > 0) {
        return n.doubleValue();
      }
    }
    // Fall back to monthlyStats
    Object monthlyObj = usageSummary.get("monthlyStats");
    if (monthlyObj instanceof Map<?, ?> monthly) {
      Object rank = monthly.get("percentileRank");
      if (rank instanceof Number n && n.doubleValue() > 0) {
        return n.doubleValue();
      }
    }
    return null;
  }

  // ====================== Search logic ======================

  /** Holds a page of search results. */
  @VisibleForTesting
  static class SearchResultPage {
    List<Map<String, Object>> hits = new ArrayList<>();
  }

  /** Searches for entities matching scope, returning _source data. */
  @VisibleForTesting
  SearchResultPage searchEntities(
      SearchRepository searchRepo,
      CatalogSecurityContext securityContext,
      String entityType,
      String scopeType,
      String scopeValue,
      int from,
      int size) {

    SearchResultPage page = new SearchResultPage();

    try {
      SubjectContext subjectContext = getSubjectContext(securityContext);

      String queryFilter = buildScopeFilter(entityType, scopeType, scopeValue);

      SearchRequest searchRequest =
          new SearchRequest()
              .withIndex(searchRepo.getIndexOrAliasName(mapEntityTypesToIndexNames(entityType)))
              .withQueryFilter(queryFilter)
              .withSize(size)
              .withFrom(from)
              .withFetchSource(true)
              .withDeleted(false);

      Response response = searchRepo.searchWithDirectQuery(searchRequest, subjectContext);

      if (response == null) return page;

      Map<String, Object> searchResponse;
      if (response.getEntity() instanceof String responseStr) {
        JsonNode jsonNode = JsonUtils.readTree(responseStr);
        searchResponse = JsonUtils.convertValue(jsonNode, Map.class);
      } else {
        searchResponse = JsonUtils.convertValue(response.getEntity(), Map.class);
      }

      // Extract hits
      Object hitsObj = searchResponse.get("hits");
      if (!(hitsObj instanceof Map<?, ?> hitsMap)) {
        LOG.debug("Unexpected search response structure: 'hits' is not a map");
        return page;
      }
      Object hitsList = hitsMap.get("hits");
      if (!(hitsList instanceof List<?> hitItems)) {
        LOG.debug("Unexpected search response structure: 'hits.hits' is not a list");
        return page;
      }

      for (Object item : hitItems) {
        if (!(item instanceof Map<?, ?> hit)) continue;
        Object source = hit.get("_source") != null ? hit.get("_source") : hit.get("source");
        if (source instanceof Map) {
          @SuppressWarnings("unchecked")
          Map<String, Object> sourceMap = (Map<String, Object>) source;
          page.hits.add(sourceMap);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to search entities for cost ranking: {}", e.getMessage());
    }

    return page;
  }

  // ====================== Rate limiting (R10.5) ======================

  /**
   * Atomically checks and acquires the per-user rate limit. Returns {@code null} if allowed, or
   * the blocking timestamp if rejected.
   */
  @VisibleForTesting
  static Long tryAcquireRateLimit(String userId) {
    long now = System.currentTimeMillis();
    Long[] blockedAt = {null};
    USER_LAST_CALL_MS.compute(
        userId,
        (k, lastCall) -> {
          if (lastCall != null && (now - lastCall) < RATE_LIMIT_COOLDOWN_MS) {
            blockedAt[0] = lastCall;
            return lastCall;
          }
          return now;
        });
    return blockedAt[0];
  }

  /** Evicts stale entries from the rate limit map. */
  @VisibleForTesting
  static void evictStaleEntries(long now) {
    USER_LAST_CALL_MS.entrySet().removeIf(e -> (now - e.getValue()) > RATE_LIMIT_COOLDOWN_MS * 2);
  }

  // ====================== Narrative generation ======================

  @VisibleForTesting
  static String generateNarrative(
      List<Map<String, Object>> ranked,
      int insufficientSignalCount,
      int totalScanned,
      String scopeType,
      String scopeValue) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Cost × Freshness Ranking\n\n");

    if (scopeType != null && scopeValue != null) {
      sb.append("**Scope:** ").append(scopeType).append(": `").append(scopeValue).append("`\n\n");
    }

    sb.append("**Scanned:** ").append(totalScanned).append(" assets\n\n");

    if (ranked.isEmpty()) {
      sb.append("No assets with sufficient cost signals found.");
      if (insufficientSignalCount > 0) {
        sb.append(" ").append(insufficientSignalCount).append(" assets lacked usage or size data.");
      }
      return sb.toString();
    }

    sb.append("### Top ").append(Math.min(ranked.size(), 10)).append(" by Priority Score\n\n");
    sb.append("| # | Asset | Cost | Stale (d) | Priority |\n");
    sb.append("|---|-------|------|-----------|----------|\n");

    int shown = Math.min(ranked.size(), 10);
    for (int i = 0; i < shown; i++) {
      Map<String, Object> r = ranked.get(i);
      String fqn = (String) r.getOrDefault("fqn", "?");
      double cost = ((Number) r.getOrDefault("costScore", 0)).doubleValue();
      double stale = ((Number) r.getOrDefault("stalenessDays", 0)).doubleValue();
      double priority = ((Number) r.getOrDefault("priorityScore", 0)).doubleValue();
      sb.append(
          String.format("| %d | `%s` | %.2f | %.0f | %.2f |\n", i + 1, fqn, cost, stale, priority));
    }
    sb.append("\n");

    if (insufficientSignalCount > 0) {
      sb.append("⚠ **")
          .append(insufficientSignalCount)
          .append("** asset(s) lacked usage or size data ");
      sb.append("and are listed under `insufficientSignal`. ");
      sb.append("Run profiling and usage ingestion to improve coverage.\n\n");
    }

    sb.append("### Formula\n");
    sb.append("`priorityScore = costScore × (1 + stalenessScore)`\n\n");
    sb.append("- `costScore = usageRank × (1 + sizeWeight)`\n");
    sb.append("- `stalenessScore = min(daysSinceUpdate / 30, 5.0) / 5.0`\n\n");
    sb.append("High-priority assets are both heavily used and stale — good candidates for ");
    sb.append("freshness tests or pipeline SLA reviews.");

    return sb.toString();
  }

  // ====================== Byte cap enforcement ======================

  @VisibleForTesting
  static Map<String, Object> enforceByteCap(Map<String, Object> result) {
    String json = JsonUtils.pojoToJson(result);
    if (json == null || json.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES) {
      return result;
    }

    List<String> warnings = new ArrayList<>();
    boolean truncated = false;

    // Truncate ranked list
    Object rankedObj = result.get("ranked");
    if (rankedObj instanceof List<?> ranked && ranked.size() > 10) {
      int originalSize = ranked.size();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rankedList = (List<Map<String, Object>>) ranked;
      result.put("ranked", rankedList.subList(0, 10));
      warnings.add(
          String.format(
              "truncated:ranked list from %d to 10 entries (payload >8KB)", originalSize));
      truncated = true;
    }

    // Truncate insufficientSignal list
    Object insufObj = result.get("insufficientSignal");
    if (insufObj instanceof List<?> insuf && insuf.size() > 5) {
      int originalSize = insuf.size();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> insufList = (List<Map<String, Object>>) insuf;
      result.put("insufficientSignal", insufList.subList(0, 5));
      warnings.add(
          String.format(
              "truncated:insufficientSignal list from %d to 5 entries (payload >8KB)",
              originalSize));
      truncated = true;
    }

    if (truncated) {
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

  // ====================== Helpers ======================

  /** Scored asset data holder. */
  @VisibleForTesting
  static class ScoredAsset {
    String fqn;
    String name;
    String service;
    String domain;
    String tier;
    double costScore;
    double stalenessDays;
    double stalenessScore;
    double priorityScore;
    boolean hasUsageData;
    boolean hasSizeData;
    boolean hasSufficientSignal;

    Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("fqn", fqn);
      map.put("name", name);
      if (service != null) map.put("service", service);
      if (domain != null) map.put("domain", domain);
      if (tier != null) map.put("tier", tier);
      map.put("costScore", Math.round(costScore * 10000.0) / 10000.0);
      map.put("stalenessDays", Math.round(stalenessDays * 10.0) / 10.0);
      map.put("stalenessScore", Math.round(stalenessScore * 10000.0) / 10000.0);
      map.put("priorityScore", Math.round(priorityScore * 10000.0) / 10000.0);
      map.put("hasUsageData", hasUsageData);
      map.put("hasSizeData", hasSizeData);
      return map;
    }
  }

  /** Builds an insufficient-signal entry for an asset missing cost/usage data. */
  private Map<String, Object> buildInsufficientSignalEntry(
      Map<String, Object> source, ScoredAsset asset) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("fqn", asset.fqn);
    entry.put("name", asset.name);
    if (asset.service != null) entry.put("service", asset.service);

    List<String> missingSignals = new ArrayList<>();
    if (!asset.hasUsageData) missingSignals.add("usageSummary");
    if (!asset.hasSizeData) missingSignals.add("profile.sizeInByte");
    entry.put("missingSignals", missingSignals);

    return entry;
  }

  /** Builds the scope filter for OpenSearch queries. */
  @VisibleForTesting
  static String buildScopeFilter(String entityType, String scopeType, String scopeValue) {
    StringBuilder must = new StringBuilder();
    must.append("{\"term\":{\"entityType\":\"").append(escapeJson(entityType)).append("\"}}");

    if ("domain".equals(scopeType) && scopeValue != null) {
      must.append(",{\"term\":{\"domains.name\":\"").append(escapeJson(scopeValue)).append("\"}}");
    } else if ("service".equals(scopeType) && scopeValue != null) {
      must.append(",{\"term\":{\"service.name\":\"").append(escapeJson(scopeValue)).append("\"}}");
    }

    return "{\"bool\":{\"must\":[" + must + "],\"must_not\":[{\"term\":{\"deleted\":true}}]}}";
  }

  private int parseLimit(Map<String, Object> params) {
    Object limitObj = params.get("limit");
    if (limitObj instanceof Number n) {
      int val = n.intValue();
      return Math.max(1, Math.min(val, MAX_LIMIT));
    }
    return DEFAULT_LIMIT;
  }

  private double parseMinStalenessDays(Map<String, Object> params) {
    Object obj = params.get("minStalenessDays");
    if (obj instanceof Number n) {
      return Math.max(0, n.doubleValue());
    }
    return 0.0;
  }

  private Map<String, Object> buildErrorResult(String message) {
    Map<String, Object> resultData = new LinkedHashMap<>();
    resultData.put("type", "costFreshnessRanking");
    resultData.put("error", message);

    EnvelopeBuilder envelope =
        EnvelopeBuilder.create().results(List.of(resultData)).narrative("Error: " + message);
    Map<String, Object> result = new LinkedHashMap<>(envelope.build());
    result.put("ranked", List.of());
    result.put("insufficientSignal", List.of());
    return result;
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
