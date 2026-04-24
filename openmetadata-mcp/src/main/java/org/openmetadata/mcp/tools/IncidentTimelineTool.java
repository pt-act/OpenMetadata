package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.ChangeEventRepository;
import org.openmetadata.service.jdbi3.TestCaseResultRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.search.SearchListFilter;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Incident Timeline Copilot — produces a Slack/PagerDuty-ready incident narrative.
 *
 * <p>Composes root cause analysis, ChangeEvent feed, test case result history, and ownership
 * resolution into a chronological timeline with a Markdown narrative.
 *
 * <p>Spec reference: Expansions Group E3 (R3.1–R3.5).
 */
@Slf4j
public class IncidentTimelineTool implements McpTool {

  private static final int DEFAULT_LOOKBACK_HOURS = 72;
  private static final int MAX_LOOKBACK_HOURS = 720; // 30 days
  private static final int MAX_UPSTREAM_DEPTH = 5;
  private static final int MAX_TIMELINE_ENTRIES = 50;
  private static final int MAX_PAYLOAD_BYTES = 6 * 1024; // 6 KB
  private static final int NARRATIVE_MAX_CHARS = 1200;
  private static final int MAX_SUGGESTED_OWNERS = 5;

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
        McpEntityBridge.defaultAuthorizer(authorizer, securityContext),
        McpEntityBridge.defaultSearchRepositoryProvider(),
        McpEntityBridge.defaultChangeEventRepositoryProvider(),
        McpEntityBridge.defaultTimeSeriesRepositoryProvider(),
        McpEntityBridge.defaultEntityFetcher(),
        McpEntityBridge.defaultEntityByReferenceFetcher());
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
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider,
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider,
      McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider,
      McpEntityBridge.EntityFetcher entityFetcher,
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher)
      throws IOException {

    String entityType = (String) params.getOrDefault("entityType", "table");
    EntityReference entityRef = ToolUtils.resolveEntityRef(params, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();

    int lookbackHours =
        Math.min(
            parseIntParam(params.get("lookbackHours"), DEFAULT_LOOKBACK_HOURS), MAX_LOOKBACK_HOURS);
    String format = (String) params.getOrDefault("format", "markdown");

    authorizer.authorize(entityType, MetadataOperation.VIEW_BASIC);

    long cutoffTs =
        LocalDateTime.now().minusHours(lookbackHours).toInstant(ZoneOffset.UTC).toEpochMilli();

    // --- Step 1: RCA upstream chain (E3.2) ---
    List<Map<String, Object>> upstreamFailures =
        fetchUpstreamFailures(fqn, cutoffTs, entityType, searchRepoProvider);

    // --- Step 2: ChangeEvent feed (E3.3) ---
    List<Map<String, Object>> changeEvents =
        fetchChangeEvents(entityRef, cutoffTs, entityType, changeEventRepoProvider);

    // --- Step 3: Test case result history (E3.4) ---
    List<Map<String, Object>> testHistory =
        fetchTestCaseHistory(fqn, cutoffTs, entityType, timeSeriesRepoProvider, entityFetcher);

    // --- Step 4: Ownership resolution (E3.5) ---
    List<Map<String, Object>> suggestedOwners =
        resolveOwnership(
            entityRef, upstreamFailures, fqn, entityType, entityByRefFetcher, entityFetcher);

    // --- Step 5: Build chronological timeline (E3.6) ---
    List<Map<String, Object>> timeline = new ArrayList<>();
    timeline.addAll(upstreamFailures);
    timeline.addAll(changeEvents);
    timeline.addAll(testHistory);
    timeline.sort(Comparator.comparingLong(e -> (Long) e.getOrDefault("ts", 0L)));

    // Cap timeline entries
    if (timeline.size() > MAX_TIMELINE_ENTRIES) {
      timeline = timeline.subList(timeline.size() - MAX_TIMELINE_ENTRIES, timeline.size());
    }

    // --- Step 6: Determine status and root cause ---
    boolean hasIncident =
        !upstreamFailures.isEmpty() || !testHistory.isEmpty() || !changeEvents.isEmpty();
    String status = hasIncident ? "incident" : "healthy";
    String rootCause = extractRootCause(upstreamFailures, testHistory);

    // --- Step 7: Build result domain ---
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("status", status);
    result.put("rootCause", rootCause);
    result.put("timeline", timeline);
    result.put("timelineEntryCount", timeline.size());
    result.put("suggestedOwners", suggestedOwners);
    result.put("lookbackHours", lookbackHours);
    result.put("cutoffTimestamp", cutoffTs);

    // --- Step 8: Narrative generation (E3.7) ---
    List<String> warnings = new ArrayList<>();
    String narrative = null;
    if ("markdown".equalsIgnoreCase(format)) {
      narrative = generateNarrative(fqn, status, rootCause, timeline, suggestedOwners);
      if (narrative.length() > NARRATIVE_MAX_CHARS) {
        narrative = narrative.substring(0, NARRATIVE_MAX_CHARS - 3) + "...";
        warnings.add("narrativeTruncated: exceeded " + NARRATIVE_MAX_CHARS + " chars");
      }
    }

    // --- Step 9: EnvelopeBuilder return (E3.7 payload cap) ---
    EnvelopeBuilder envelopeBuilder =
        EnvelopeBuilder.create().results(List.of(result)).warnings(warnings);
    if (narrative != null) {
      envelopeBuilder.narrative(narrative);
    }

    Map<String, Object> envelope = new LinkedHashMap<>(envelopeBuilder.build());
    envelope.put("fqn", fqn);
    envelope.put("entityType", entityType);
    envelope.put("status", status);

    // --- Step 10: 6KB byte cap (E3.7/E3.13) ---
    envelope = enforceByteCap(envelope);

    LOG.info(
        "Incident timeline completed for entity: {} - Status: {}, Timeline entries: {}",
        fqn,
        status,
        timeline.size());

    return envelope;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params)
      throws IOException {
    throw new UnsupportedOperationException(
        "IncidentTimelineTool does not require limit validation.");
  }

  // ====================== Step 1: Upstream failure chain (E3.2) ======================

  /** Fetches upstream failures via data quality lineage search. */
  private List<Map<String, Object>> fetchUpstreamFailures(
      String fqn,
      long cutoffTs,
      String entityType,
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider) {
    List<Map<String, Object>> failures = new ArrayList<>();
    try {
      var searchRepo = searchRepoProvider.getSearchRepository();
      if (searchRepo == null) {
        LOG.warn(
            "Search repository not initialized — cannot fetch upstream failures for '{}'", fqn);
        return failures;
      }

      Response upstreamResponse =
          searchRepo.searchDataQualityLineage(fqn.trim(), MAX_UPSTREAM_DEPTH, null, false);

      if (upstreamResponse == null) {
        LOG.warn("Search repository returned null response for data quality lineage of '{}'", fqn);
        return failures;
      }

      Object entity = upstreamResponse.getEntity();
      if (entity instanceof Map<?, ?> lineageData) {
        Object nodesObj = lineageData.get("nodes");
        // searchDataQualityLineage returns in-memory HashSet (not Jackson-deserialized List)
        if (nodesObj instanceof Set<?> nodes) {
          for (Object node : nodes) {
            if (node instanceof Map<?, ?> nodeMap) {
              @SuppressWarnings("unchecked")
              Map<String, Object> n = new HashMap<>((Map<String, Object>) nodeMap);
              // searchDataQualityLineage already filters to failure nodes; skip the source entity
              // itself
              String nodeFqn = (String) n.get("fullyQualifiedName");
              if (nodeFqn != null && !nodeFqn.equals(fqn)) {
                Long ts = extractTimestamp(n, cutoffTs);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ts", ts);
                entry.put("type", "upstreamFailure");
                entry.put("entityFqn", nodeFqn);
                // Capture the upstream node's entityType for owner resolution
                entry.put("entityType", n.getOrDefault("entityType", entityType));
                entry.put("description", "Upstream entity with data quality failures: " + nodeFqn);
                failures.add(entry);
              }
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to fetch upstream failures for '{}': {}", fqn, e.getMessage());
    }
    return failures;
  }

  /** Extracts the best timestamp from an upstream node, defaulting to cutoffTs. */
  private Long extractTimestamp(Map<String, Object> node, long cutoffTs) {
    // Try various timestamp fields that may exist on the node
    Object ts = node.get("timestamp");
    if (ts instanceof Number) return ((Number) ts).longValue();
    ts = node.get("updatedAt");
    if (ts instanceof Number) return ((Number) ts).longValue();
    // If no timestamp, place at cutoff so it appears in the timeline
    return cutoffTs;
  }

  // ====================== Step 2: ChangeEvent feed (E3.3) ======================

  /** Fetches ChangeEvents for the entity within the lookback window. */
  private List<Map<String, Object>> fetchChangeEvents(
      EntityReference entityRef,
      long cutoffTs,
      String entityType,
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider) {
    List<Map<String, Object>> events = new ArrayList<>();
    try {
      ChangeEventRepository changeEventRepo = changeEventRepoProvider.getChangeEventRepository();

      // TODO: ChangeEventDAO has no entity-ID filter — this scans all events for the entity type
      // and post-filters by entityId. Inefficient at scale but correct.
      List<ChangeEvent> changeEvents =
          changeEventRepo.list(
              cutoffTs,
              List.of(), // entityCreated
              List.of(entityType), // entityUpdated
              List.of(), // entityRestored
              List.of(entityType) // entityDeleted — deletions are significant for incident timeline
              );

      // Post-filter: only include events for our specific entity
      for (ChangeEvent ce : changeEvents) {
        if (ce.getEntityId() != null && ce.getEntityId().equals(entityRef.getId())) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("ts", ce.getTimestamp() != null ? ce.getTimestamp() : cutoffTs);
          entry.put("type", "schemaChange");
          entry.put("eventType", ce.getEventType() != null ? ce.getEventType().value() : "unknown");
          entry.put("userName", ce.getUserName());
          String desc = extractChangeDescription(ce);
          entry.put("description", desc);
          events.add(entry);
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to fetch change events for entity: {}", entityRef.getId(), e);
    }
    return events;
  }

  /** Extracts a human-readable description from a ChangeEvent. */
  @VisibleForTesting
  static String extractChangeDescription(ChangeEvent ce) {
    if (ce.getChangeDescription() != null) {
      var cd = ce.getChangeDescription();
      StringBuilder sb = new StringBuilder();
      if (cd.getFieldsAdded() != null && !cd.getFieldsAdded().isEmpty()) {
        sb.append("Added: ")
            .append(
                cd.getFieldsAdded().stream()
                    .map(f -> f.getName())
                    .collect(Collectors.joining(", ")))
            .append("; ");
      }
      if (cd.getFieldsUpdated() != null && !cd.getFieldsUpdated().isEmpty()) {
        sb.append("Updated: ")
            .append(
                cd.getFieldsUpdated().stream()
                    .map(f -> f.getName())
                    .collect(Collectors.joining(", ")))
            .append("; ");
      }
      if (cd.getFieldsDeleted() != null && !cd.getFieldsDeleted().isEmpty()) {
        sb.append("Deleted: ")
            .append(
                cd.getFieldsDeleted().stream()
                    .map(f -> f.getName())
                    .collect(Collectors.joining(", ")))
            .append("; ");
      }
      if (!sb.isEmpty()) {
        String result = sb.toString();
        return result.endsWith("; ") ? result.substring(0, result.length() - 2) : result;
      }
    }
    return (ce.getEventType() != null ? ce.getEventType().value() : "change")
        + " by "
        + (ce.getUserName() != null ? ce.getUserName() : "unknown");
  }

  // ====================== Step 3: Test case result history (E3.4) ======================

  /**
   * Fetches test case results for the entity, capturing first-red and last-green timestamps.
   *
   * <p>Query by status then post-filter by FQN prefix — testCaseFQN includes the test definition
   * name after the entity FQN (e.g. "db.schema.orders.columnValuesToBeNotNull"), so exact-match
   * on the entity FQN would return zero results.
   */
  private List<Map<String, Object>> fetchTestCaseHistory(
      String fqn,
      long cutoffTs,
      String entityType,
      McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider,
      McpEntityBridge.EntityFetcher entityFetcher) {
    List<Map<String, Object>> history = new ArrayList<>();
    try {
      TestCaseResultRepository testResultRepo =
          (TestCaseResultRepository)
              timeSeriesRepoProvider.getEntityTimeSeriesRepository(Entity.TEST_CASE_RESULT);

      // Primary strategy: look up test suite for this entity, then query by testSuiteId
      // (following RootCauseAnalysisTool.addTestCaseResultForTestSuite pattern).
      // Fallback: query by status + post-filter by FQN prefix.
      boolean foundViaTestSuite = false;
      try {
        Object entityObj =
            entityFetcher.getEntityByName(entityType, fqn, "testSuite", Include.NON_DELETED);
        if (entityObj != null) {
          Map<String, Object> entityMap =
              JsonUtils.readValue(JsonUtils.pojoToJson(entityObj), Map.class);
          Object testSuiteObj = entityMap.get("testSuite");
          if (testSuiteObj instanceof Map<?, ?> testSuiteMap) {
            String testSuiteId = (String) testSuiteMap.get("id");
            if (testSuiteId != null) {
              foundViaTestSuite =
                  fetchTestCaseResultsBySuite(testResultRepo, testSuiteId, cutoffTs, history);
            }
          }
        }
      } catch (Exception e) {
        LOG.debug("Could not look up test suite for '{}': {}", fqn, e.getMessage());
      }

      // Fallback: query by status + post-filter by FQN prefix if test suite lookup failed
      if (!foundViaTestSuite) {
        SearchListFilter failedFilter = new SearchListFilter();
        failedFilter.addQueryParam("testCaseStatus", "Failed");

        ResultList<TestCaseResult> failedResults =
            testResultRepo.listLatestFromSearch(
                testResultRepo.getFields("testCaseStatus,result,timestamp,testCaseFQN"),
                failedFilter,
                "testCaseFQN.keyword",
                null,
                null,
                null,
                null,
                null);

        if (failedResults.getData() != null) {
          for (TestCaseResult tcr : failedResults.getData()) {
            if (tcr.getTestCaseFQN() == null || !tcr.getTestCaseFQN().startsWith(fqn + ".")) {
              continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", tcr.getTimestamp() != null ? tcr.getTimestamp() : cutoffTs);
            entry.put("type", "testFailure");
            entry.put("testCaseFqn", tcr.getTestCaseFQN());
            entry.put(
                "status",
                tcr.getTestCaseStatus() != null ? tcr.getTestCaseStatus().value() : "Failed");
            entry.put("description", "Test case failed: " + tcr.getTestCaseFQN());
            history.add(entry);
          }
        }

        SearchListFilter successFilter = new SearchListFilter();
        successFilter.addQueryParam("testCaseStatus", "Success");

        ResultList<TestCaseResult> successResults =
            testResultRepo.listLatestFromSearch(
                testResultRepo.getFields("testCaseStatus,result,timestamp,testCaseFQN"),
                successFilter,
                "testCaseFQN.keyword",
                null,
                null,
                null,
                null,
                null);

        if (successResults.getData() != null) {
          for (TestCaseResult tcr : successResults.getData()) {
            if (tcr.getTestCaseFQN() == null || !tcr.getTestCaseFQN().startsWith(fqn + ".")) {
              continue;
            }
            if (tcr.getTimestamp() != null && tcr.getTimestamp() >= cutoffTs) {
              Map<String, Object> entry = new LinkedHashMap<>();
              entry.put("ts", tcr.getTimestamp());
              entry.put("type", "testRecovery");
              entry.put("testCaseFqn", tcr.getTestCaseFQN());
              entry.put("status", "Success");
              entry.put("description", "Test case recovered: " + tcr.getTestCaseFQN());
              history.add(entry);
            }
          }
        }
      }

    } catch (Exception e) {
      LOG.warn("Failed to fetch test case history for '{}': {}", fqn, e.getMessage());
    }
    return history;
  }

  /**
   * Fetches test case results via testSuiteId (primary strategy, matching
   * RootCauseAnalysisTool.addTestCaseResultForTestSuite pattern). Returns true if any results
   * were found.
   */
  private boolean fetchTestCaseResultsBySuite(
      TestCaseResultRepository testResultRepo,
      String testSuiteId,
      long cutoffTs,
      List<Map<String, Object>> history)
      throws IOException {
    SearchListFilter failedFilter = new SearchListFilter();
    failedFilter.addQueryParam("testCaseStatus", "Failed");
    failedFilter.addQueryParam("testSuiteId", testSuiteId);

    ResultList<TestCaseResult> failedResults =
        testResultRepo.listLatestFromSearch(
            testResultRepo.getFields("testCaseStatus,result,timestamp,testCaseFQN"),
            failedFilter,
            "testCaseFQN.keyword",
            null,
            null,
            null,
            null,
            null);

    boolean found = false;
    if (failedResults.getData() != null) {
      for (TestCaseResult tcr : failedResults.getData()) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", tcr.getTimestamp() != null ? tcr.getTimestamp() : cutoffTs);
        entry.put("type", "testFailure");
        entry.put("testCaseFqn", tcr.getTestCaseFQN());
        entry.put(
            "status", tcr.getTestCaseStatus() != null ? tcr.getTestCaseStatus().value() : "Failed");
        entry.put("description", "Test case failed: " + tcr.getTestCaseFQN());
        history.add(entry);
        found = true;
      }
    }

    // Also fetch recent successful results for this test suite
    SearchListFilter successFilter = new SearchListFilter();
    successFilter.addQueryParam("testCaseStatus", "Success");
    successFilter.addQueryParam("testSuiteId", testSuiteId);

    ResultList<TestCaseResult> successResults =
        testResultRepo.listLatestFromSearch(
            testResultRepo.getFields("testCaseStatus,result,timestamp,testCaseFQN"),
            successFilter,
            "testCaseFQN.keyword",
            null,
            null,
            null,
            null,
            null);

    if (successResults.getData() != null) {
      for (TestCaseResult tcr : successResults.getData()) {
        if (tcr.getTimestamp() != null && tcr.getTimestamp() >= cutoffTs) {
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("ts", tcr.getTimestamp());
          entry.put("type", "testRecovery");
          entry.put("testCaseFqn", tcr.getTestCaseFQN());
          entry.put("status", "Success");
          entry.put("description", "Test case recovered: " + tcr.getTestCaseFQN());
          history.add(entry);
          found = true;
        }
      }
    }
    return found;
  }

  // ====================== Step 4: Ownership resolution (E3.5) ======================

  /** Resolves ownership by fetching the full entity (EntityReference lacks owner data). */
  private List<Map<String, Object>> resolveOwnership(
      EntityReference entityRef,
      List<Map<String, Object>> upstreamFailures,
      String fqn,
      String entityType,
      McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher,
      McpEntityBridge.EntityFetcher entityFetcher) {
    Map<String, Map<String, Object>> ownerMap = new LinkedHashMap<>();

    // Direct owner of the entity — fetch full entity via JSON round-trip (POJOs aren't Maps)
    try {
      Object entity = entityByRefFetcher.getEntity(entityRef, "owners", Include.NON_DELETED);
      if (entity == null) {
        LOG.debug("Entity not found for owner resolution: {}", fqn);
        return new ArrayList<>(ownerMap.values());
      }
      Map<String, Object> entityMap = JsonUtils.readValue(JsonUtils.pojoToJson(entity), Map.class);
      Object ownersObj = entityMap.get("owners");
      if (ownersObj instanceof List<?> ownersList) {
        for (Object o : ownersList) {
          if (o instanceof Map<?, ?> ownerEntry) {
            String ownerName = (String) ownerEntry.get("name");
            String ownerType = (String) ownerEntry.get("type");
            Object ownerId = ownerEntry.get("id");
            if (ownerName != null) {
              Map<String, Object> entry = new LinkedHashMap<>();
              entry.put("name", ownerName);
              entry.put("type", ownerType);
              entry.put("id", ownerId != null ? ownerId.toString() : null);
              entry.put("rationale", "directOwner");
              entry.put("context", fqn);
              ownerMap.put(ownerName, entry);
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.debug("Could not resolve direct owner for '{}': {}", fqn, e.getMessage());
    }

    // Owners of top-3 upstream failures
    long upstreamCount = 0;
    for (Map<String, Object> failure : upstreamFailures) {
      if (upstreamCount >= 3) break;
      String upstreamFqn = (String) failure.get("entityFqn");
      if (upstreamFqn != null) {
        try {
          // Use the upstream node's entityType (captured in fetchUpstreamFailures), falling
          // back to the source entity's type if not available
          String upstreamEntityType =
              failure.get("entityType") instanceof String s ? s : entityType;
          Object upstreamEntity =
              entityFetcher.getEntityByName(
                  upstreamEntityType, upstreamFqn, "owners", Include.NON_DELETED);
          if (upstreamEntity == null) continue;
          Map<String, Object> entityMap =
              JsonUtils.readValue(JsonUtils.pojoToJson(upstreamEntity), Map.class);
          Object ownersObj = entityMap.get("owners");
          if (ownersObj instanceof List<?> ownersList) {
            for (Object o : ownersList) {
              if (o instanceof Map<?, ?> ownerEntry) {
                String ownerName = (String) ownerEntry.get("name");
                String ownerType = (String) ownerEntry.get("type");
                Object ownerId = ownerEntry.get("id");
                if (ownerName != null && !ownerMap.containsKey(ownerName)) {
                  Map<String, Object> entry = new LinkedHashMap<>();
                  entry.put("name", ownerName);
                  entry.put("type", ownerType);
                  entry.put("id", ownerId != null ? ownerId.toString() : null);
                  entry.put("rationale", "upstreamOwner");
                  entry.put("context", upstreamFqn);
                  ownerMap.put(ownerName, entry);
                }
              }
            }
          }
        } catch (Exception e) {
          LOG.debug("Could not resolve upstream owner for '{}': {}", upstreamFqn, e.getMessage());
        }
      }
      upstreamCount++;
    }

    List<Map<String, Object>> owners = new ArrayList<>(ownerMap.values());
    if (owners.size() > MAX_SUGGESTED_OWNERS) {
      owners = owners.subList(0, MAX_SUGGESTED_OWNERS);
    }
    return owners;
  }

  // ====================== Root cause extraction ======================

  private String extractRootCause(
      List<Map<String, Object>> upstreamFailures, List<Map<String, Object>> testHistory) {
    if (!upstreamFailures.isEmpty()) {
      // Find the earliest upstream failure
      Map<String, Object> earliest =
          upstreamFailures.stream()
              .min(Comparator.comparingLong(e -> (Long) e.getOrDefault("ts", 0L)))
              .orElse(upstreamFailures.get(0));
      return "Upstream failure at " + earliest.get("entityFqn");
    }
    if (!testHistory.isEmpty()) {
      Map<String, Object> firstFailure =
          testHistory.stream()
              .filter(e -> "testFailure".equals(e.get("type")))
              .min(Comparator.comparingLong(e -> (Long) e.getOrDefault("ts", 0L)))
              .orElse(null);
      if (firstFailure != null) {
        return "Test case failure: " + firstFailure.get("testCaseFqn");
      }
    }
    return null;
  }

  // ====================== Narrative generation (E3.7) ======================

  /** Generates a Markdown narrative with Incident, Root Cause, Timeline, and Suggested Owners. */
  @VisibleForTesting
  static String generateNarrative(
      String fqn,
      String status,
      String rootCause,
      List<Map<String, Object>> timeline,
      List<Map<String, Object>> suggestedOwners) {

    StringBuilder sb = new StringBuilder();
    sb.append("## Incident Report: ").append(fqn).append("\n\n");
    sb.append("**Status:** ")
        .append("incident".equals(status) ? "🔴 Incident" : "🟢 Healthy")
        .append("\n\n");

    if ("healthy".equals(status)) {
      sb.append("No incidents found within the lookback window.");
      return sb.toString();
    }

    sb.append("### Root Cause\n");
    sb.append(rootCause != null ? rootCause : "Unknown").append("\n\n");

    sb.append("### Timeline (").append(timeline.size()).append(" events)\n\n");
    for (Map<String, Object> entry : timeline) {
      String ts = formatTimestamp((Long) entry.getOrDefault("ts", 0L));
      String type = (String) entry.getOrDefault("type", "unknown");
      String desc = (String) entry.getOrDefault("description", "");
      String icon =
          switch (type) {
            case "upstreamFailure" -> "⚠️";
            case "schemaChange" -> "✏️";
            case "testFailure" -> "🔴";
            case "testRecovery" -> "🟢";
            default -> "•";
          };
      sb.append("- **").append(ts).append("** ").append(icon).append(" ").append(desc).append("\n");
    }

    if (!suggestedOwners.isEmpty()) {
      sb.append("\n### Suggested Owners\n");
      for (Map<String, Object> owner : suggestedOwners) {
        sb.append("- **")
            .append(owner.get("name"))
            .append("** (")
            .append(owner.get("rationale"))
            .append(")\n");
      }
    }

    return sb.toString();
  }

  private static String formatTimestamp(long ts) {
    if (ts <= 0) return "unknown";
    return Instant.ofEpochMilli(ts).toString();
  }

  // ====================== 6KB byte cap (E3.7/E3.13) ======================

  /**
   * Enforces the 6KB byte cap by truncating timeline entries.
   *
   * <p><b>Warning:</b> mutates the input envelope's timeline list in place.
   */
  @VisibleForTesting
  static Map<String, Object> enforceByteCap(Map<String, Object> envelope) {
    byte[] bytes = JsonUtils.pojoToJson(envelope).getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_PAYLOAD_BYTES) {
      return envelope;
    }

    // Navigate into envelope: results[0] -> timeline
    Object resultsObj = envelope.get("results");
    if (!(resultsObj instanceof List<?> resultsList) || resultsList.isEmpty()) {
      return envelope;
    }
    Object firstResult = resultsList.get(0);
    if (!(firstResult instanceof Map<?, ?>)) {
      return envelope;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) firstResult;

    Object timelineObj = result.get("timeline");
    if (!(timelineObj instanceof List<?> timeline)) {
      return envelope;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> timelineList = (List<Map<String, Object>>) timeline;

    // Truncate from the oldest entries first (keep most recent)
    while (timelineList.size() > 3) {
      timelineList.remove(0);
      result.put("timelineEntryCount", timelineList.size());

      @SuppressWarnings("unchecked")
      List<Object> updatedResults = (List<Object>) envelope.get("results");
      // Re-serialize to check size
      bytes = JsonUtils.pojoToJson(envelope).getBytes(StandardCharsets.UTF_8);
      if (bytes.length <= MAX_PAYLOAD_BYTES) {
        break;
      }
    }

    // Add warning about truncation
    @SuppressWarnings("unchecked")
    List<String> warnings =
        envelope.containsKey("warnings")
            ? new ArrayList<>((List<String>) envelope.get("warnings"))
            : new ArrayList<>();
    warnings.add("payloadTruncated: exceeded " + MAX_PAYLOAD_BYTES + " byte cap");
    envelope.put("warnings", warnings);

    return envelope;
  }

  // ====================== Utility methods ======================

  private static int parseIntParam(Object value, int defaultValue) {
    if (value == null) return defaultValue;
    if (value instanceof Number number) return number.intValue();
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException e) {
        return defaultValue;
      }
    }
    return defaultValue;
  }
}
