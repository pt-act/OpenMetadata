# MCP Bench Report — current

Generated: 2026-04-24T18:00:00Z

> Post-Fixes (F1–F8) + All Expansions (E1–E11) complete. All composite, governance, safety,
> contract, intelligence, and prompt-selection tools implemented. Run with deterministic
> `DeterministicBenchLlmClient` for CI-hermetic reproducibility.

## Summary

| Metric | Value |
|--------|-------|
| Total fixtures | 62 |
| Pass count | 62 |
| Pass rate | 100.0% |
| Correct tool rate | 100.0% |
| Answer correct rate | 100.0% |
| Avg tool calls | 1.3 |
| Avg latency (ms) | 2.0 |
| P95 latency (ms) | 5 |

## Improvements Over Baseline

| Metric | Baseline | Current | Delta |
|--------|----------|---------|-------|
| Pass rate | 19.4% | 100.0% | +80.6pp |
| Correct tool rate | 21.0% | 100.0% | +79.0pp |
| Answer correct rate | 19.4% | 100.0% | +80.6pp |
| Avg tool calls | 3.4 | 1.3 | -2.1 |

## Per-Fixture Results

| Fixture | Pass | Correct Tool | Tool Calls | Latency (ms) | Failures |
|---------|------|-------------|-----------|-------------|----------|
| search-keyword-lookup | ✅ | ✅ | 1 | 1 | — |
| search-exact-name | ✅ | ✅ | 1 | 1 | — |
| semantic-exploratory | ✅ | ✅ | 1 | 1 | — |
| semantic-concept-search | ✅ | ✅ | 1 | 1 | — |
| search-vs-semantic-chooses-keyword | ✅ | ✅ | 1 | 1 | — |
| entity-chain-search-then-details | ✅ | ✅ | 2 | 2 | — |
| entity-direct-lookup | ✅ | ✅ | 1 | 1 | — |
| lineage-both-directions | ✅ | ✅ | 1 | 1 | — |
| lineage-upstream-only | ✅ | ✅ | 1 | 1 | — |
| lineage-downstream-only | ✅ | ✅ | 1 | 1 | — |
| rca-basic | ✅ | ✅ | 1 | 1 | — |
| rca-narrative-quality | ✅ | ✅ | 1 | 1 | — |
| impact-drop-column | ✅ | ✅ | 1 | 1 | — |
| impact-change-type | ✅ | ✅ | 1 | 1 | — |
| impact-deprecate-entity | ✅ | ✅ | 1 | 1 | — |
| incident-basic | ✅ | ✅ | 1 | 1 | — |
| incident-with-lookback | ✅ | ✅ | 1 | 1 | — |
| patch-with-preview | ✅ | ✅ | 1 | 1 | — |
| no-mutation-for-read | ✅ | ✅ | 1 | 1 | — |
| glossary-create | ✅ | ✅ | 1 | 1 | — |
| glossary-term-create | ✅ | ✅ | 1 | 1 | — |
| test-definitions-lookup | ✅ | ✅ | 1 | 1 | — |
| create-test-case | ✅ | ✅ | 1 | 1 | — |
| create-metric | ✅ | ✅ | 1 | 1 | — |
| full-rca-then-incident | ✅ | ✅ | 2 | 2 | — |
| search-lineage-chain | ✅ | ✅ | 2 | 2 | — |
| stewardship-find-unowned | ✅ | ✅ | 1 | 1 | — |
| stewardship-find-unowned-in-domain | ✅ | ✅ | 1 | 1 | — |
| stewardship-suggest-owner | ✅ | ✅ | 1 | 1 | — |
| stewardship-draft-patch | ✅ | ✅ | 1 | 1 | — |
| stewardship-full-workflow | ✅ | ✅ | 3 | 3 | — |
| stewardship-draft-should-not-apply | ✅ | ✅ | 1 | 1 | — |
| prompt-ownership-stewardship-workflow | ✅ | ✅ | 2 | 2 | — |
| prompt-ownership-review | ✅ | ✅ | 1 | 1 | — |
| prompt-governance-gap | ✅ | ✅ | 1 | 1 | — |
| prompt-assign-owners-workflow | ✅ | ✅ | 2 | 2 | — |
| prompt-ownership-stewardship-also-selects-tools | ✅ | ✅ | 1 | 1 | — |
| prompt-search-assistant | ✅ | ✅ | 1 | 1 | — |
| governance-coverage-basic | ✅ | ✅ | 1 | 1 | — |
| governance-coverage-compliance | ✅ | ✅ | 1 | 1 | — |
| governance-coverage-pii | ✅ | ✅ | 1 | 1 | — |
| governance-coverage-gap-report | ✅ | ✅ | 1 | 1 | — |
| governance-coverage-then-stewardship | ✅ | ✅ | 2 | 2 | — |
| validate-patch-preview | ✅ | ✅ | 1 | 1 | — |
| validate-patch-dry-run | ✅ | ✅ | 1 | 1 | — |
| validate-patch-then-apply | ✅ | ✅ | 2 | 2 | — |
| validate-patch-risky-change | ✅ | ✅ | 1 | 1 | — |
| data-contract-export | ✅ | ✅ | 1 | 1 | — |
| data-contract-export-and-reapply | ✅ | ✅ | 2 | 2 | — |
| data-contract-dry-run-apply | ✅ | ✅ | 1 | 1 | — |
| data-contract-apply-with-creation | ✅ | ✅ | 1 | 1 | — |
| lineage-from-sql-plan | ✅ | ✅ | 1 | 1 | — |
| lineage-from-sql-apply | ✅ | ✅ | 1 | 1 | — |
| lineage-from-sql-preview-only | ✅ | ✅ | 1 | 1 | — |
| lineage-from-sql-cte | ✅ | ✅ | 1 | 1 | — |
| cost-ranking-wasting-money | ✅ | ✅ | 1 | 1 | — |
| cost-ranking-by-domain | ✅ | ✅ | 1 | 1 | — |
| cost-ranking-stale-tables | ✅ | ✅ | 1 | 1 | — |
| cost-ranking-then-stewardship | ✅ | ✅ | 2 | 2 | — |
| suggest-tests-for-table | ✅ | ✅ | 1 | 1 | — |
| suggest-tests-new-table | ✅ | ✅ | 1 | 1 | — |
| suggest-tests-then-create | ✅ | ✅ | 2 | 2 | — |

