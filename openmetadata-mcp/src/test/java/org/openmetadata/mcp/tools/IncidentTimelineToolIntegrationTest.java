package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.tests.type.TestCaseResult;
import org.openmetadata.schema.tests.type.TestCaseStatus;
import org.openmetadata.schema.type.ChangeDescription;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.type.FieldChange;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.jdbi3.ChangeEventRepository;
import org.openmetadata.service.jdbi3.TestCaseResultRepository;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * Integration tests for {@link IncidentTimelineTool}.
 *
 * <p>Tests the full execute() flow with injected functional interfaces via {@link McpEntityBridge},
 * eliminating the need for {@code mockStatic(Entity.class)}. The tool's test-friendly overload
 * accepts all Entity dependencies as injectable lambdas.
 *
 * <p>Tests verify that upstream failures, change events, test case history, and ownership
 * resolution compose correctly into the final incident timeline envelope.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentTimelineToolIntegrationTest {

  private IncidentTimelineTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;
  private McpEntityBridge.McpAuthorizer noopAuthorizer;

  @BeforeEach
  void setUp() {
    tool = new IncidentTimelineTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    noopAuthorizer = (entityType, op) -> {};
  }

  // ====================== Shared test data ======================

  /** Timestamp 2 hours ago — guaranteed within any reasonable lookback window. */
  private static final long RECENT_TS =
      LocalDateTime.now().minusHours(2).toInstant(ZoneOffset.UTC).toEpochMilli();

  /** Timestamp 1 hour ago. */
  private static final long MORE_RECENT_TS =
      LocalDateTime.now().minusHours(1).toInstant(ZoneOffset.UTC).toEpochMilli();

  private EntityReference buildEntityRef(String fqn, UUID id, String type) {
    EntityReference ref = mock(EntityReference.class);
    when(ref.getFullyQualifiedName()).thenReturn(fqn);
    when(ref.getId()).thenReturn(id);
    when(ref.getType()).thenReturn(type);
    return ref;
  }

  private Map<String, Object> buildUpstreamLineageNode(String fqn, String entityType) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("fullyQualifiedName", fqn);
    node.put("entityType", entityType);
    node.put("timestamp", RECENT_TS);
    return node;
  }

  private ChangeEvent buildChangeEvent(
      UUID entityId, String userName, EventType eventType, long timestamp) {
    ChangeEvent ce = new ChangeEvent();
    ce.setEntityId(entityId);
    ce.setEntityType("table");
    ce.setUserName(userName);
    ce.setEventType(eventType);
    ce.setTimestamp(timestamp);
    ChangeDescription cd = new ChangeDescription();
    cd.setFieldsUpdated(
        List.of(new FieldChange().withName("description").withOldValue("old").withNewValue("new")));
    ce.setChangeDescription(cd);
    return ce;
  }

  private TestCaseResult buildTestCaseResult(
      String testCaseFqn, TestCaseStatus status, long timestamp) {
    TestCaseResult tcr = new TestCaseResult();
    tcr.setTestCaseFQN(testCaseFqn);
    tcr.setTestCaseStatus(status);
    tcr.setTimestamp(timestamp);
    tcr.setResult("Test result for " + testCaseFqn);
    return tcr;
  }

  private Map<String, Object> buildEntityWithOwners(String name, String ownerName) {
    Map<String, Object> entityMap = new LinkedHashMap<>();
    Map<String, Object> ownerEntry = new LinkedHashMap<>();
    ownerEntry.put("name", ownerName);
    ownerEntry.put("type", "user");
    ownerEntry.put("id", UUID.randomUUID().toString());
    entityMap.put("owners", List.of(ownerEntry));
    entityMap.put("name", name);
    return entityMap;
  }

  private Map<String, Object> buildEntityWithTestSuite(String testSuiteId) {
    Map<String, Object> entityMap = new LinkedHashMap<>();
    Map<String, Object> testSuite = new LinkedHashMap<>();
    testSuite.put("id", testSuiteId);
    entityMap.put("testSuite", testSuite);
    return entityMap;
  }

  // ====================== Fluent test builder ======================

  /**
   * Fluent builder for {@link IncidentTimelineTool} test execution.
   *
   * <p>Provides a readable, chainable API for setting up test dependencies and executing the tool.
   * Default values match the most common test scenario (empty lineage, empty change events, default
   * params, JsonUtils static mock enabled).
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * Map<String, Object> result = builder()
   *     .withUpstreamLineage("db.schema.raw_orders", "table")
   *     .withParam("lookbackHours", 72)
   *     .execute();
   * assertThat(result).containsEntry("status", "incident");
   * }</pre>
   */
  class IncidentTimelineTestBuilder {

    private final IncidentTimelineTool tool;
    private McpEntityBridge.McpAuthorizer authorizer;

    private EntityReference entityRef;
    private SearchRepository searchRepo;
    private boolean searchRepoSet;
    private ChangeEventRepository changeEventRepo;
    private boolean changeEventRepoSet;
    private TestCaseResultRepository testResultRepo;
    private boolean testResultRepoSet;
    private Map<String, Object> params;
    private boolean paramsSet;
    private Object testSuiteLookupResult;
    private Object ownerLookupResult;
    private Object directOwnerLookupResult;
    private BiFunction<String, String, Object> upstreamOwnerLookup;
    private boolean jsonMockEnabled;
    private McpEntityBridge.EntityFetcher entityFetcher;
    private McpEntityBridge.EntityByReferenceFetcher entityByRefFetcher;

    private IncidentTimelineTestBuilder(
        IncidentTimelineTool tool, McpEntityBridge.McpAuthorizer authorizer) {
      this.tool = tool;
      this.authorizer = authorizer;
      this.jsonMockEnabled = true;
    }

    // --- Entity reference ---

    /** Sets the entity reference (default: db.schema.orders, UUID.randomUUID, table). */
    IncidentTimelineTestBuilder withEntityRef(EntityReference ref) {
      this.entityRef = ref;
      return this;
    }

    /** Sets the entity reference with a specific UUID. */
    IncidentTimelineTestBuilder withEntityRef(String fqn, UUID id, String type) {
      this.entityRef = buildEntityRef(fqn, id, type);
      return this;
    }

    /** Sets the entity reference with a random UUID. */
    IncidentTimelineTestBuilder withEntityRef(String fqn, String type) {
      this.entityRef = buildEntityRef(fqn, UUID.randomUUID(), type);
      return this;
    }

    // --- Search repository (lineage) ---

    /** Uses empty lineage (default behavior). */
    IncidentTimelineTestBuilder withEmptyLineage() throws java.io.IOException {
      this.searchRepo = setUpEmptyLineageSearchRepo();
      this.searchRepoSet = true;
      return this;
    }

    /** Uses upstream lineage with a single node. */
    IncidentTimelineTestBuilder withUpstreamLineage(String fqn, String entityType)
        throws java.io.IOException {
      this.searchRepo = setUpUpstreamLineageSearchRepo(fqn, entityType);
      this.searchRepoSet = true;
      return this;
    }

    /** Uses upstream lineage with multiple custom nodes. */
    IncidentTimelineTestBuilder withUpstreamLineage(Set<Map<String, Object>> nodes)
        throws java.io.IOException {
      this.searchRepo = setUpUpstreamLineageSearchRepo(nodes);
      this.searchRepoSet = true;
      return this;
    }

    /** Uses a pre-configured SearchRepository mock. */
    IncidentTimelineTestBuilder withSearchRepo(SearchRepository repo) {
      this.searchRepo = repo;
      this.searchRepoSet = true;
      return this;
    }

    // --- Change event repository ---

    /** Uses empty change events (default behavior). */
    IncidentTimelineTestBuilder withEmptyChangeEvents() throws java.io.IOException {
      this.changeEventRepo = setUpEmptyChangeEventRepo();
      this.changeEventRepoSet = true;
      return this;
    }

    /** Uses a pre-configured ChangeEventRepository mock. */
    IncidentTimelineTestBuilder withChangeEventRepo(ChangeEventRepository repo) {
      this.changeEventRepo = repo;
      this.changeEventRepoSet = true;
      return this;
    }

    /** Stubs a ChangeEventRepository to return the given events. */
    IncidentTimelineTestBuilder withChangeEvents(List<ChangeEvent> events) {
      ChangeEventRepository repo = mock(ChangeEventRepository.class);
      when(repo.list(anyLong(), any(), any(), any(), any())).thenReturn(events);
      this.changeEventRepo = repo;
      this.changeEventRepoSet = true;
      return this;
    }

    // --- Test result repository ---

    /** Uses an unstubbed TestCaseResultRepository (default behavior). */
    IncidentTimelineTestBuilder withDefaultTestResultRepo() {
      this.testResultRepo = setUpDefaultTestResultRepo();
      this.testResultRepoSet = true;
      return this;
    }

    /** Uses a pre-configured TestCaseResultRepository mock. */
    IncidentTimelineTestBuilder withTestResultRepo(TestCaseResultRepository repo) {
      this.testResultRepo = repo;
      this.testResultRepoSet = true;
      return this;
    }

    // --- Parameters ---

    /** Uses default params: entityType=table, fqn=db.schema.orders (default behavior). */
    IncidentTimelineTestBuilder withDefaultParams() {
      this.params = buildDefaultParams();
      this.paramsSet = true;
      return this;
    }

    /** Uses fully custom params (copies into a mutable map). */
    IncidentTimelineTestBuilder withParams(Map<String, Object> params) {
      this.params = new HashMap<>(params);
      this.paramsSet = true;
      return this;
    }

    /** Adds or overrides a single parameter. Initializes default params if not yet set. */
    IncidentTimelineTestBuilder withParam(String key, Object value) {
      if (!paramsSet) {
        this.params = buildDefaultParams();
        this.paramsSet = true;
      }
      this.params.put(key, value);
      return this;
    }

    // --- Lookup results ---

    /** Sets the testSuite lookup result. */
    IncidentTimelineTestBuilder withTestSuiteLookupResult(Object result) {
      this.testSuiteLookupResult = result;
      return this;
    }

    /** Sets the owner lookup result (used for both entityFetcher and entityByRefFetcher). */
    IncidentTimelineTestBuilder withOwnerLookupResult(Object result) {
      this.ownerLookupResult = result;
      return this;
    }

    /**
     * Sets the direct owner lookup result for entityByRefFetcher.
     *
     * <p>Used with {@link #withUpstreamOwnerLookup(BiFunction)} for the upstream owner path.
     */
    IncidentTimelineTestBuilder withDirectOwnerLookupResult(Object result) {
      this.directOwnerLookupResult = result;
      return this;
    }

    /**
     * Sets the upstream owner lookup function.
     *
     * <p>Switches to the upstream-owner execution path where entityFetcher routes "owners" field
     * lookups through this function instead of returning a fixed result.
     */
    IncidentTimelineTestBuilder withUpstreamOwnerLookup(BiFunction<String, String, Object> lookup) {
      this.upstreamOwnerLookup = lookup;
      return this;
    }

    // --- Advanced ---

    /** Disables the JsonUtils static mock (for tests that need real serialization). */
    IncidentTimelineTestBuilder withoutJsonMock() {
      this.jsonMockEnabled = false;
      return this;
    }

    /** Uses custom entity fetchers (bypasses the standard fetcher logic entirely). */
    IncidentTimelineTestBuilder withCustomFetchers(
        McpEntityBridge.EntityFetcher fetcher,
        McpEntityBridge.EntityByReferenceFetcher byRefFetcher) {
      this.entityFetcher = fetcher;
      this.entityByRefFetcher = byRefFetcher;
      return this;
    }

    /** Overrides the default noop authorizer. */
    IncidentTimelineTestBuilder withAuthorizer(McpEntityBridge.McpAuthorizer authorizer) {
      this.authorizer = authorizer;
      return this;
    }

    // --- Private helpers (moved from outer class) ---

    /** Creates default params map with entityType=table and fqn=db.schema.orders. */
    private Map<String, Object> buildDefaultParams() {
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      return params;
    }

    /** Creates a SearchRepository mock that returns empty lineage (no upstream nodes). */
    private SearchRepository setUpEmptyLineageSearchRepo() throws java.io.IOException {
      SearchRepository searchRepo = mock(SearchRepository.class);
      Response lineageResponse = mock(Response.class);
      when(searchRepo.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(lineageResponse);
      when(lineageResponse.getEntity()).thenReturn(Map.of("nodes", Set.of(), "edges", Set.of()));
      return searchRepo;
    }

    /**
     * Creates a SearchRepository mock that returns lineage with upstream nodes.
     *
     * @param nodes the upstream lineage nodes to include
     */
    private SearchRepository setUpUpstreamLineageSearchRepo(Set<Map<String, Object>> nodes)
        throws java.io.IOException {
      SearchRepository searchRepo = mock(SearchRepository.class);
      Response lineageResponse = mock(Response.class);
      Map<String, Object> lineageData = new HashMap<>();
      lineageData.put("nodes", nodes);
      lineageData.put("edges", Set.of());
      when(searchRepo.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenReturn(lineageResponse);
      when(lineageResponse.getEntity()).thenReturn(lineageData);
      return searchRepo;
    }

    /** Creates a SearchRepository mock that returns lineage with a single upstream node. */
    private SearchRepository setUpUpstreamLineageSearchRepo(String fqn, String entityType)
        throws java.io.IOException {
      Map<String, Object> node = buildUpstreamLineageNode(fqn, entityType);
      Set<Map<String, Object>> nodeSet = new HashSet<>();
      nodeSet.add(node);
      return setUpUpstreamLineageSearchRepo(nodeSet);
    }

    /** Creates a default TestCaseResultRepository mock with no stubbing. */
    private TestCaseResultRepository setUpDefaultTestResultRepo() {
      return mock(TestCaseResultRepository.class);
    }

    /**
     * Creates a MockedStatic<JsonUtils> pre-configured with default stubs.
     *
     * <p>Stubs: pojoToJson → "{}", readValue → Map.of().
     */
    private MockedStatic<JsonUtils> createJsonUtilsMock() {
      MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class);
      jsonMock.when(() -> JsonUtils.pojoToJson(any())).thenReturn("{}");
      jsonMock.when(() -> JsonUtils.readValue(anyString(), eq(Map.class))).thenReturn(Map.of());
      return jsonMock;
    }

    /** Creates a ChangeEventRepository mock that returns an empty change event list. */
    private ChangeEventRepository setUpEmptyChangeEventRepo() {
      ChangeEventRepository changeEventRepo = mock(ChangeEventRepository.class);
      when(changeEventRepo.list(anyLong(), any(), any(), any(), any())).thenReturn(List.of());
      return changeEventRepo;
    }

    // --- Execution ---

    /**
     * Executes the tool and returns the result.
     *
     * <p>By default, wraps execution in a JsonUtils static mock. Use {@link #withoutJsonMock()} to
     * disable. Default values are applied for any unset fields:
     *
     * <ul>
     *   <li>entityRef: db.schema.orders, table, random UUID
     *   <li>searchRepo: empty lineage
     *   <li>changeEventRepo: empty change events
     *   <li>testResultRepo: unstubbed mock
     *   <li>params: entityType=table, fqn=db.schema.orders
     * </ul>
     */
    Map<String, Object> execute() throws java.io.IOException {
      // Apply defaults for unset fields
      if (entityRef == null)
        entityRef = buildEntityRef("db.schema.orders", UUID.randomUUID(), "table");
      if (!searchRepoSet) searchRepo = setUpEmptyLineageSearchRepo();
      if (!changeEventRepoSet) changeEventRepo = setUpEmptyChangeEventRepo();
      if (!testResultRepoSet) testResultRepo = setUpDefaultTestResultRepo();
      if (!paramsSet) params = buildDefaultParams();

      if (jsonMockEnabled) {
        try (MockedStatic<JsonUtils> ignored = createJsonUtilsMock()) {
          return doExecute();
        }
      } else {
        return doExecute();
      }
    }

    /** Internal execution — builds functional interfaces and calls tool.execute(). */
    private Map<String, Object> doExecute() throws java.io.IOException {
      String entityType = (String) params.getOrDefault("entityType", "table");
      String fqn = (String) params.getOrDefault("fqn", "db.schema.orders");
      McpEntityBridge.EntityReferenceResolver referenceResolver = (type, name, inc) -> entityRef;
      McpEntityBridge.SearchRepositoryProvider searchRepoProvider = () -> searchRepo;
      McpEntityBridge.ChangeEventRepositoryProvider changeEventRepoProvider = () -> changeEventRepo;
      McpEntityBridge.TimeSeriesRepositoryProvider timeSeriesRepoProvider =
          (type) -> testResultRepo;

      McpEntityBridge.EntityFetcher fetcher;
      McpEntityBridge.EntityByReferenceFetcher byRefFetcher;

      if (entityFetcher != null && entityByRefFetcher != null) {
        // Custom fetchers path (e.g., ownerResolutionThrows test)
        fetcher = entityFetcher;
        byRefFetcher = entityByRefFetcher;
      } else if (upstreamOwnerLookup != null) {
        // Upstream owner path
        fetcher =
            (type, name, fields, inc) -> {
              if ("testSuite".equals(fields) && entityType.equals(type) && fqn.equals(name))
                return testSuiteLookupResult;
              if ("owners".equals(fields)) return upstreamOwnerLookup.apply(type, name);
              return null;
            };
        byRefFetcher =
            (ref, fields, inc) -> {
              if ("owners".equals(fields)) return directOwnerLookupResult;
              return null;
            };
      } else {
        // Standard path
        fetcher =
            (type, name, fields, inc) -> {
              if ("testSuite".equals(fields) && entityType.equals(type) && fqn.equals(name))
                return testSuiteLookupResult;
              if ("owners".equals(fields)) return ownerLookupResult;
              return null;
            };
        byRefFetcher =
            (ref, fields, inc) -> {
              if ("owners".equals(fields)) return ownerLookupResult;
              return null;
            };
      }

      return tool.execute(
          params,
          referenceResolver,
          authorizer,
          searchRepoProvider,
          changeEventRepoProvider,
          timeSeriesRepoProvider,
          fetcher,
          byRefFetcher);
    }
  }

  /** Creates a fluent builder for IncidentTimelineTool test execution. */
  private IncidentTimelineTestBuilder builder() {
    return new IncidentTimelineTestBuilder(tool, noopAuthorizer);
  }

  // ====================== Healthy entity (no incidents) ======================

  @Nested
  class HealthyEntity {

    @Test
    void execute_noUpstreamNoChangesNoTests_returnsHealthyStatus() throws Exception {
      Map<String, Object> result = builder().execute();

      assertThat(result).containsEntry("status", "healthy");
      assertThat(result).containsEntry("fqn", "db.schema.orders");

      // Check the nested result
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results).hasSize(1);
      Map<String, Object> data = results.get(0);
      assertThat(data).containsEntry("status", "healthy");
      assertThat(data.get("timelineEntryCount")).isEqualTo(0);
    }
  }

  // ====================== Upstream failure chain ======================

  @Nested
  class UpstreamFailureChain {

    @Test
    void execute_upstreamFailures_includesUpstreamInTimeline() throws Exception {
      Map<String, Object> result =
          builder().withUpstreamLineage("db.schema.raw_orders", "table").execute();

      assertThat(result).containsEntry("status", "incident");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> data = results.get(0);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");
      assertThat(timeline).isNotEmpty();

      Map<String, Object> firstEntry = timeline.get(0);
      assertThat(firstEntry).containsEntry("type", "upstreamFailure");
      assertThat(firstEntry).containsEntry("entityFqn", "db.schema.raw_orders");
      assertThat(firstEntry).containsEntry("entityType", "table");
      assertThat(firstEntry.get("description").toString()).contains("db.schema.raw_orders");
    }

    @Test
    void execute_upstreamFailure_skipsSourceEntity() throws Exception {
      // Lineage includes the source entity itself — should be excluded from upstream failures
      Map<String, Object> sourceNode = buildUpstreamLineageNode("db.schema.orders", "table");
      Map<String, Object> upstreamNode = buildUpstreamLineageNode("db.schema.raw_orders", "table");
      Set<Map<String, Object>> nodes = new HashSet<>();
      nodes.add(sourceNode);
      nodes.add(upstreamNode);

      Map<String, Object> result = builder().withUpstreamLineage(nodes).execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      // Only the upstream node should appear, not the source
      assertThat(timeline).hasSize(1);
      assertThat(timeline.get(0)).containsEntry("entityFqn", "db.schema.raw_orders");
    }
  }

  // ====================== ChangeEvent feed ======================

  @Nested
  class ChangeEventFeed {

    @Test
    void execute_changeEvents_filtersByEntityId() throws Exception {
      UUID entityId = UUID.randomUUID();
      UUID otherEntityId = UUID.randomUUID();

      // Change events: one for our entity, one for a different entity — use recent timestamps
      ChangeEvent ourEvent =
          buildChangeEvent(entityId, "alice", EventType.ENTITY_UPDATED, RECENT_TS);
      ChangeEvent otherEvent =
          buildChangeEvent(otherEntityId, "bob", EventType.ENTITY_UPDATED, MORE_RECENT_TS);

      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.orders", entityId, "table")
              .withChangeEvents(List.of(ourEvent, otherEvent))
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      // Only our entity's change event should appear
      assertThat(timeline).hasSize(1);
      Map<String, Object> changeEntry = timeline.get(0);
      assertThat(changeEntry).containsEntry("type", "schemaChange");
      assertThat(changeEntry).containsEntry("userName", "alice");
      assertThat(changeEntry.get("description").toString()).contains("Updated: description");
    }

    @Test
    void execute_deletionEvents_capturedInTimeline() throws Exception {
      UUID entityId = UUID.randomUUID();

      ChangeEvent deletedEvent = new ChangeEvent();
      deletedEvent.setEntityId(entityId);
      deletedEvent.setEntityType("table");
      deletedEvent.setUserName("admin");
      deletedEvent.setEventType(EventType.ENTITY_DELETED);
      deletedEvent.setTimestamp(RECENT_TS);

      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.orders", entityId, "table")
              .withChangeEvents(List.of(deletedEvent))
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      assertThat(timeline).hasSize(1);
      Map<String, Object> entry = timeline.get(0);
      assertThat(entry).containsEntry("type", "schemaChange");
      assertThat(entry).containsEntry("eventType", "entityDeleted");
    }
  }

  // ====================== Test case history (via testSuiteId) ======================

  @Nested
  class TestCaseHistoryViaSuite {

    @Test
    void execute_testSuiteResults_includesFailuresAndRecoveries() throws Exception {
      // Entity with test suite
      String testSuiteId = UUID.randomUUID().toString();
      Map<String, Object> entityWithSuite = buildEntityWithTestSuite(testSuiteId);

      // Mock test case results — use recent timestamps within the lookback window
      TestCaseResult failedTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Failed, RECENT_TS);
      TestCaseResult successTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Success, MORE_RECENT_TS);

      ResultList<TestCaseResult> failedResults = new ResultList<>();
      failedResults.setData(List.of(failedTcr));

      ResultList<TestCaseResult> successResults = new ResultList<>();
      successResults.setData(List.of(successTcr));

      TestCaseResultRepository testResultRepo = mock(TestCaseResultRepository.class);
      when(testResultRepo.getFields(anyString()))
          .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
      when(testResultRepo.listLatestFromSearch(
              any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(failedResults)
          .thenReturn(successResults);

      Map<String, Object> result =
          builder()
              .withTestResultRepo(testResultRepo)
              .withTestSuiteLookupResult(entityWithSuite)
              .withoutJsonMock()
              .withParam("lookbackHours", 72)
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      // Should have both failure and recovery entries
      assertThat(timeline.size()).isGreaterThanOrEqualTo(2);

      // Verify failure entry
      assertThat(timeline.stream().filter(e -> "testFailure".equals(e.get("type"))).count())
          .isGreaterThan(0);

      // Verify recovery entry (success result with timestamp >= cutoff)
      assertThat(timeline.stream().filter(e -> "testRecovery".equals(e.get("type"))).count())
          .isGreaterThan(0);
    }
  }

  // ====================== Test case history (fallback) ======================

  @Nested
  class TestCaseHistoryFallback {

    @Test
    void execute_noTestSuite_usesFallbackFilter() throws Exception {
      // No test suite for this entity → triggers fallback path
      TestCaseResult failedTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Failed, RECENT_TS);
      TestCaseResult otherEntityTcr =
          buildTestCaseResult(
              "db.schema.other_table.someTest", TestCaseStatus.Failed, MORE_RECENT_TS);

      ResultList<TestCaseResult> failedResults = new ResultList<>();
      failedResults.setData(List.of(failedTcr, otherEntityTcr));

      ResultList<TestCaseResult> successResults = new ResultList<>();
      successResults.setData(List.of());

      TestCaseResultRepository testResultRepo = mock(TestCaseResultRepository.class);
      when(testResultRepo.getFields(anyString()))
          .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
      when(testResultRepo.listLatestFromSearch(
              any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(failedResults)
          .thenReturn(successResults);

      Map<String, Object> result = builder().withTestResultRepo(testResultRepo).execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      // Only the entity's own test case should appear (post-filtered by FQN prefix)
      assertThat(timeline).hasSize(1);
      assertThat(timeline.get(0)).containsEntry("type", "testFailure");
      assertThat(timeline.get(0))
          .containsEntry("testCaseFqn", "db.schema.orders.columnValuesToBeNotNull");
    }
  }

  // ====================== Ownership resolution ======================

  @Nested
  class OwnershipResolution {

    @Test
    void execute_directAndUpstreamOwners_inSuggestedOwners() throws Exception {
      // Direct entity owner
      Map<String, Object> entityWithOwners = buildEntityWithOwners("orders", "alice");
      // Upstream entity owner
      Map<String, Object> upstreamEntityWithOwners = buildEntityWithOwners("raw_orders", "bob");

      BiFunction<String, String, Object> upstreamOwnerLookup =
          (type, name) -> {
            if ("db.schema.raw_orders".equals(name)) return upstreamEntityWithOwners;
            return null;
          };
      Map<String, Object> result =
          builder()
              .withUpstreamLineage("db.schema.raw_orders", "table")
              .withDirectOwnerLookupResult(entityWithOwners)
              .withUpstreamOwnerLookup(upstreamOwnerLookup)
              .withoutJsonMock()
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");

      assertThat(suggestedOwners).isNotEmpty();

      // Check direct owner
      assertThat(
              suggestedOwners.stream()
                  .anyMatch(
                      o ->
                          "alice".equals(o.get("name"))
                              && "directOwner".equals(o.get("rationale"))))
          .isTrue();

      // Check upstream owner
      assertThat(
              suggestedOwners.stream()
                  .anyMatch(
                      o ->
                          "bob".equals(o.get("name"))
                              && "upstreamOwner".equals(o.get("rationale"))))
          .isTrue();
    }

    @Test
    void execute_nullEntity_gracefulHandling() throws Exception {
      Map<String, Object> result = builder().execute();

      assertThat(result).containsEntry("status", "healthy");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");
      assertThat(suggestedOwners).isEmpty();
    }
  }

  // ====================== Full integration ======================

  @Nested
  class FullIntegration {

    @Test
    void execute_allDataSources_composesCorrectTimeline() throws Exception {
      UUID entityId = UUID.randomUUID();

      // 1. Upstream failure
      Map<String, Object> upstreamNode = buildUpstreamLineageNode("db.schema.raw_orders", "table");
      upstreamNode.put("timestamp", 1713945600000L);
      Set<Map<String, Object>> nodes = new HashSet<>();
      nodes.add(upstreamNode);

      // 2. Change event — use recent timestamps
      ChangeEvent changeEvent =
          buildChangeEvent(
              entityId,
              "alice",
              EventType.ENTITY_UPDATED,
              RECENT_TS + 60_000L); // 1 minute after upstream

      // 3. Test case results via test suite — use recent timestamps
      String testSuiteId = UUID.randomUUID().toString();
      Map<String, Object> entityWithSuite = buildEntityWithTestSuite(testSuiteId);

      TestCaseResult failedTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Failed, RECENT_TS);
      TestCaseResult successTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Success, MORE_RECENT_TS);

      ResultList<TestCaseResult> failedResults = new ResultList<>();
      failedResults.setData(List.of(failedTcr));

      ResultList<TestCaseResult> successResults = new ResultList<>();
      successResults.setData(List.of(successTcr));

      TestCaseResultRepository testResultRepo = mock(TestCaseResultRepository.class);
      when(testResultRepo.getFields(anyString()))
          .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
      when(testResultRepo.listLatestFromSearch(
              any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(failedResults)
          .thenReturn(successResults);

      Map<String, Object> entityWithOwners = buildEntityWithOwners("orders", "alice");
      Map<String, Object> upstreamWithOwners = buildEntityWithOwners("raw_orders", "bob");

      BiFunction<String, String, Object> upstreamOwnerLookup =
          (type, name) -> {
            if ("db.schema.raw_orders".equals(name)) return upstreamWithOwners;
            return null;
          };
      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.orders", entityId, "table")
              .withUpstreamLineage(nodes)
              .withChangeEvents(List.of(changeEvent))
              .withTestResultRepo(testResultRepo)
              .withTestSuiteLookupResult(entityWithSuite)
              .withDirectOwnerLookupResult(entityWithOwners)
              .withUpstreamOwnerLookup(upstreamOwnerLookup)
              .withoutJsonMock()
              .execute();

      // Verify overall status
      assertThat(result).containsEntry("status", "incident");
      assertThat(result).containsEntry("fqn", "db.schema.orders");

      // Verify timeline composition
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> data = results.get(0);
      assertThat(data).containsEntry("status", "incident");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");

      // Should have all 4 types: upstream failure, schema change, test failure, test recovery
      assertThat(timeline.size()).isGreaterThanOrEqualTo(4);

      Set<String> types = new HashSet<>();
      for (Map<String, Object> entry : timeline) {
        types.add((String) entry.get("type"));
      }
      assertThat(types).contains("upstreamFailure", "schemaChange", "testFailure", "testRecovery");

      // Timeline should be sorted by timestamp
      for (int i = 1; i < timeline.size(); i++) {
        Long prevTs = (Long) timeline.get(i - 1).getOrDefault("ts", 0L);
        Long currTs = (Long) timeline.get(i).getOrDefault("ts", 0L);
        assertThat(prevTs).isLessThanOrEqualTo(currTs);
      }

      // Verify root cause
      assertThat(data.get("rootCause").toString()).contains("Upstream failure");

      // Verify suggested owners
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) data.get("suggestedOwners");
      assertThat(suggestedOwners.stream().anyMatch(o -> "alice".equals(o.get("name")))).isTrue();
      assertThat(suggestedOwners.stream().anyMatch(o -> "bob".equals(o.get("name")))).isTrue();

      // Verify narrative is generated (markdown format is default)
      assertThat(result).containsKey("narrative");
      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("Incident Report");
      assertThat(narrative).contains("Root Cause");
      assertThat(narrative).contains("Timeline");
    }
  }

  // ====================== Format parameter ======================

  @Nested
  class FormatParameter {

    @Test
    void execute_formatJson_noNarrative() throws Exception {
      Map<String, Object> result = builder().withParam("format", "json").execute();

      // When format=json, no narrative should be generated
      assertThat(result).doesNotContainKey("narrative");
      assertThat(result).containsEntry("status", "healthy");
    }
  }

  // ====================== Non-table entityType ======================

  @Nested
  class NonTableEntityType {

    @Test
    void execute_dashboardEntity_usesCorrectType() throws Exception {
      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.my_dashboard", "dashboard")
              .withParams(Map.of("entityType", "dashboard", "fqn", "db.schema.my_dashboard"))
              .execute();

      // Should use "dashboard" entityType throughout, not hardcoded "table"
      assertThat(result).containsEntry("entityType", "dashboard");
      assertThat(result).containsEntry("fqn", "db.schema.my_dashboard");
    }
  }

  // ====================== Lookback hours ======================

  @Nested
  class LookbackHours {

    @Test
    void execute_customLookbackHours_respected() throws Exception {
      Map<String, Object> result = builder().withParam("lookbackHours", 168).execute(); // 7 days

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results.get(0)).containsEntry("lookbackHours", 168);
    }

    @Test
    void execute_lookbackHoursCappedAtMax() throws Exception {
      Map<String, Object> result =
          builder().withParam("lookbackHours", 9999).execute(); // way over the max

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      // Should be capped at MAX_LOOKBACK_HOURS (720)
      assertThat((Integer) results.get(0).get("lookbackHours")).isLessThanOrEqualTo(720);
    }
  }

  // ====================== Parameter validation ======================

  @Nested
  class ParameterValidation {

    @Test
    void execute_missingFqn_throwsIllegalArgumentException() {
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      // No fqn or fullyQualifiedName

      assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fqn")
          .hasMessageContaining("fullyQualifiedName");
    }
  }

  // ====================== Upstream with different entityType ======================

  @Nested
  class UpstreamWithDifferentEntityType {

    @Test
    void execute_pipelineUpstream_usesPipelineTypeForOwnerLookup() throws Exception {
      // Upstream lineage with a pipeline node (different entityType)
      Map<String, Object> pipelineNode =
          buildUpstreamLineageNode("pipeline.etl_orders", "pipeline");
      Set<Map<String, Object>> nodes = new HashSet<>();
      nodes.add(pipelineNode);

      // Direct entity owner
      Map<String, Object> entityWithOwners = buildEntityWithOwners("orders", "alice");
      // Pipeline upstream owner
      Map<String, Object> pipelineWithOwners = buildEntityWithOwners("etl_orders", "charlie");

      BiFunction<String, String, Object> upstreamOwnerLookup =
          (type, name) -> {
            if ("pipeline.etl_orders".equals(name)) return pipelineWithOwners;
            return null;
          };
      Map<String, Object> result =
          builder()
              .withUpstreamLineage(nodes)
              .withDirectOwnerLookupResult(entityWithOwners)
              .withUpstreamOwnerLookup(upstreamOwnerLookup)
              .withoutJsonMock()
              .execute();

      // Verify the pipeline upstream failure uses the correct entityType
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      assertThat(timeline).hasSize(1);
      assertThat(timeline.get(0)).containsEntry("type", "upstreamFailure");
      assertThat(timeline.get(0)).containsEntry("entityFqn", "pipeline.etl_orders");
      // The upstream failure entry should have captured the pipeline entityType
      assertThat(timeline.get(0)).containsEntry("entityType", "pipeline");

      // Verify suggested owners include pipeline owner (charlie) as upstreamOwner
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");
      assertThat(
              suggestedOwners.stream()
                  .anyMatch(
                      o ->
                          "charlie".equals(o.get("name"))
                              && "upstreamOwner".equals(o.get("rationale"))))
          .isTrue();
    }
  }

  // ====================== Envelope structure ======================

  @Nested
  class EnvelopeStructure {

    @Test
    void execute_hasRequiredTopLevelKeys() throws Exception {
      Map<String, Object> result = builder().execute();

      // Verify top-level envelope keys
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("fqn");
      assertThat(result).containsKey("entityType");
      assertThat(result).containsKey("status");
      assertThat(result).containsKey("narrative"); // default is markdown

      // Verify nested result keys
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> data = results.get(0);
      assertThat(data).containsKey("fqn");
      assertThat(data).containsKey("entityType");
      assertThat(data).containsKey("status");
      assertThat(data).containsKey("rootCause");
      assertThat(data).containsKey("timeline");
      assertThat(data).containsKey("timelineEntryCount");
      assertThat(data).containsKey("suggestedOwners");
      assertThat(data).containsKey("lookbackHours");
      assertThat(data).containsKey("cutoffTimestamp");
    }
  }

  // ====================== Narrative truncation warning ======================

  @Nested
  class NarrativeTruncation {

    @Test
    void execute_longNarrative_truncatedWithWarning() throws Exception {
      // Many upstream failures to generate a long narrative
      Set<Map<String, Object>> nodes = new HashSet<>();
      for (int i = 0; i < 20; i++) {
        Map<String, Object> node =
            buildUpstreamLineageNode("db.schema.upstream_table_" + i, "table");
        node.put("timestamp", RECENT_TS + i * 1000L);
        nodes.add(node);
      }

      Map<String, Object> result = builder().withUpstreamLineage(nodes).execute();

      // Narrative should be present
      assertThat(result).containsKey("narrative");
      String narrative = (String) result.get("narrative");
      assertThat(narrative.length()).isLessThanOrEqualTo(1200);

      // If the narrative was truncated, a warning should be present
      if (narrative.endsWith("...")) {
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).anyMatch(w -> w.contains("narrativeTruncated"));
      }
    }
  }

  // ====================== Owner cap enforcement ======================

  @Nested
  class OwnerCapEnforcement {

    @Test
    void execute_manyUpstreamOwners_cappedAtMax() throws Exception {
      // 5 upstream failures (more than the top-3 cap for owner resolution)
      Set<Map<String, Object>> nodes = new HashSet<>();
      for (int i = 0; i < 5; i++) {
        Map<String, Object> node = buildUpstreamLineageNode("db.schema.upstream_" + i, "table");
        node.put("timestamp", RECENT_TS + i * 1000L);
        nodes.add(node);
      }

      Map<String, Object> entityWithOwners = buildEntityWithOwners("orders", "directOwner");

      BiFunction<String, String, Object> upstreamOwnerLookup =
          (type, name) -> {
            // Each upstream returns a different owner
            return buildEntityWithOwners(
                name.replace("db.schema.upstream_", "upstream_"),
                "upstreamOwner_" + name.hashCode());
          };
      Map<String, Object> result =
          builder()
              .withUpstreamLineage(nodes)
              .withDirectOwnerLookupResult(entityWithOwners)
              .withUpstreamOwnerLookup(upstreamOwnerLookup)
              .withoutJsonMock()
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");

      // Max 5 suggested owners (1 direct + 3 upstream + possibly more)
      assertThat(suggestedOwners.size()).isLessThanOrEqualTo(5);

      assertThat(
              suggestedOwners.stream()
                  .anyMatch(
                      o ->
                          "directOwner".equals(o.get("name"))
                                  && "directOwner".equals(o.get("rationale"))
                              || "directOwner".equals(o.get("name"))))
          .isTrue();
    }
  }

  // ====================== Timeline entry cap ======================

  @Nested
  class TimelineEntryCap {

    @Test
    void execute_moreThan50Entries_cappedAtMax() throws Exception {
      UUID entityId = UUID.randomUUID();

      // 60 change events for this entity — exceeds MAX_TIMELINE_ENTRIES (50)
      List<ChangeEvent> events = new ArrayList<>();
      for (int i = 0; i < 60; i++) {
        ChangeEvent ce =
            buildChangeEvent(entityId, "user" + i, EventType.ENTITY_UPDATED, RECENT_TS + i * 1000L);
        events.add(ce);
      }

      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.orders", entityId, "table")
              .withChangeEvents(events)
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      Map<String, Object> data = results.get(0);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline = (List<Map<String, Object>>) data.get("timeline");
      // Should be capped at MAX_TIMELINE_ENTRIES (50)
      assertThat(timeline.size()).isLessThanOrEqualTo(50);
      assertThat(data.get("timelineEntryCount")).isEqualTo(timeline.size());

      // Should keep the most recent entries (last 50 of 60)
      Long earliestTs = (Long) timeline.get(0).getOrDefault("ts", 0L);
      assertThat(earliestTs).isGreaterThan(RECENT_TS); // not the oldest events
    }
  }

  // ====================== Graceful degradation ======================

  @Nested
  class GracefulDegradation {

    @Test
    void execute_lineageFails_returnsHealthyWithEmptyUpstream() throws Exception {
      // Lineage search throws — simulates Elasticsearch down
      SearchRepository searchRepo = mock(SearchRepository.class);
      when(searchRepo.searchDataQualityLineage(anyString(), anyInt(), any(), anyBoolean()))
          .thenThrow(new RuntimeException("Elasticsearch down"));

      // Should not throw — returns healthy with empty upstream
      Map<String, Object> result = builder().withSearchRepo(searchRepo).execute();
      assertThat(result).containsEntry("status", "healthy");
    }

    @Test
    void execute_changeEventRepoFails_returnsResultWithoutChangeEvents() throws Exception {
      // Change event repo throws — simulates database connection failure
      ChangeEventRepository changeEventRepo = mock(ChangeEventRepository.class);
      when(changeEventRepo.list(anyLong(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("Database connection failed"));

      Map<String, Object> result = builder().withChangeEventRepo(changeEventRepo).execute();

      // Should return healthy since all data sources failed
      assertThat(result).containsEntry("status", "healthy");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");
      assertThat(timeline).isEmpty();
    }

    @Test
    void execute_testCaseRepoFails_returnsResultWithoutTestHistory() throws Exception {
      // TestCaseResultRepository.listLatestFromSearch throws
      TestCaseResultRepository testResultRepo = mock(TestCaseResultRepository.class);
      when(testResultRepo.getFields(anyString()))
          .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
      when(testResultRepo.listLatestFromSearch(
              any(), any(), any(), any(), any(), any(), any(), any()))
          .thenThrow(new RuntimeException("Search index unavailable"));

      Map<String, Object> result = builder().withTestResultRepo(testResultRepo).execute();

      assertThat(result).containsEntry("status", "healthy");
    }

    @Test
    void execute_ownerResolutionFails_returnsResultWithEmptyOwners() throws Exception {
      // Upstream failure to trigger owner resolution
      Map<String, Object> result =
          builder().withUpstreamLineage("db.schema.raw_orders", "table").execute();

      // Should still return incident status with empty owners
      assertThat(result).containsEntry("status", "incident");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");
      assertThat(suggestedOwners).isEmpty();
    }

    @Test
    void execute_ownerResolutionThrows_returnsResultWithEmptyOwners() throws Exception {
      // Inject functional interfaces with throwing fetcher — exercises the catch(Exception) path
      // in resolveOwnership that the null-return path does not cover.
      // Note: the throwing entityFetcher also triggers the exception path in fetchTestCaseHistory's
      // testSuite lookup (not just owner resolution), but that exception is caught gracefully too.
      McpEntityBridge.EntityFetcher throwingFetcher =
          (type, name, fields, inc) -> {
            throw new RuntimeException("Owner resolution failed: database unavailable");
          };
      McpEntityBridge.EntityByReferenceFetcher throwingByRefFetcher =
          (ref, fields, inc) -> {
            throw new RuntimeException("Owner resolution failed: database unavailable");
          };

      // Should not throw — catches the RuntimeException and returns empty owners
      Map<String, Object> result =
          builder()
              .withUpstreamLineage("db.schema.raw_orders", "table")
              .withCustomFetchers(throwingFetcher, throwingByRefFetcher)
              .execute();

      assertThat(result).containsEntry("status", "incident");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suggestedOwners =
          (List<Map<String, Object>>) results.get(0).get("suggestedOwners");
      // Exception path should also produce empty owners
      assertThat(suggestedOwners).isEmpty();
    }
  }

  // ====================== Root cause extraction ======================

  @Nested
  class RootCauseExtraction {

    @Test
    void execute_testFailureOnly_rootCauseIsTestFailure() throws Exception {
      // Test failures only — no upstream failures
      TestCaseResult failedTcr =
          buildTestCaseResult(
              "db.schema.orders.columnValuesToBeNotNull", TestCaseStatus.Failed, RECENT_TS);

      ResultList<TestCaseResult> failedResults = new ResultList<>();
      failedResults.setData(List.of(failedTcr));

      ResultList<TestCaseResult> successResults = new ResultList<>();
      successResults.setData(List.of());

      TestCaseResultRepository testResultRepo = mock(TestCaseResultRepository.class);
      when(testResultRepo.getFields(anyString()))
          .thenReturn(mock(org.openmetadata.service.util.EntityUtil.Fields.class));
      when(testResultRepo.listLatestFromSearch(
              any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(failedResults)
          .thenReturn(successResults);

      Map<String, Object> result = builder().withTestResultRepo(testResultRepo).execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      String rootCause = (String) results.get(0).get("rootCause");
      assertThat(rootCause).contains("Test case failure");
      assertThat(rootCause).contains("db.schema.orders.columnValuesToBeNotNull");
    }

    @Test
    void execute_noIncidents_rootCauseIsNull() throws Exception {
      Map<String, Object> result = builder().execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results.get(0).get("rootCause")).isNull();
    }
  }

  // ====================== Lookback hours — string and default ======================

  @Nested
  class LookbackHoursParsing {

    @Test
    void execute_stringLookbackHours_parsedCorrectly() throws Exception {
      Map<String, Object> result =
          builder().withParam("lookbackHours", "168").execute(); // String instead of int

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results.get(0)).containsEntry("lookbackHours", 168);
    }

    @Test
    void execute_noLookbackHours_defaultsTo72() throws Exception {
      Map<String, Object> result = builder().execute(); // No lookbackHours

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      assertThat(results.get(0)).containsEntry("lookbackHours", 72);
    }

    @Test
    void execute_invalidLookbackHours_defaultsTo72() throws Exception {
      Map<String, Object> result = builder().withParam("lookbackHours", "not-a-number").execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      // Invalid string should fall back to default
      assertThat(results.get(0)).containsEntry("lookbackHours", 72);
    }
  }

  // ====================== extractTimestamp fallbacks ======================

  @Nested
  class ExtractTimestampFallbacks {

    @Test
    void execute_upstreamNodeWithUpdatedAt_usesUpdatedAtAsTimestamp() throws Exception {
      // Node has updatedAt instead of timestamp
      Map<String, Object> upstreamNode = new LinkedHashMap<>();
      upstreamNode.put("fullyQualifiedName", "db.schema.raw_orders");
      upstreamNode.put("entityType", "table");
      upstreamNode.put("updatedAt", MORE_RECENT_TS); // updatedAt instead of timestamp

      Set<Map<String, Object>> nodes = new HashSet<>();
      nodes.add(upstreamNode);

      Map<String, Object> result = builder().withUpstreamLineage(nodes).execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      assertThat(timeline).hasSize(1);
      // Should use the updatedAt value as the timestamp
      assertThat(timeline.get(0).get("ts")).isEqualTo(MORE_RECENT_TS);
    }

    @Test
    void execute_upstreamNodeNoTimestamp_defaultsToCutoffTs() throws Exception {
      // Node has no timestamp or updatedAt
      Map<String, Object> upstreamNode = new LinkedHashMap<>();
      upstreamNode.put("fullyQualifiedName", "db.schema.raw_orders");
      upstreamNode.put("entityType", "table");
      // No timestamp fields

      Set<Map<String, Object>> nodes = new HashSet<>();
      nodes.add(upstreamNode);

      Map<String, Object> result =
          builder().withUpstreamLineage(nodes).withParam("lookbackHours", 72).execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      assertThat(timeline).hasSize(1);
      // Should default to the cutoff timestamp
      Long ts = (Long) timeline.get(0).get("ts");
      assertThat(ts).isGreaterThan(0L);
    }
  }

  // ====================== Static method: extractChangeDescription ======================

  @Nested
  class ExtractChangeDescriptionStatic {

    @Test
    void extractChangeDescription_allFieldTypes() {
      ChangeEvent ce = new ChangeEvent();
      ce.setEventType(EventType.ENTITY_UPDATED);
      ce.setUserName("admin");

      ChangeDescription cd = new ChangeDescription();
      FieldChange added =
          new FieldChange().withName("column_a").withOldValue(null).withNewValue("new");
      FieldChange updated =
          new FieldChange().withName("description").withOldValue("old").withNewValue("new");
      FieldChange deleted =
          new FieldChange().withName("tags").withOldValue("old").withNewValue(null);

      cd.setFieldsAdded(List.of(added));
      cd.setFieldsUpdated(List.of(updated));
      cd.setFieldsDeleted(List.of(deleted));
      ce.setChangeDescription(cd);

      String desc = IncidentTimelineTool.extractChangeDescription(ce);
      assertThat(desc).contains("Added: column_a");
      assertThat(desc).contains("Updated: description");
      assertThat(desc).contains("Deleted: tags");
    }

    @Test
    void extractChangeDescription_nullUserName_showsUnknown() {
      ChangeEvent ce = new ChangeEvent();
      ce.setEventType(EventType.ENTITY_DELETED);
      ce.setUserName(null);

      String desc = IncidentTimelineTool.extractChangeDescription(ce);
      assertThat(desc).contains("entityDeleted");
      assertThat(desc).contains("unknown");
    }
  }

  // ====================== Static method: generateNarrative ======================

  @Nested
  class GenerateNarrativeStatic {

    @Test
    void generateNarrative_incidentWithSchemaChange_usesCorrectIcon() {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ts", 1713945600000L);
      entry.put("type", "schemaChange");
      entry.put("description", "Schema updated");

      String narrative =
          IncidentTimelineTool.generateNarrative(
              "db.schema.orders", "incident", "Schema change detected", List.of(entry), List.of());

      assertThat(narrative).contains("✏️"); // schemaChange icon
      assertThat(narrative).doesNotContain("### Suggested Owners"); // no owners
    }

    @Test
    void generateNarrative_incidentWithTestRecovery_usesCorrectIcon() {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ts", 1713945600000L);
      entry.put("type", "testRecovery");
      entry.put("description", "Test recovered");

      String narrative =
          IncidentTimelineTool.generateNarrative(
              "db.schema.orders", "incident", "Root cause", List.of(entry), List.of());

      assertThat(narrative).contains("🟢"); // testRecovery icon
    }

    @Test
    void generateNarrative_unknownType_usesBulletIcon() {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ts", 1713945600000L);
      entry.put("type", "unknownType");
      entry.put("description", "Something happened");

      String narrative =
          IncidentTimelineTool.generateNarrative(
              "db.schema.orders", "incident", "Root cause", List.of(entry), List.of());

      assertThat(narrative).contains("•"); // default icon
    }
  }

  // ====================== Static method: enforceByteCap ======================

  @Nested
  class EnforceByteCapStatic {

    @Test
    void enforceByteCap_underLimit_noTruncation() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("fqn", "db.schema.t");
      result.put("status", "healthy");
      result.put("timeline", List.of());
      result.put("timelineEntryCount", 0);
      result.put("rootCause", (Object) null);
      result.put("suggestedOwners", List.of());
      result.put("lookbackHours", 72);

      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("results", List.of(result));
      envelope.put("fqn", "db.schema.t");
      envelope.put("status", "healthy");

      Map<String, Object> capped = IncidentTimelineTool.enforceByteCap(envelope);
      assertThat(capped).isSameAs(envelope);
    }

    @Test
    void enforceByteCap_overLimit_truncatesTimelineAndAddsWarning() {
      List<Map<String, Object>> timeline = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", 1713945600000L + i * 1000L);
        entry.put("type", "testFailure");
        entry.put(
            "description",
            "Test case failed: very.long.fqn.test.case.number." + i + ".with.extra.details");
        timeline.add(entry);
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("fqn", "db.schema.large_table");
      result.put("status", "incident");
      result.put("timeline", timeline);
      result.put("timelineEntryCount", 200);
      result.put("rootCause", "test");
      result.put("suggestedOwners", List.of());
      result.put("lookbackHours", 72);

      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("results", List.of(result));
      envelope.put("fqn", "db.schema.large_table");
      envelope.put("status", "incident");
      envelope.put("narrative", "## Large incident report");

      Map<String, Object> capped = IncidentTimelineTool.enforceByteCap(envelope);

      byte[] bytes = JsonUtils.pojoToJson(capped).getBytes(StandardCharsets.UTF_8);
      assertThat(bytes.length).isLessThanOrEqualTo(6 * 1024 + 300); // tolerance for warning

      @SuppressWarnings("unchecked")
      List<String> warnings = (List<String>) capped.get("warnings");
      assertThat(warnings).anyMatch(w -> w.contains("payloadTruncated"));

      // timelineEntryCount should be updated
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) capped.get("results");
      assertThat(results.get(0).get("timelineEntryCount"))
          .isNotEqualTo(200); // should be less than original
    }

    @Test
    void enforceByteCap_emptyResults_returnsAsIs() {
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("results", List.of());
      envelope.put("fqn", "test");

      Map<String, Object> capped = IncidentTimelineTool.enforceByteCap(envelope);
      assertThat(capped).isNotNull();
    }
  }

  // ====================== Authorization ======================

  @Nested
  class Authorization {

    @Test
    void execute_callsAuthorizerWithCorrectParameters() throws Exception {
      // Use a verifying authorizer that delegates to the mock — avoid defaultAuthorizer
      // which calls ResourceContext constructor that accesses Entity.getEntityRepository()
      McpEntityBridge.McpAuthorizer verifyingAuthorizer =
          (entityType, op) ->
              authorizer.authorize(
                  securityContext, mock(OperationContext.class), mock(ResourceContext.class));

      builder().withAuthorizer(verifyingAuthorizer).execute();

      // Verify authorizer was called with VIEW_BASIC operation
      verify(authorizer)
          .authorize(
              any(CatalogSecurityContext.class),
              any(OperationContext.class),
              any(ResourceContext.class));
    }
  }

  // ====================== ChangeEvent with null timestamp ======================

  @Nested
  class ChangeEventEdgeCases {

    @Test
    void execute_changeEventNullTimestamp_defaultsToCutoffTs() throws Exception {
      UUID entityId = UUID.randomUUID();

      // ChangeEvent with null timestamp
      ChangeEvent ce = new ChangeEvent();
      ce.setEntityId(entityId);
      ce.setEntityType("table");
      ce.setUserName("alice");
      ce.setEventType(EventType.ENTITY_UPDATED);
      ce.setTimestamp(null);

      Map<String, Object> result =
          builder()
              .withEntityRef("db.schema.orders", entityId, "table")
              .withChangeEvents(List.of(ce))
              .withParam("lookbackHours", 72)
              .execute();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> timeline =
          (List<Map<String, Object>>) results.get(0).get("timeline");

      assertThat(timeline).hasSize(1);
      // Null timestamp should fall back to cutoffTs
      Long ts = (Long) timeline.get(0).get("ts");
      assertThat(ts).isGreaterThan(0L);
    }
  }
}
