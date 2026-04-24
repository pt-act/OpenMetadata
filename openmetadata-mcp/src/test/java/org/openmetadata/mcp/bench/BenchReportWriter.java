package org.openmetadata.mcp.bench;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Writes benchmark results to Markdown report files. */
public class BenchReportWriter {

  /**
   * Generate a Markdown report from the given results and stats.
   *
   * @param results per-fixture results
   * @param stats aggregate statistics
   * @param label label for the run (e.g. "current" or "baseline")
   * @return Markdown string
   */
  public static String generateReport(
      List<BenchResult> results, BenchRunner.AggregateStats stats, String label) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);

    pw.printf("# MCP Bench Report — %s%n%n", label);
    pw.printf("Generated: %s%n%n", Instant.now());

    // Summary table
    pw.println("## Summary");
    pw.println();
    pw.println("| Metric | Value |");
    pw.println("|--------|-------|");
    pw.printf("| Total fixtures | %d |%n", stats.totalFixtures());
    pw.printf("| Pass count | %d |%n", stats.passCount());
    pw.printf("| Pass rate | %.1f%% |%n", stats.passRate() * 100);
    pw.printf("| Correct tool rate | %.1f%% |%n", stats.correctToolRate() * 100);
    pw.printf("| Answer correct rate | %.1f%% |%n", stats.answerCorrectRate() * 100);
    pw.printf("| Avg tool calls | %.1f |%n", stats.avgToolCalls());
    pw.printf("| Avg latency (ms) | %.1f |%n", stats.avgLatencyMs());
    pw.printf("| P95 latency (ms) | %d |%n", stats.p95LatencyMs());
    pw.println();

    // Per-fixture results
    pw.println("## Per-Fixture Results");
    pw.println();
    pw.println("| Fixture | Pass | Correct Tool | Tool Calls | Latency (ms) | Failures |");
    pw.println("|---------|------|-------------|-----------|-------------|----------|");
    for (BenchResult r : results) {
      String failures = r.getFailures().isEmpty() ? "—" : String.join("; ", r.getFailures());
      pw.printf(
          "| %s | %s | %s | %d | %d | %s |%n",
          r.getFixtureId(),
          r.isPass() ? "✅" : "❌",
          r.isCorrectTool() ? "✅" : "❌",
          r.getToolCallCount(),
          r.getLatencyMs(),
          failures);
    }
    pw.println();

    // Tool call details
    pw.println("## Tool Call Details");
    pw.println();
    for (BenchResult r : results) {
      pw.printf("### %s%n", r.getFixtureId());
      pw.printf("- Tools selected: %s%n", String.join(", ", r.getToolCalls()));
      pw.printf("- Tool call count: %d%n", r.getToolCallCount());
      if (!r.getPromptCalls().isEmpty()) {
        pw.printf("- Prompts selected: %s%n", String.join(", ", r.getPromptCalls()));
      }
      if (!r.getFailures().isEmpty()) {
        pw.printf("- Failures:%n");
        for (String f : r.getFailures()) {
          pw.printf("  - %s%n", f);
        }
      }
      pw.println();
    }

    pw.flush();
    return sw.toString();
  }

  /** Write report to a file. */
  public static void writeToFile(String report, Path path) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, report);
  }
}
