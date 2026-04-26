Part 1: OpenMetadata Issues Matching Our Work
🟢 Direct hits — our work directly addresses these issues:
┌───────┬────────────────────────────────────┬──────────────────┬─────────────────────────────────────────────────────────────────────────────────┐
│ Issue │ Title                              │ State            │ Our Work                                                                        │
├───────┼────────────────────────────────────┼──────────────────┼─────────────────────────────────────────────────────────────────────────────────┤
│ #2660 │ New MCP Tools                      │ Open (epic,      │ We built 12 of the requested new MCP tools — governance, stewardship, cost      │
│ 9     │                                    │ hackathon        │ intelligence, test authoring, contracts, SQL lineage                            │
│       │                                    │ label!)          │                                                                                 │
│ #2664 │ GitHub Action: "What Breaks If I   │ Open             │ Our change_impact tool literally answers this exact question — downstream blast │
│ 8     │ Change This?"                      │                  │ radius with severity rubric                                                     │
│ #2750 │ AI-Powered Metadata Lineage        │ Open             │ Our lineage_from_sql + change_impact + incident_timeline tools deliver          │
│ 1     │ Visualization                      │                  │ lineage-powered AI insights                                                     │
│ #2744 │ Include glossary term synonyms in  │ Open             │ Our semantic_search enhancements (filter transparency, pagination) are adjacent │
│ 1     │ search and semantic retrieval      │                  │ — but we didn't add synonym indexing (upstream embedding change)                │
│ #2700 │ README Missing Key Info — MCP      │ Open             │ We just restructured the MCP README with the full tool catalog, bench table,    │
│ 9     │ Integration                        │                  │ and design trade-offs                                                           │
│ #2532 │ Update MCP documentation to        │ Open             │ Our README restructuring covers this                                            │
│ 9     │ include latest features            │                  │                                                                                 │
└───────┴────────────────────────────────────┴──────────────────┴─────────────────────────────────────────────────────────────────────────────────┘
🟡 Adjacent — related but we don't fully address:
┌────────┬─────────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────┐
│ Issue  │ Title                                   │ Our Relationship                                                                       │
├────────┼─────────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
│ #21535 │ Support for Documenting MCP Services    │ We enable managing metadata via MCP; this is about documenting MCP services themselves │
│ #27678 │ Implement MCP Client in OpenMetadata UI │ UI-side; we're backend tools. Our composite tools would feed this if built             │
│ #26608 │ Conversational Data Catalog Chat App    │ Our tools power the backend of a chat app; this is the UI wrapper                      │
└────────┴─────────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────┘
🔴 Critical — we need to check:
┌──────┬───────────────────────────────────┬─────┬────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Issu │ Title                             │ Sta │ Why it matters                                                                                 │
│ e    │                                   │ te  │                                                                                                │
├──────┼───────────────────────────────────┼─────┼────────────────────────────────────────────────────────────────────────────────────────────────┤
│ #268 │ Security audit: MCP tool          │ Clo │ This was found and presumably fixed upstream. Our new tools need the same output sanitization  │
│ 11   │ description injection + missing   │ sed │ that was applied to the original 12 tools. We should verify our tools aren't vulnerable to the │
│      │ output sanitization               │     │ same injection patterns.                                                                       │
└──────┴───────────────────────────────────┴─────┴────────────────────────────────────────────────────────────────────────────────────────────────┘
The biggest signal: Issue #26609 has the  hackathon  label. The OpenMetadata org literally created an epic asking for exactly what we built. Cite this issue number in the PR.
────────────────────────────────────────────────────────────────────────────────
Part 2: 43 Upstream Commits — Impact Assessment
Good news: Zero merge conflicts
I ran a test merge ( git merge --no-commit --no-ff upstream/main ) and it auto-resolved cleanly. No manual conflict resolution needed.
Only 2 upstream commits touch our module
┌───────┬─────────────────────────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────┐
│ Commi │ What it does                                                            │ Impact on us                                                  │
│ t     │                                                                         │                                                               │
├───────┼─────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────┤
│ 10e43 │ SearchUtils refactor: merges SearchUtil into SearchUtils, removes fuzzy │ Low — import rename only. Our F4/F5 size clamping and         │
│ a47a7 │ ngram match, touches SearchMetadataTool.java (import change: SearchUtil │ queryFilter validation are in the tool's execute() method,    │
│       │ → SearchUtils)                                                          │ not the utility class.                                        │
│ 4cf11 │ Adds textToLLMContext field for embeddings, touches                     │ Low — adds a field to VectorDocBuilder; doesn't change our    │
│ 6f849 │ SemanticSearchToolTest.java in openmetadata-service test tree           │ semantic_search tool logic or our filter transparency (F6).   │
└───────┴─────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────┘
Other upstream changes that matter
┌───────────────────┬────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────┐
│ Commit            │ Area                           │ Risk                                                                                       │
├───────────────────┼────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────┤
│ 5d9dbfa2d1        │ dryRun support for bulk domain │ None — different module                                                                    │
│                   │ asset add/remove               │                                                                                            │
│ e4d3e423e1        │ Version API improvements       │ Interesting — our incident_timeline tool uses change events; this might enrich the data we │
│                   │ (timeline, metadata change     │ already consume                                                                            │
│                   │ tracking)                      │                                                                                            │
│ 9951082e13        │ Trino cross-database lineage   │ None — connector-level, not MCP                                                            │
│                   │ for case-insensitive tables    │                                                                                            │
│ Entity.java (77   │ Core entity class              │ Watch — our F8 fix added null resets to Entity.clear(). Upstream removed ~60 lines from    │
│ changes, +17/-60) │                                │ Entity.java. The auto-merge succeeded, but we should verify our F8 additions survived the  │
│                   │                                │ merge.                                                                                     │
└───────────────────┴────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────┘
What's NOT in upstream
No upstream commit adds new MCP tools. No upstream commit touches  tools.json  registrations. No upstream commit adds bench infrastructure. Our work is entirely novel relative to upstream.
────────────────────────────────────────────────────────────────────────────────
Summary: What This Means for the Next 2 Days
1. Cite #26609 in the PR. The upstream org literally asked for what we built. That's the single strongest "why this matters" signal.
2. Cite #26648. "What Breaks If I Change This?" is our  change_impact  tool. Someone already asked for it by name.
3. Verify output sanitization (from #26811). The upstream security audit found injection vulnerabilities in MCP tool descriptions. Our 12 new tools need the same sanitization that was applied to the original tools. This should be a quick check — do our tools sanitize user-supplied strings before returning them?
4. Rebase onto upstream before submitting. The merge auto-resolves, but we should rebase to make the PR diff clean against current main.
5. The  textToLLMContext  field is interesting — it means upstream is actively improving embedding quality. Our  semantic_search  tool could benefit from mentioning this field exists in the vector index, though it doesn't change our implementation.
6. Entity.java changes — verify F8 survived the rebase. Our  Entity.clear()  additions need to still be present after the upstream removals.

3-Minute Screencast Storyboard
┌────────┬─────────────────────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────┐
│ Time   │ What's on screen                                        │ Narration / talking point                                                    │
├────────┼─────────────────────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────┤
│ 0:00–0 │ Title card: "24 MCP Tools for OpenMetadata" + At a      │ "We turned OpenMetadata's MCP server from 12 broken tools into 24            │
│ :15    │ Glance table (bench 19%→100%)                           │ production-grade tools — and we can prove it."                               │
│ 0:15–0 │ search_metadata in Claude Desktop → find orders table   │ "Start where any data user starts: search for the table you care about."     │
│ :45    │                                                         │                                                                              │
│ 0:45–1 │ change_impact on orders table → show blast radius       │ "Now ask: if I change this table, what breaks? 47 downstream dashboards and  │
│ :30    │ narrative + downstream count                            │ pipelines — ranked by severity."                                             │
│ 1:30–2 │ incident_timeline on a failing pipeline → chronological │ "Or: what happened to this pipeline? A chronological timeline — upstream     │
│ :15    │ narrative with RCA events + test failures               │ change at 2am, test failure at 3am, downstream impact by 4am."               │
│ 2:15–2 │ Flash the bench table (19%→100%, 62 fixtures, 908       │ "Every tool is benchmarked. Before our fixes, tool-selection accuracy was    │
│ :45    │ tests)                                                  │ 19%. After: 100%. 908 tests, zero failures."                                 │
│ 2:45–3 │ Show 3 open PRs on open-metadata/OpenMetadata (or the   │ "And it's all upstream-mergeable. Three PRs already open. This isn't a       │
│ :00    │ PR link)                                                │ hackathon fork — it's a contribution."                                       │
└────────┴─────────────────────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────┘
Key rule: Scripted demo, not live. Live LLM demos fail at the worst moment. Record with a tool you control.
────────────────────────────────────────────────────────────────────────────────
What's Done vs. What's Left
┌───────────────────────────────────────────────────────────────────────┬────────────────────────────────────────┐
│ Action                                                                │ Status                                 │
├───────────────────────────────────────────────────────────────────────┼────────────────────────────────────────┤
│ README restructured (bench table above fold, trade-offs, PR sequence) │ ✅ Committed (1eaac72c0c)              │
│ MCP_CHANGELOG.md content absorbed into README                         │ ✅ Done                                │
│ 908/908 tests verified green                                          │ ✅ Done                                │
│ Rate limit implementation committed                                   │ ✅ Done (9fa27ddccc)                   │
│ Screencast storyboard                                                 │ ✅ Written above                       │
│ Open upstream draft PRs                                               │ ❌ Not done — needs your push decision │
│ Record screencast                                                     │ ❌ Not done — needs 
your recording   
☐  Final test run to confirm 908/908 green before submission