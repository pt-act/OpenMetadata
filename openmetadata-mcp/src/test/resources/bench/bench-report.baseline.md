# MCP Bench Report — baseline

Generated: 2026-04-22T00:00:00Z

> This is a hypothetical baseline representing the state **before** the Fixes spec (F1–F8)
> and Expansions spec (E1–E11) were implemented. Values are estimated based on the known
> gaps that existed: broken FQN chaining, no zero-depth lineage, no RCA symmetry,
> no semantic search pagination, no composite tools, no filter transparency,
> no stewardship/governance/contract/intelligence tools.

## Summary

| Metric | Value |
|--------|-------|
| Total fixtures | 62 |
| Pass count | 12 |
| Pass rate | 19.4% |
| Correct tool rate | 21.0% |
| Answer correct rate | 19.4% |
| Avg tool calls | 3.4 |
| Avg latency (ms) | 45.0 |
| P95 latency (ms) | 78 |

## Key Gaps (Pre-Fixes)

- **FQN chaining broken:** `fullyQualifiedName` not accepted by detail/lineage/patch tools
- **No zero-depth lineage:** `upstreamDepth=0` clamped to 1
- **No RCA symmetry:** Downstream nodes verbose, no test case cap
- **No `from` in semantic_search:** Pagination undiscoverable
- **No composite tools:** No `change_impact`, `incident_timeline`
- **No filter transparency:** Unknown filters silently dropped
- **No structured logging:** Tool failures invisible in production
- **No stewardship tools:** No `find_unowned_assets`, `suggest_owner_for`, `draft_ownership_patch`
- **No governance scanner:** No `scan_governance_coverage`
- **No validate_patch:** No dry-run preview
- **No data contracts:** No `generate_data_contract`, `apply_data_contract`
- **No SQL lineage:** No `lineage_from_sql`
- **No cost ranking:** No `rank_assets_by_cost`
- **No test authoring:** No `suggest_test_cases`
- **No prompt selection:** No `ownership_stewardship` prompt, no `search_metadata` prompt

## Per-Fixture Results

