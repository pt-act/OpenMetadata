# Testing Strategy

## CI Workflows

### Integration Tests Compile & MCP Unit Tests

**Workflow file:** `OpenMetadata-main/.github/workflows/compile-and-mcp-tests.yml`

A lightweight CI check that runs on PRs and pushes to `main` touching relevant Java modules. It catches stale Maven artifacts, missing transitive dependencies, and incompatible type mismatches early — without needing Docker or a running server.

**Trigger events:** `pull_request`, `push` (to `main`), `merge_group`, and `workflow_dispatch` (manual trigger from the Actions tab).

**What it does:**

1. **Detects changes** in relevant paths using `dorny/paths-filter`. Skipped when no Java files are modified (reports as "Success" for branch protection).
2. **Clean-installs upstream modules** from source:
   ```
   mvn -B clean install -pl :openmetadata-integration-tests -am -DskipTests
   ```
   This rebuilds the full dependency chain (`common → openmetadata-spec → openmetadata-sdk → openmetadata-service → openmetadata-mcp`) before compiling `openmetadata-integration-tests`, preventing stale `~/.m2` artifacts that cause "cannot access" and "incompatible types" errors.
3. **Runs `openmetadata-mcp` unit tests** as a fast smoke check (no Docker needed):
   ```
   mvn -B test -pl :openmetadata-mcp -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true -Denforcer.skip=true
   ```

**Triggered paths** (relative to the repository root `OpenMetadata-main/`):

- `.github/workflows/compile-and-mcp-tests.yml`
- `openmetadata-service/**`
- `openmetadata-integration-tests/**`
- `openmetadata-spec/src/main/resources/json/schema/**`
- `openmetadata-sdk/**`
- `openmetadata-mcp/**`
- `openmetadata-clients/**`
- `common/**`
- `pom.xml`
- `bootstrap/**`

### Branch Protection: Required Status Check

The workflow includes a gate job (`compile-and-mcp-tests-status`) that serves as a single required status check for branch protection. It reports "Success" when the compile job passes or is legitimately skipped (no relevant changes), and reports failure only when the compile job actually fails or is cancelled.

**To configure branch protection:**

1. Go to **Settings → Branches → Branch protection rules** in the GitHub repository
2. Click **Edit** on the rule for your target branch (e.g., `main`)
3. Enable **"Require status checks to pass before merging"**
4. Add the required check: `compile-and-mcp-tests-status`
5. Click **Save changes**

> **Note:** The workflow must run at least once on the branch before GitHub will recognize `compile-and-mcp-tests-status` as a valid status check name to add. You can trigger a run via `workflow_dispatch` from the Actions tab.

### Why This Workflow Exists

Without `mvn clean install -am`, cached `~/.m2/repository` artifacts from a previous commit can produce misleading compilation errors when schema classes change between commits. For example:

```
error: cannot access org.openmetadata.schema.entity.teams.Role
  class file for org.openmetadata.schema.entity.teams.Role not found
incompatible types: DatabaseSchema cannot be converted to
  org.openmetadata.schema.entity.data.DatabaseSchema
```

These errors appear to be source code bugs but are actually caused by stale local artifacts. The `clean install -am` step eliminates them by always rebuilding from source.

---

## Integration Tests

### ValidatePatchParityIT — R9.8 Parity Test

**File:** `OpenMetadata-main/openmetadata-integration-tests/src/test/java/org/openmetadata/it/tests/mcp/ValidatePatchParityIT.java`

Verifies that the MCP `validate_patch` tool's `afterSnapshot` matches the actual post-state produced by `patch_entity` for a set of "golden patches". This is the R9.8 parity requirement: the dry-run preview must be identical to what the real patch would produce.

**Requires a running OpenMetadata server** with MCP enabled (tagged `@Tag("mcp")`).

**How to run:**

```bash
# Start the server (Docker or local), then:
cd OpenMetadata-main
mvn verify -pl :openmetadata-integration-tests -Dit.test=ValidatePatchParityIT -Dgroups="mcp"
```

**Test strategy:**

1. `@BeforeAll` — Creates a test table, resolves the admin user ID (for owner patches), creates a test domain (for domain patches), and captures the entity's original state (description, tags, owners, domains, extension)
2. For each golden patch (parameterized or separate `@Test`):
   - Restore to clean baseline
   - Apply a precondition patch if needed (e.g., add a tier before testing "remove tier")
   - Call `validate_patch` → capture `afterSnapshot`
   - Call `patch_entity` with the same patch → apply it for real
   - GET the entity from the server → authoritative current state
   - Compare `afterSnapshot` field with actual server state
   - Restore baseline for the next test
3. `@AfterAll` — Deletes the test table and test domain

**29 golden patches tested** (19 parameterized + 10 separate `@Test` methods):

*Parameterized patches* (1–19, use `@MethodSource`):

