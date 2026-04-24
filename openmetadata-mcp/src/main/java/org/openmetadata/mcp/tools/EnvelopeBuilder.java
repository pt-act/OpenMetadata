package org.openmetadata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for consistent MCP tool response envelopes.
 *
 * <p>Every tool that returns lists MUST use this builder so that MCP clients receive a
 * predictable response shape with pagination, warnings, and optional narrative.
 *
 * <p>Envelope shape:
 *
 * <pre>{@code
 * {
 *   "results": [...],
 *   "pagination": { "from": 0, "size": 25, "total": 317, "nextFrom": 25 },
 *   "warnings": ["ignoredFilter: foo"],
 *   "narrative": "Optional Markdown summary."
 * }
 * }</pre>
 *
 * <p>When {@code from + size >= total}, {@code nextFrom} is omitted (no more pages).
 *
 * <p>Compatibility shim: when warnings contain entries matching {@code ignoredFilter:*}, they
 * are also copied to a top-level {@code ignoredFilters} array for one release cycle (per roadmap
 * Conflict Resolution §2). Remove the shim after the next release.
 */
public final class EnvelopeBuilder {

  private List<?> results;
  private Integer from;
  private Integer size;
  private Integer total;
  private final List<String> warnings = new ArrayList<>();
  private String narrative;

  private EnvelopeBuilder() {}

  public static EnvelopeBuilder create() {
    return new EnvelopeBuilder();
  }

  public EnvelopeBuilder results(List<?> results) {
    this.results = results;
    return this;
  }

  public EnvelopeBuilder pagination(int from, int size, int total) {
    this.from = from;
    this.size = size;
    this.total = total;
    return this;
  }

  public EnvelopeBuilder warning(String warning) {
    this.warnings.add(warning);
    return this;
  }

  public EnvelopeBuilder warnings(List<String> warnings) {
    this.warnings.addAll(warnings);
    return this;
  }

  public EnvelopeBuilder narrative(String narrative) {
    this.narrative = narrative;
    return this;
  }

  /**
   * Builds the envelope as a {@code Map<String, Object>}.
   *
   * <p>If pagination params are set, includes the {@code pagination} block. If warnings are
   * present, includes them. The compatibility shim copies {@code ignoredFilter:*} warnings to
   * a top-level {@code ignoredFilters} array.
   */
  public Map<String, Object> build() {
    Map<String, Object> envelope = new LinkedHashMap<>();

    envelope.put("results", results != null ? results : List.of());

    if (from != null && size != null && total != null) {
      Map<String, Object> pagination = new LinkedHashMap<>();
      pagination.put("from", from);
      pagination.put("size", size);
      pagination.put("total", total);
      // nextFrom is null when there are no more pages
      int nextFrom = from + size;
      if (nextFrom < total) {
        pagination.put("nextFrom", nextFrom);
      }
      envelope.put("pagination", pagination);
    }

    if (!warnings.isEmpty()) {
      envelope.put("warnings", List.copyOf(warnings));

      // Compatibility shim: copy ignoredFilter:* entries to top-level ignoredFilters array
      // Remove this shim after one release cycle (see roadmap Conflict Resolution §2)
      List<String> ignoredFilters =
          warnings.stream()
              .filter(w -> w.startsWith("ignoredFilter:"))
              .map(w -> w.substring("ignoredFilter:".length()).trim())
              .toList();
      if (!ignoredFilters.isEmpty()) {
        envelope.put("ignoredFilters", ignoredFilters);
      }
    }

    if (narrative != null && !narrative.isBlank()) {
      envelope.put("narrative", narrative);
    }

    return envelope;
  }
}
