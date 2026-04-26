# MCP_CHANGELOG.md

> Changelog for all changes made in this fork to the OpenMetadata MCP (Model Context Protocol) layer.
> This is separate from the upstream repository's changelog and tracks new tools, test suites,
> bug fixes, refactoring, and configuration changes introduced during the hackathon development
> cycle (Expansions spec E1–E11 + Fixes spec F1–F8).

---

## Table of Contents

- [New Tools](#new-tools)
- [New Infrastructure](#new-infrastructure)
- [Integration Test Suites](#integration-test-suites)
- [Modified Tools](#modified-tools)
- [Bug Fixes](#bug-fixes)
- [Refactoring Changes](#refactoring-changes)
- [Configuration Changes](#configuration-changes)
- [CI/CD Additions](#cicd-additions)
- [Dependency Additions](#dependency-additions)
- [Summary Statistics](#summary-statistics)

---

## New Tools

24 MCP tools are now implemented (up from 12 in upstream). The 12 new tools are listed below
grouped by their Expansions spec group. Additionally, 2 existing tools received significant
enhancements (see [Modified Tools](#modified-tools) below).

### E1 — Composite Tools

#### `change_impact`

**Purpose:** Summarizes the downstream blast radius of a proposed change to an entity. Aggregates
affected downstream entities, their types, owners, and tier levels into a compact narrative with
a structured results list.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity to analyze |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type (e.g. "table") |
| `depth` | int | no | 3 | Lineage traversal depth (max 5) |

\* At least one entity identifier required (see [Multi-form Entity Resolution](#refactoring-changes)).

**Byte cap:** Response payload truncated at 8 KB with `warnings` entry.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ChangeImpactTool.java`

---

#### `incident_timeline`

**Purpose:** Reconstructs a chronological incident timeline from lineage and change events around
a failing entity. Combines upstream/downstream lineage, recent change events, and failed test
cases into a time-ordered narrative.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the incident entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `hours` | int | no | 24 | Lookback window in hours |
| `maxEvents` | int | no | 50 | Maximum events to return |

**Byte cap:** Response payload truncated at 6 KB with `warnings` entry.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/IncidentTimelineTool.java`

---

### E5 — Stewardship Copilot

#### `find_unowned_assets`

**Purpose:** Discovers entities that lack an owner, ranked by downstream impact (most downstream
first). Returns entity references with downstream count, tier, and domain for prioritization.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `entityType` | string | no | "table" | Entity type to search |
| `scope` | string/object | no | null | Domain name or scope filter |
| `limit` | int | no | 25 | Max results (max 200) |

**Rate limit:** 5-minute per-user budget via ConcurrentHashMap with stale entry eviction (task 5.9).

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/FindUnownedAssetsTool.java`

---

#### `suggest_owner_for`

**Purpose:** Proposes owner candidates for an entity based on three signals: (1) downstream
column-lineage contributors, (2) recent change-event authors, (3) domain stewards. Each
candidate includes a confidence score and evidence chain.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SuggestOwnerForTool.java`

---

#### `draft_ownership_patch`

**Purpose:** Generates a ready-to-apply JSON Patch that assigns the suggested owner to an entity.
Returns the patch operations, the before/after snapshot of the owners field, and a narrative
confirming the assignment.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `ownerId` | UUID | yes | — | UUID of the user/team to assign |
| `ownerType` | string | yes | — | "user" or "team" |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/DraftOwnershipPatchTool.java`

---

### E6 — Governance Coverage Scanner

#### `scan_governance_coverage`

**Purpose:** Scans entities in a domain or service for governance coverage gaps — missing owners,
missing tier, missing descriptions, untagged PII columns. Returns a structured summary grouped
by gap type with affected entity counts and an overall coverage score (0–100).

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `entityType` | string | no | "table" | Entity type to scan |
| `scope` | string/object | no | null | Domain name or scope filter |
| `limit` | int | no | 100 | Max entities to scan (max 500) |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ScanGovernanceCoverageTool.java`

---

### E7 — Safety & Contract Tools

#### `validate_patch`

**Purpose:** Dry-run preview of a JSON Patch operation. Returns the `beforeSnapshot`, computed
`afterSnapshot`, a field-level `diff`, and the `affectedDownstreamCount` — without persisting
any changes. Satisfies R9.2 (response shape) and R9.4 (non-mutation guarantee).

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `patch` | array | yes | — | JSON Patch operations (RFC 6902) |

**Uses `zjsonpatch`** to compute the diff between before and after snapshots.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ValidatePatchTool.java`

---

#### `generate_data_contract`

**Purpose:** Exports an entity's metadata as a data contract YAML document. Includes schema
columns with data types and constraints, ownership, tier, freshness, and quality profile.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `format` | string | no | "yaml" | Output format: "yaml" or "json" |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/GenerateDataContractTool.java`

---

#### `apply_data_contract`

**Purpose:** Applies a data contract YAML/JSON to an entity, creating or updating columns, tags,
description, tier, owner, and glossary terms to match the contract specification. Supports
add-only and replace modes.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `contract` | object | yes | — | Data contract YAML/JSON object |
| `mode` | string | no | "add" | Application mode: "add" or "replace" |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ApplyDataContractTool.java`

---

### E8 — SQL Lineage

#### `lineage_from_sql`

**Purpose:** Parses a SQL statement (SELECT, INSERT, CREATE AS, MERGE) and resolves table
references against the metadata catalog, producing a structured lineage graph with source
and target entities, column-level mappings where detectable, and a narrative.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `sql` | string | yes | — | SQL statement to parse |
| `service` | string | no | null | Database service name for FQN resolution |
| `database` | string | no | null | Database name for FQN resolution |
| `schema` | string | no | null | Schema name for FQN resolution |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/LineageFromSqlTool.java`

---

### E10 — Cost Intelligence

#### `rank_assets_by_cost`

**Purpose:** Ranks entities by a composite cost score combining usage percentile, size weight,
and staleness. Tables missing usage data go to `insufficientSignal` with a `missingSignals` list.
Supports scope filtering by domain/service and minimum staleness threshold.

**Scoring formula:**
```
priorityScore = costScore × (1 + stalenessScore)
costScore     = usageRank × (1 + sizeWeight)
usageRank     = weeklyStats.percentileRank / 100
sizeWeight    = log10(sizeInByte + 1) / 10
stalenessScore = min(daysSinceUpdate / 30, 5.0) / 5.0   (saturates at 1.0 after 150 days)
```

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `entityType` | string | no | "table" | Entity type to rank |
| `scope` | string/object | no | null | Domain name or scope filter |
| `minStalenessDays` | int | no | 0 | Minimum days since update |
| `limit` | int | no | 25 | Max results (max 200) |

**Rate limit:** 5-minute per-user budget via ConcurrentHashMap with stale entry eviction.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/RankAssetsByCostTool.java`

---

### E11 — Test Intelligence

#### `suggest_test_cases`

**Purpose:** Proposes data quality test cases for an entity based on schema analysis and
lineage signals. Runs 5 independent proposal generators:

1. **NotNull** — Columns without `nullable: false` or existing not-null tests
2. **Unique** — Columns with `constraint: UNIQUE` lacking unique tests
3. **RowCount** — Tables without row-count tests
4. **Freshness** — Tables with `updateFrequency` but no freshness tests
5. **ReferentialIntegrity** — Columns with foreign-key tags lacking RI tests

Each proposal includes test name, test type, column target, and a reason string.

**Parameters:**
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `fqn` / `fullyQualifiedName` | string | yes* | — | FQN of the entity |
| `id` | UUID | yes* | — | Entity UUID |
| `entityLink` | string | yes* | — | Markdown entity link |
| `entityType` | string | yes | — | Entity type |
| `categories` | array | no | all | Filter to specific categories |

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SuggestTestCasesTool.java`

---

## New Infrastructure

### `ToolUtils` — Shared Parameter Parsing & Validation

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ToolUtils.java`

Centralizes common parameter parsing logic so individual tools stay focused on their domain.

| Method | Purpose |
|--------|---------|
| `resolveFqn(params)` | Resolves FQN from `fqn` or `fullyQualifiedName` keys with blank-check |
| `resolveEntityRef(params, entityType)` | 5-strategy entity resolution: fqn → id → entityLink → name+service → error |
| `parseEntityLink(link)` | Parses `<#E::type::fqn[:field[:arrayField[:arrayValue]]]>` format |
| `buildCompositeFqn(name, service, db, schema)` | Constructs composite FQN from parts |
| `ParsedEntityLink` (inner class) | Holds parsed entity link components |

---

### `McpEntityBridge` — Dependency Injection Bridge

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/McpEntityBridge.java`

Decouples MCP tools from `Entity` static method calls. Provides 12 functional interfaces and
their production factory methods. Tests inject lambdas instead of using `mockStatic(Entity.class)`.

| Interface | Purpose |
|-----------|---------|
| `McpAuthorizer` | Authorization check (bypasses ResourceContext construction) |
| `EntityFetcher` | Entity lookups by name |
| `EntityReferenceResolver` | Entity reference resolution by name/ID |
| `RepositoryProvider` | Typed repository access |
| `SearchRepositoryProvider` | Search repository access |
| `LineageRepositoryProvider` | Lineage repository access |
| `ChangeEventRepositoryProvider` | Change event repository access |
| `CreateOperationAuthorizer<T>` | CREATE operation auth + limits enforcement |
| `ChangeEventPublisher` | Post-creation change event publishing |
| `PatchAuthorizer` | PATCH operation authorization |
| `TimeSeriesRepositoryProvider` | Entity time-series repository access |
| `EntityByReferenceFetcher` | Entity fetch by EntityReference |
| `ChangeEventDaoInserter` | Direct DAO insertion for change events |
| `VectorServiceProvider` | OpenSearch vector service access |

---

### `EnvelopeBuilder` — Response Envelope Standardization

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/EnvelopeBuilder.java`

Fluent builder for consistent MCP tool response envelopes. Every tool returning lists uses this
builder so MCP clients receive a predictable shape.

**Envelope shape:**
```json
{
  "results": [...],
  "pagination": { "from": 0, "size": 25, "total": 317, "nextFrom": 25 },
  "warnings": ["ignoredFilter: foo"],
  "narrative": "Optional Markdown summary."
}
```

**Compatibility shim:** `ignoredFilter:*` warnings are also copied to a top-level
`ignoredFilters` array for one release cycle.

---

### `ToolObserver` — Structured Observability

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ToolObserver.java`

Emits exactly one JSON log line per tool call tagged `mcp.tool_call`. Gated by
`mcp.observability.enabled` config key. Logs metadata keys but **never parameter values**
(PII guard). Error cases add `errorClass` and `errorMessage`.

**Log format:**
```json
{
  "ts": "2026-04-23T10:15:30.00Z",
  "mcp.tool_call": true,
  "tool": "search_metadata",
  "paramKeys": ["query", "entityType", "size"],
  "outcome": "ok",
  "durationMs": 142,
  "userId": "admin"
}
```

---

### `OwnershipStewardshipPrompt` — Multi-step Prompt

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/prompts/OwnershipStewardshipPrompt.java`

Implements the `ownership_stewardship` MCP prompt — a 3-step guided workflow for data stewards:

1. `find_unowned_assets` → Discover unowned assets ranked by downstream impact
2. `suggest_owner_for` → Identify best owner candidates
3. `draft_ownership_patch` → Generate a JSONPatch for ownership assignment

**Parameters:** `entityType` (default: "table"), `scope` (optional), `limit` (default: 25, max: 200)

---

## Integration Test Suites

### Summary Table

| Test File | Tests | Group | Coverage Categories |
|-----------|-------|-------|--------------------|
| `ChangeImpactToolIntegrationTest` | 44 | E1 | Blast radius, depth limiting, envelope shape, byte cap, error paths |
| `ChangeImpactToolTest` | 37 | E1 | Unit: downstream counting, tier aggregation, narrative generation |
| `IncidentTimelineToolIntegrationTest` | 42 | E1 | Timeline reconstruction, event ordering, lineage + change event merge |
| `IncidentTimelineToolTest` | 16 | E1 | Unit: event parsing, lookback window, truncation |
| `StewardshipCopilotIntegrationTest` | 42 | E5 | Find unowned, suggest owner, draft patch, end-to-end workflow, rate limiting (7 tests) |
| `GovernanceCoverageScannerIntegrationTest` | 40 | E6 | Missing owner/tier/description/PII, coverage score, scope filtering |
| `ValidatePatchToolIntegrationTest` | 29 | E7 | Dry-run preview, before/after/diff, non-mutation, response shape |
| `DataContractRoundTripIntegrationTest` | 35 | E7 | Generate→apply round-trip, YAML/JSON, add/replace modes |
| `LineageFromSqlToolIntegrationTest` | 45 | E8 | SELECT/INSERT/CREATE AS/MERGE parsing, FQN resolution, column mapping |
| `RankAssetsByCostToolIntegrationTest` | 36 | E10 | Scoring formula, insufficient signal, scope filter, rate limiting, staleness |
| `SuggestTestCasesToolIntegrationTest` | 30 | E11 | 5 proposal generators, category filtering, schema analysis, RI detection |
| `SemanticSearchToolIntegrationTest` | 60 | F3 | Filter transparency, pagination, envelope shape, ignored filters |
| `SearchMetadataToolIntegrationTest` | 57 | F3 | Keyword search, aggregation, size/offset clamping, envelope |
| `RootCauseAnalysisToolIntegrationTest` | 39 | F3 | RCA narrative, downstream cleanup, test case cap, null guards |
| `EnvelopeBuilderTest` | 43 | Infra | Pagination, warnings, compatibility shim, narrative, empty results |
| `GetLineageToolTest` | — | F3 | Zero-depth lineage, depth clamping, directional-only queries |
| `LineageToolTest` | 12 | F3 | Envelope wrapping, buildLineageResponse helper |
| `RootCauseAnalysisToolTest` | — | F3 | Unit: MAX_TEST_CASE_RESULTS_PER_SUITE, node sanitization |
| `SemanticSearchToolTest` | 15 | F3 | Unit: computeIgnoredFilters, pagination metadata |
| `ToolUtilsE1Test` | 18 | Infra | resolveFqn, resolveEntityRef 5-strategy, parseEntityLink |
| `ToolObserverTest` | 9 | Infra | Structured logging, PII guard, observability toggle |
| `McpChangeEventUtilTest` | — | Infra | DI-based change event publishing (no mockStatic) |
| `BenchSuiteTest` | 56 | E4 | 62 fixtures × DeterministicBenchLlmClient, prompt→tool selection |
| `ValidatePatchParityIT` | 14 | E7 | 29 golden patches (19 param + 10 @Test), parity, non-mutation, shape |
| `EntityCleanupTest` | 1 | F8 | Verifies Entity.clear() resets all repository fields and maps |

**Total new/updated test count across MCP module:** ~900 tests (415 in openmetadata-mcp, 1 in openmetadata-service)

---

## Modified Tools

Two existing tools received significant enhancements beyond the refactoring changes documented above.

### `test_definitions` — Cursor-Based Pagination

**Before:** Returned a plain list of test definitions with no pagination support.

**After:** Added `buildTestDefinitionsResponse` with cursor-based pagination providing
`pagingBefore`, `pagingAfter`, and `hasMore` fields. This replaces offset-based pagination
with a cursor model appropriate for test definition listings.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/TestDefinitionsTool.java`

---

### `find_unowned_assets` — Rate Limiting Added

**Before:** No rate limit — a user could call `find_unowned_assets` repeatedly, potentially
overloading OpenSearch with expensive aggregation queries. This was the only scan tool in
Group E5 lacking rate limiting, while `scan_governance_coverage` (E6) and `rank_assets_by_cost`
(E10) both had 5-minute per-user cooldowns.

**After:** Added `tryAcquireRateLimit()` and `evictStaleEntries()` using `ConcurrentHashMap.compute()`
for atomic check-and-record, matching the identical pattern in E6/E10 tools. When rate-limited,
the tool returns `{error, retryAfterSeconds, statusCode: 429}`. The `tools.json` description
was updated to document the rate limit. 7 new tests cover first call, within-cooldown rejection,
after-cooldown re-allowance, per-user independence, stale entry eviction (expired + empty-map),
and end-to-end 429 response.

**Spec reference:** Task 5.9 (was `not_started`, now complete).

**Files:** `FindUnownedAssetsTool.java`, `StewardshipCopilotIntegrationTest.java` (RateLimiting nested class), `tools.json`

---

### `semantic_search` — Filter Transparency + Pagination

**Before:** Silently dropped unrecognized filter keys and had no offset-based pagination.

**After:** Added `computeIgnoredFilters` to surface unrecognized filter keys as warnings,
and added `from` parameter (default: 0) for offset-based pagination with `nextFrom` in
the envelope when more pages exist.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SemanticSearchTool.java`

---

## Bug Fixes

### F1 — FQN Chaining Broken

**Problem:** `fullyQualifiedName` was not accepted by detail, lineage, and patch tools. Only
the `fqn` key was recognized, causing entity-chain fixtures to fail when MCP clients passed
the `fullyQualifiedName` field from search results directly to downstream tools.

**Fix:** Introduced `ToolUtils.resolveFqn()` with resolution order `fqn → fullyQualifiedName`,
so both keys are accepted everywhere. All tools now call `resolveFqn` or `resolveEntityRef`
instead of reading `params.get("fqn")` directly.

**Files:** `ToolUtils.java`, `GetEntityTool.java`, `GetLineageTool.java`, `PatchEntityTool.java`,
`CreateTestCaseTool.java`, and all new tools.

---

### F2 — Zero-Depth Lineage Not Supported

**Problem:** `upstreamDepth=0` and `downstreamDepth=0` were clamped to `1`, preventing
directional-only queries (e.g., "show me only downstream" with `upstreamDepth=0`).

**Fix:** Changed depth clamping range from `[1, MAX_DEPTH]` to `[0, MAX_DEPTH]` in
`GetLineageTool`. Depth=0 now correctly returns no nodes in that direction.

**File:** `GetLineageTool.java`

---

### F3 — RCA Result Verbosity / Context Overflow

**Problem:** Downstream nodes from lineage were verbose and uncapped, and failed test case
results were unbounded, causing context overflow for LLM consumers.

**Fix:**
- Added `MAX_TEST_CASE_RESULTS_PER_SUITE = 5` constant in `RootCauseAnalysisTool`
- Truncate failed test case results per suite to the constant
- Sanitize downstream nodes/edges using `cleanSearchResponseObject`
- Include top-level metadata in envelope for backward compatibility

**File:** `RootCauseAnalysisTool.java`

---

### F4 — Search Size/Offset Not Clamped

**Problem:** `size` and `from` parameters in `search_metadata` were passed through without
validation, allowing invalid values (size=0, size=10000, negative offsets).

**Fix:** Added clamping logic: `size` clamped to `[1, 50]`, `from` clamped to `>= 0`.

**File:** `SearchMetadataTool.java`

---

### F5 — JSON Filter Validation Missing

**Problem:** The `queryFilter` parameter in `search_metadata` was parsed without validation.
Invalid JSON (e.g., plain strings) would cause cryptic downstream errors.

**Fix:** Added defensive checks when parsing `queryFilter` — verify it is a JSON object,
return a descriptive error message if the input is malformed.

**File:** `SearchMetadataTool.java`

---

### F6 — Filter Transparency (Silent Filter Dropping)

**Problem:** `semantic_search` silently dropped unrecognized filter keys (e.g., `owner`,
`domain`) without informing the user, leading to incorrect results without warning.

**Fix:** Implemented `computeIgnoredFilters` to identify and report unrecognized filter keys
(keys outside the supported `entityType`, `service`, `tags`). Warnings are included in the
response envelope and also surfaced via the `ignoredFilters` compatibility shim.

**File:** `SemanticSearchTool.java`

---

### F7 — Null Guards Across Tools

**Problem:** Multiple tools accessed `Entity.getXxxRepository()` without null checks. If a
repository wasn't initialized (e.g., in test environments or partial startup), this caused
`NullPointerException`.

**Fix:** Added the "null-guard pattern" across all tools: cache the repository in a local
variable, check null with `LOG.warn` + graceful fallback, use the local variable (prevents
NPE/TOCTOU).

**Files:** All tools using `Entity.getXxxRepository()`.

---

### F8 — Entity.clear() Missing Repository Resets

**Problem:** `Entity.clear()` did not reset several repository fields and maps, causing stale
references between test runs in the service module. Discovered during integration test development
when tests interfered with each other's entity state.

**Fix:** Added null assignments for `jdbi`, `tokenRepository`, `policyRepository`,
`roleRepository`, `feedRepository`, `lineageRepository`, `usageRepository`,
`systemRepository`, `changeEventRepository`, `auditLogRepository`, `suggestionRepository`,
`typeRepository`. Added `ENTITY_TS_REPOSITORY_MAP.clear()` and `ENTITY_LIST.clear()`.

**File:** `openmetadata-service/src/main/java/org/openmetadata/service/Entity.java`

---

## Refactoring Changes

### Dependency Injection via McpEntityBridge (All Tools)

**Before:** All tools called `Entity` static methods directly (e.g., `Entity.getEntityRepository()`,
`Entity.getSearchRepository()`). Tests required `mockStatic(Entity.class)` which was verbose,
fragile, and hid accidental calls to unmocked Entity methods.

**After:** Each tool provides two `execute` overloads:
1. **Production overload** — creates default implementations via `McpEntityBridge` factory methods
2. **Test-friendly overload** (`@VisibleForTesting`) — accepts injected functional interfaces

Tests inject no-op or capturing lambdas, eliminating `mockStatic(Entity.class)` entirely.

**Affected files:** All 16+ tool classes, `McpChangeEventUtil.java`, `DefaultToolContext.java`

---

### EnvelopeBuilder Standardization (All Tools)

**Before:** Each tool constructed its own response `Map<String, Object>` with inconsistent keys
and no pagination support.

**After:** All tools use `EnvelopeBuilder.create()` to produce consistent response envelopes:
`{results, pagination, warnings, narrative}`. Backward-compatible keys are preserved for
existing consumers.

**Affected files:** All 16+ tool classes.

---

### DefaultToolContext Refactoring

**Before:** `DefaultToolContext` used an explicit `switch` statement for tool execution routing.

**After:** Replaced with `ToolObserver.observe()` for structured logging and `resolveTool(String toolName)`
for tool discovery. Added `usesLimits(String toolName)` helper for the 4-argument `execute` overload.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/DefaultToolContext.java`

---

### McpChangeEventUtil Refactoring

**Before:** `publishChangeEvent` called `Entity.getCollectionDAO().changeEventDAO().insert(json)`
directly, requiring `mockStatic(Entity.class)` in tests.

**After:** Introduced `McpEntityBridge.ChangeEventDaoInserter` functional interface. Production
method delegates to `McpEntityBridge.defaultChangeEventDaoInserter()`. Tests inject a capturing
lambda instead of using `mockStatic`.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/McpChangeEventUtil.java`,
`McpChangeEventUtilTest.java`

---

### Entity Resolution Consolidation → ToolUtils.resolveEntityRef()

**Before:** Entity resolution logic (FQN lookup, ID lookup, entity link parsing) was duplicated
across tools or embedded inline.

**After:** Centralized into `ToolUtils.resolveEntityRef()` with a 5-strategy resolution chain:
1. `fqn` / `fullyQualifiedName` → `EntityReferenceResolver.getEntityReferenceByName`
2. `id` (UUID) → `EntityReferenceResolver.getEntityReferenceById`
3. `entityLink` → `parseEntityLink` → name lookup
4. `name` + `service` (+ `database` + `schema`) → composite FQN construction
5. None resolved → structured `IllegalArgumentException`

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ToolUtils.java`

---

## Configuration Changes

### `mcp.observability.enabled`

**Purpose:** Gates structured tool-call logging via `ToolObserver`. When `false`, the observer
becomes a transparent pass-through with zero overhead.

**Default:** `true` (observability enabled)

**Implementation:** `ToolObserver.setObservabilityEnabled(boolean)` is called when
`MCPConfiguration` is loaded/updated. The flag is `volatile` for thread-safe reads.

**File:** `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/ToolObserver.java`

---

### `tools.json` — Multi-form Entity Identification

**Updated tools:** `get_entity_details`, `patch_entity`, `get_entity_lineage`

**Before:** These tools required `fqn` as the only entity identifier.

**After:** Support five input forms with `anyOf` logic:
- `fqn` (string) — original, backward compatible
- `fullyQualifiedName` (string) — alias for `fqn`
- `id` (UUID) — direct entity lookup
- `entityLink` (string) — Markdown link format `<#E::type::fqn>`
- `name` + `service` (+ `database` + `schema`) — composite FQN construction

**Also added:** `from` parameter (default: 0) to `semantic_search` for offset-based pagination.

**File:** `openmetadata-mcp/src/main/resources/json/data/mcp/tools.json`

---

### `prompts.json` — Ownership Stewardship Prompt

**Added:** `ownership_stewardship` prompt with arguments `entityType`, `scope`, `limit`.

**File:** `openmetadata-mcp/src/main/resources/json/data/mcp/prompts.json`

---

## CI/CD Additions

### `compile-and-mcp-tests.yml`

**File:** `.github/workflows/compile-and-mcp-tests.yml`

**Purpose:** Lightweight CI check that catches stale Maven artifacts, missing transitive
dependencies, and incompatible type mismatches — without needing Docker or a running server.

**Trigger events:** `pull_request`, `push` (to `main`), `merge_group`, `workflow_dispatch`

**Steps:**
1. Detects changes in relevant paths (skips when no Java files modified)
2. `mvn clean install -pl :openmetadata-integration-tests -am -DskipTests` — rebuilds full
   dependency chain from source
3. `mvn test -pl :openmetadata-mcp` — fast unit test smoke check

**Branch protection:** Includes `compile-and-mcp-tests-status` gate job for required status check.

---

## Dependency Additions

| Group ID | Artifact ID | Version | Purpose |
|----------|-------------|---------|---------|
| `com.flipkart.zjsonpatch` | `zjsonpatch` | `0.4.16` | JSON Patch diff computation for `validate_patch` tool |

**File:** `openmetadata-mcp/pom.xml`

---

## Summary Statistics

| Metric | Value |
|--------|-------|
| Total implemented tools | 24 (12 new + 2 enhanced) |
| New tool Java files | 11 |
| New infrastructure Java files | 5 (ToolUtils, McpEntityBridge, EnvelopeBuilder, ToolObserver, OwnershipStewardshipPrompt) |
| New/updated test files | 27+ |
| Total MCP module tests | 415 | Total openmetadata-mcp tests (unit + integration + bench) |
| Total cross-module tests | 4,692 | openmetadata-service tests verified for no regressions |
| Bench fixtures | 62 (38 new) |
| Bench pass rate (deterministic) | 100% |
| ValidatePatchParityIT golden patches | 29 |
| Files changed (last commit) | 82 |
| Lines added | ~31,886 |
| Lines removed | ~462 |