| # | Patch | Precondition | Field compared |
|---|-------|-------------|---------------|
| 1 | Replace description | — | `description` |
| 2 | Add PII.Sensitive tag | — | `tags` |
| 3 | Clear description | — | `description` |
| 4 | Restore description via "add" op | Clear description first | `description` |
| 5 | Add tier (Tier2) | — | `tier` |
| 6 | Replace tier (Tier2 → Tier3) | Add Tier2 first | `tier` |
| 7 | Remove tier | Add Tier2 first | `tier` |
| 8 | Add displayName | — | `displayName` |
| 9 | Replace displayName | Add displayName first | `displayName` |
| 10 | Remove displayName | Add displayName first | `displayName` |
| 11 | Add retentionPeriod (P30D) | — | `retentionPeriod` |
| 12 | Replace retentionPeriod (P30D → P60D) | Add P30D first | `retentionPeriod` |
| 13 | Remove retentionPeriod | Add P30D first | `retentionPeriod` |
| 14 | Add sourceUrl (https://example.com/source) | — | `sourceUrl` |
| 15 | Replace sourceUrl (source → updated) | Add sourceUrl first | `sourceUrl` |
| 16 | Remove sourceUrl | Add sourceUrl first | `sourceUrl` |
| 17 | Add schemaDefinition (DDL) | — | `schemaDefinition` |
| 18 | Replace schemaDefinition (DDL → different DDL) | Add schemaDefinition first | `schemaDefinition` |
| 19 | Remove schemaDefinition | Add schemaDefinition first | `schemaDefinition` |

*Owner patches* (20–23, separate `@Test` — require runtime-resolved `adminUserId`/`testUser2Id`):

| # | Patch | Precondition | Field compared |
|---|-------|-------------|---------------|
| 20 | Add owner | — | `owners` |
| 21 | Replace owners | Add owner first | `owners` |
| 22 | Replace owners with different user | Add admin as owner first | `owners` |
| 23 | Remove owners | Add owner first | `owners` |

*Domain patches* (24–27, separate `@Test` — require runtime-resolved `testDomainId`/`testDomain2Id`):

| # | Patch | Precondition | Field compared |
|---|-------|-------------|---------------|
| 24 | Add domain | — | `domains` |
| 25 | Replace domains | Add domain first | `domains` |
| 26 | Replace domains with different domain | Add domain1 first | `domains` |
| 27 | Remove domains | Add domain first | `domains` |

*Extension patches* (28–29, separate `@Test`):

| # | Patch | Precondition | Field compared |
|---|-------|-------------|---------------|
| 28 | Add extension field | — | `extension` |
| 29 | Remove extension field | Add extension field first | `extension` |

**Additional test cases:**

- **Non-mutation test (R9.4)** — Verifies `validate_patch` does NOT persist changes to the entity (read-only guarantee).
- **Response shape test (R9.2)** — Verifies `validate_patch` returns all required fields: `fqn`, `entityType`, `beforeSnapshot`, `afterSnapshot`, `diff`, `affectedDownstreamCount`, `affectedDownstreamCountNote`.

**Key design decisions:**

- **Replace-based tag/owner/domain restore** — Tags, owners, and domains are restored by replacing the entire array rather than removing items by index, avoiding index-shifting bugs.
- **Set-based comparison for EntityReference arrays** — Owner and domain order is not guaranteed, so they are compared as `Set<String>` of entity IDs (order-independent). This check runs **before** the generic array branch in `compareFields` to avoid EntityReference arrays being incorrectly matched as tags (which would produce vacuously-passing empty-set assertions).
- **Set-based tag comparison** — Tag order is not guaranteed, so tags are compared as `Set<String>` of `tagFQN` values (order-independent).
- **JSON tree comparison for extension** — Extension is a free-form object, compared using Jackson's `JsonNode.equals()`.
- **Separate `@Test` methods for owner/domain/extension** — These patches require runtime-resolved IDs (`adminUserId`, `testDomainId`) that are only available after `@BeforeAll` runs. Since `@MethodSource` executes at test discovery time (before `@BeforeAll`), these patches cannot use the parameterized approach. They use a shared `runParityTest()` helper to avoid duplicating the parity test logic.
- **Try/catch in `restoreBaseline`** — Prevents cascading failures if one test's cleanup fails.
- **`originalEntityState` captured at `@BeforeAll`** — Used to reconstruct the exact original tags/owners/domains/extension/retentionPeriod for restore.
- **RetentionPeriod inheritance note** — `retentionPeriod` can be inherited from the parent database/schema, but `createServiceDatabaseSchemaTable` creates entities without a parent retentionPeriod, so inheritance is not a concern for the current tests.
- **Manual SLF4J logger** — Uses `org.slf4j.LoggerFactory` directly (not `@Slf4j`) to avoid subclassing conflicts with `McpTestBase`.