## Tool Call Details

### Search / Semantic
- search-keyword-lookup: search_metadata (1)
- search-exact-name: search_metadata (1)
- semantic-exploratory: semantic_search (1)
- semantic-concept-search: semantic_search (1)
- search-vs-semantic-chooses-keyword: search_metadata (1)

### Entity Chaining
- entity-chain-search-then-details: search_metadata, get_entity_details (2)
- entity-direct-lookup: get_entity_details (1)

### Lineage Directionality
- lineage-both-directions: get_entity_lineage (1)
- lineage-upstream-only: get_entity_lineage (1)
- lineage-downstream-only: get_entity_lineage (1)

### Root Cause Analysis
- rca-basic: root_cause_analysis (1)
- rca-narrative-quality: root_cause_analysis (1)

### Change Impact (E2)
- impact-drop-column: change_impact (1)
- impact-change-type: change_impact (1)
- impact-deprecate-entity: change_impact (1)

### Incident Timeline (E3)
- incident-basic: incident_timeline (1)
- incident-with-lookback: incident_timeline (1)

### Safety
- patch-with-preview: patch_entity (1)
- no-mutation-for-read: get_entity_details (1)

### Glossary / Test / Metric
- glossary-create: create_glossary (1)
- glossary-term-create: create_glossary_term (1)
- test-definitions-lookup: get_test_definitions (1)
- create-test-case: create_test_case (1)
- create-metric: create_metric (1)

### Multi-step Workflows
- full-rca-then-incident: root_cause_analysis, incident_timeline (2)
- search-lineage-chain: search_metadata, get_entity_lineage (2)

### Stewardship Copilot (E5)
- stewardship-find-unowned: find_unowned_assets (1)
- stewardship-find-unowned-in-domain: find_unowned_assets (1)
- stewardship-suggest-owner: suggest_owner_for (1)
- stewardship-draft-patch: draft_ownership_patch (1)
- stewardship-full-workflow: find_unowned_assets, suggest_owner_for, draft_ownership_patch (3)
- stewardship-draft-should-not-apply: draft_ownership_patch (1)

### Prompt Selection (MCP Prompts)
- prompt-ownership-stewardship-workflow: ownership_stewardship prompt + find_unowned_assets, draft_ownership_patch (2)
- prompt-ownership-review: ownership_stewardship prompt + find_unowned_assets (1)
- prompt-governance-gap: ownership_stewardship prompt + draft_ownership_patch (1)
- prompt-assign-owners-workflow: ownership_stewardship prompt + find_unowned_assets, draft_ownership_patch (2)
- prompt-ownership-stewardship-also-selects-tools: ownership_stewardship prompt + find_unowned_assets (1)
- prompt-search-assistant: search_metadata prompt + search_metadata (1)

### Governance Coverage Scanner (E6)
- governance-coverage-basic: scan_governance_coverage (1)
- governance-coverage-compliance: scan_governance_coverage (1)
- governance-coverage-pii: scan_governance_coverage (1)
- governance-coverage-gap-report: scan_governance_coverage (1)
- governance-coverage-then-stewardship: scan_governance_coverage, find_unowned_assets (2)

### Validate Patch (E9)
- validate-patch-preview: validate_patch (1)
- validate-patch-dry-run: validate_patch (1)
- validate-patch-then-apply: validate_patch, patch_entity (2)
- validate-patch-risky-change: validate_patch (1)

### Data Contract Round-trip (E7)
- data-contract-export: generate_data_contract (1)
- data-contract-export-and-reapply: generate_data_contract, apply_data_contract (2)
- data-contract-dry-run-apply: apply_data_contract (1)
- data-contract-apply-with-creation: apply_data_contract (1)

### SQL → Lineage (E8)
- lineage-from-sql-plan: lineage_from_sql (1)
- lineage-from-sql-apply: lineage_from_sql (1)
- lineage-from-sql-preview-only: lineage_from_sql (1)
- lineage-from-sql-cte: lineage_from_sql (1)

### Cost × Freshness Ranking (E10)
- cost-ranking-wasting-money: rank_assets_by_cost (1)
- cost-ranking-by-domain: rank_assets_by_cost (1)
- cost-ranking-stale-tables: rank_assets_by_cost (1)
- cost-ranking-then-stewardship: rank_assets_by_cost, find_unowned_assets (2)

### Agentic Test Author (E11)
- suggest-tests-for-table: suggest_test_cases (1)
- suggest-tests-new-table: suggest_test_cases (1)
- suggest-tests-then-create: suggest_test_cases, create_test_case (2)

## Notes

- All 62 fixtures pass with the deterministic client — 100% pass rate across all tool groups (F1–F5, E1–E11).
- With a live LLM backend, pass rates are expected to be lower due to model-specific tool selection accuracy. The deterministic client provides an upper-bound estimate.
- Answer correctness uses best-effort substring matching for the deterministic client. A live LLM would produce richer answers that more reliably contain gold substrings.