| Fixture | Pass | Correct Tool | Tool Calls | Failures |
|---------|------|-------------|-----------|----------|
| search-keyword-lookup | ✅ | ✅ | 1 | — |
| search-exact-name | ✅ | ✅ | 1 | — |
| semantic-exploratory | ❌ | ❌ | 1 | Missing expected tool: semantic_search |
| semantic-concept-search | ❌ | ❌ | 1 | Missing expected tool: semantic_search |
| search-vs-semantic-chooses-keyword | ✅ | ✅ | 1 | — |
| entity-chain-search-then-details | ❌ | ❌ | 2 | Missing expected tool: get_entity_details (fqn mismatch) |
| entity-direct-lookup | ❌ | ❌ | 1 | Missing expected tool: get_entity_details (fqn mismatch) |
| lineage-both-directions | ✅ | ✅ | 1 | — |
| lineage-upstream-only | ❌ | ❌ | 1 | Upstream not disabled (depth clamped to 1) |
| lineage-downstream-only | ❌ | ❌ | 1 | Downstream not disabled (depth clamped to 1) |
| rca-basic | ✅ | ✅ | 1 | — |
| rca-narrative-quality | ❌ | ✅ | 1 | Answer missing gold substring (downstream bloat) |
| impact-drop-column | ❌ | ❌ | 5 | Missing expected tool: change_impact; tool call count exceeds max |
| impact-change-type | ❌ | ❌ | 5 | Missing expected tool: change_impact; tool call count exceeds max |
| impact-deprecate-entity | ❌ | ❌ | 5 | Missing expected tool: change_impact; tool call count exceeds max |
| incident-basic | ❌ | ❌ | 4 | Missing expected tool: incident_timeline; tool call count exceeds max |
| incident-with-lookback | ❌ | ❌ | 4 | Missing expected tool: incident_timeline; tool call count exceeds max |
| patch-with-preview | ✅ | ✅ | 1 | — |
| no-mutation-for-read | ✅ | ✅ | 1 | — |
| glossary-create | ✅ | ✅ | 1 | — |
| glossary-term-create | ✅ | ✅ | 1 | — |
| test-definitions-lookup | ✅ | ✅ | 1 | — |
| create-test-case | ✅ | ✅ | 1 | — |
| create-metric | ✅ | ✅ | 1 | — |
| full-rca-then-incident | ❌ | ❌ | 4 | Missing expected tool: incident_timeline; tool call count exceeds max |
| search-lineage-chain | ❌ | ❌ | 2 | FQN mismatch on get_entity_lineage |
| stewardship-find-unowned | ❌ | ❌ | 1 | Missing expected tool: find_unowned_assets |
| stewardship-find-unowned-in-domain | ❌ | ❌ | 1 | Missing expected tool: find_unowned_assets |
| stewardship-suggest-owner | ❌ | ❌ | 1 | Missing expected tool: suggest_owner_for |
| stewardship-draft-patch | ❌ | ❌ | 1 | Missing expected tool: draft_ownership_patch |
| stewardship-full-workflow | ❌ | ❌ | 3 | Missing expected tools: find_unowned_assets, suggest_owner_for, draft_ownership_patch |
| stewardship-draft-should-not-apply | ❌ | ❌ | 1 | Missing expected tool: draft_ownership_patch |
| prompt-ownership-stewardship-workflow | ❌ | ❌ | 2 | Missing expected prompt: ownership_stewardship; missing tools |
| prompt-ownership-review | ❌ | ❌ | 1 | Missing expected prompt: ownership_stewardship; missing tools |
| prompt-governance-gap | ❌ | ❌ | 1 | Missing expected prompt: ownership_stewardship; missing tools |
| prompt-assign-owners-workflow | ❌ | ❌ | 2 | Missing expected prompt: ownership_stewardship; missing tools |
| prompt-ownership-stewardship-also-selects-tools | ❌ | ❌ | 1 | Missing expected prompt: ownership_stewardship; missing tools |
| prompt-search-assistant | ❌ | ❌ | 1 | Missing expected prompt: search_metadata |
| governance-coverage-basic | ❌ | ❌ | 1 | Missing expected tool: scan_governance_coverage |
| governance-coverage-compliance | ❌ | ❌ | 1 | Missing expected tool: scan_governance_coverage |
| governance-coverage-pii | ❌ | ❌ | 1 | Missing expected tool: scan_governance_coverage |
| governance-coverage-gap-report | ❌ | ❌ | 1 | Missing expected tool: scan_governance_coverage |
| governance-coverage-then-stewardship | ❌ | ❌ | 2 | Missing expected tools: scan_governance_coverage, find_unowned_assets |
| validate-patch-preview | ❌ | ❌ | 1 | Missing expected tool: validate_patch |
| validate-patch-dry-run | ❌ | ❌ | 1 | Missing expected tool: validate_patch |
| validate-patch-then-apply | ❌ | ❌ | 2 | Missing expected tool: validate_patch |
| validate-patch-risky-change | ❌ | ❌ | 1 | Missing expected tool: validate_patch |
| data-contract-export | ❌ | ❌ | 1 | Missing expected tool: generate_data_contract |
| data-contract-export-and-reapply | ❌ | ❌ | 2 | Missing expected tools: generate_data_contract, apply_data_contract |
| data-contract-dry-run-apply | ❌ | ❌ | 1 | Missing expected tool: apply_data_contract |
| data-contract-apply-with-creation | ❌ | ❌ | 1 | Missing expected tool: apply_data_contract |
| lineage-from-sql-plan | ❌ | ❌ | 1 | Missing expected tool: lineage_from_sql |
| lineage-from-sql-apply | ❌ | ❌ | 1 | Missing expected tool: lineage_from_sql |
| lineage-from-sql-preview-only | ❌ | ❌ | 1 | Missing expected tool: lineage_from_sql |
| lineage-from-sql-cte | ❌ | ❌ | 1 | Missing expected tool: lineage_from_sql |
| cost-ranking-wasting-money | ❌ | ❌ | 1 | Missing expected tool: rank_assets_by_cost |
| cost-ranking-by-domain | ❌ | ❌ | 1 | Missing expected tool: rank_assets_by_cost |
| cost-ranking-stale-tables | ❌ | ❌ | 1 | Missing expected tool: rank_assets_by_cost |
| cost-ranking-then-stewardship | ❌ | ❌ | 2 | Missing expected tools: rank_assets_by_cost, find_unowned_assets |
| suggest-tests-for-table | ❌ | ❌ | 1 | Missing expected tool: suggest_test_cases |
| suggest-tests-new-table | ❌ | ❌ | 1 | Missing expected tool: suggest_test_cases |
| suggest-tests-then-create | ❌ | ❌ | 2 | Missing expected tools: suggest_test_cases, create_test_case |
