package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Validation tests for EnvelopeBuilder (E1.16) and search envelope regression (E1.17). */
class EnvelopeBuilderTest {

  // --- EnvelopeBuilder unit tests (E1.16) ---

  @Test
  void build_minimal_resultsOnly() {
    Map<String, Object> envelope = EnvelopeBuilder.create().results(List.of("a", "b")).build();

    assertEquals(List.of("a", "b"), envelope.get("results"));
    assertNull(envelope.get("pagination")); // no pagination set
    assertNull(envelope.get("warnings")); // no warnings
    assertNull(envelope.get("narrative")); // no narrative
  }

  @Test
  void build_noResults_defaultsToEmptyList() {
    Map<String, Object> envelope = EnvelopeBuilder.create().build();

    assertEquals(List.of(), envelope.get("results"));
  }

  @Test
  void build_withPagination_includesNextFrom() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create().results(Collections.emptyList()).pagination(0, 10, 100).build();

    Map<String, Object> pagination = (Map<String, Object>) envelope.get("pagination");
    assertNotNull(pagination);
    assertEquals(0, pagination.get("from"));
    assertEquals(10, pagination.get("size"));
    assertEquals(100, pagination.get("total"));
    assertEquals(10, pagination.get("nextFrom")); // 0 + 10 = 10 < 100
  }

  @Test
  void build_withPagination_noNextFromWhenAllFetched() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create().results(Collections.emptyList()).pagination(90, 10, 100).build();

    Map<String, Object> pagination = (Map<String, Object>) envelope.get("pagination");
    assertNotNull(pagination);
    assertNull(pagination.get("nextFrom")); // 90 + 10 = 100, not < 100
  }

  @Test
  void build_withPagination_noNextFromWhenBeyondTotal() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create().results(Collections.emptyList()).pagination(0, 100, 50).build();

    Map<String, Object> pagination = (Map<String, Object>) envelope.get("pagination");
    assertNull(pagination.get("nextFrom")); // 0 + 100 >= 50
  }

  @Test
  void build_withWarnings() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(Collections.emptyList())
            .warning("something went wrong")
            .warning("another issue")
            .build();

    List<String> warnings = (List<String>) envelope.get("warnings");
    assertEquals(2, warnings.size());
    assertEquals("something went wrong", warnings.get(0));
    assertEquals("another issue", warnings.get(1));
  }

  @Test
  void build_withIgnoredFilterWarnings_compatibilityShim() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(Collections.emptyList())
            .warning("ignoredFilter: foo")
            .warning("ignoredFilter: bar")
            .warning("some other warning")
            .build();

    // Warnings list includes all
    List<String> warnings = (List<String>) envelope.get("warnings");
    assertEquals(3, warnings.size());

    // Compatibility shim: ignoredFilters array
    List<String> ignoredFilters = (List<String>) envelope.get("ignoredFilters");
    assertNotNull(ignoredFilters);
    assertEquals(2, ignoredFilters.size());
    assertEquals("foo", ignoredFilters.get(0));
    assertEquals("bar", ignoredFilters.get(1));
  }

  @Test
  void build_noIgnoredFilterWarnings_noIgnoredFiltersArray() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(Collections.emptyList())
            .warning("some other warning")
            .build();

    assertNull(envelope.get("ignoredFilters")); // no compatibility shim needed
  }

  @Test
  void build_withNarrative() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(Collections.emptyList())
            .narrative("Found 3 tables related to your query.")
            .build();

    assertEquals("Found 3 tables related to your query.", envelope.get("narrative"));
  }

  @Test
  void build_blankNarrative_omitted() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create().results(Collections.emptyList()).narrative("   ").build();

    assertNull(envelope.get("narrative"));
  }

  @Test
  void build_warningsListMethod() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(Collections.emptyList())
            .warnings(List.of("w1", "w2"))
            .build();

    List<String> warnings = (List<String>) envelope.get("warnings");
    assertEquals(List.of("w1", "w2"), warnings);
  }

  @Test
  void build_fluentChaining() {
    Map<String, Object> envelope =
        EnvelopeBuilder.create()
            .results(List.of(Map.of("name", "test")))
            .pagination(0, 10, 50)
            .warning("ignoredFilter: xyz")
            .narrative("Summary")
            .build();

    assertNotNull(envelope.get("results"));
    assertNotNull(envelope.get("pagination"));
    assertNotNull(envelope.get("warnings"));
    assertNotNull(envelope.get("ignoredFilters"));
    assertEquals("Summary", envelope.get("narrative"));
  }

  // --- Search envelope regression tests (E1.17) ---
  // (Superseded by @Nested BuildEnhancedSearchResponseTests below)

  // --- SearchMetadataTool.buildEnhancedSearchResponse() detailed tests ---

  @Nested
  class BuildEnhancedSearchResponseTests {

    /** Build a search response map simulating OpenSearch output. */
    private static Map<String, Object> buildSearchResponse(
        List<Map<String, Object>> hitSources, Object total) {
      List<Map<String, Object>> hits = new ArrayList<>();
      for (Map<String, Object> source : hitSources) {
        hits.add(Map.of("_source", source));
      }
      return Map.of("hits", Map.of("hits", hits, "total", total));
    }

    private static final Map<String, Object> BASIC_HIT_SOURCE =
        Map.of(
            "name", "test_table",
            "fullyQualifiedName", "svc.db.s.test_table",
            "entityType", "table");

    @Test
    void nullResponse_returnsEmptyResponse() {
      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(null, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("pagination");
      assertThat(result).containsEntry("totalFound", 0);
    }

    @Test
    void nullTopHits_returnsEmptyResponse() {
      Map<String, Object> searchResponse = Map.of(); // no "hits" key

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 0);
    }

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("pagination");
    }

    @Test
    void resultsContainsCleanedHits() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);

      @SuppressWarnings("unchecked")
      Map<String, Object> cleaned = (Map<String, Object>) results.get(0);
      // Essential fields are preserved
      assertThat(cleaned).containsKey("name");
      assertThat(cleaned).containsKey("fullyQualifiedName");
    }

    @Test
    void emptyHits_resultsIsEmptyList() {
      Map<String, Object> searchResponse =
          Map.of("hits", Map.of("hits", List.of(), "total", Map.of("value", 0)));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
    }

    @Test
    void paginationStructure() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 100));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("from", 0);
      assertThat(pagination).containsEntry("size", 10);
      assertThat(pagination).containsEntry("total", 100);
      assertThat(pagination).containsEntry("nextFrom", 10); // 0 + 10 < 100
    }

    @Test
    void paginationNoNextFromWhenAllFetched() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 10));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).doesNotContainKey("nextFrom");
    }

    @Test
    void paginationWithOffset() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 100));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 20, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("from", 20);
      assertThat(pagination).containsEntry("nextFrom", 30); // 20 + 10 < 100
    }

    @Test
    void hasMoreWhenMoreResultsExist() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 100));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("hasMore", true);
    }

    @Test
    void noHasMoreWhenAllFetched() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 5));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("hasMore");
    }

    @Test
    void backwardCompatFields() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 100));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "myQuery", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 100);
      assertThat(result).containsEntry("returnedCount", 1);
      assertThat(result).containsEntry("query", "myQuery");
      assertThat(result).containsKey("usage");
      assertThat((String) result.get("usage")).contains("get_entity_details");
    }

    @Test
    void totalAsPlainNumber() {
      Map<String, Object> searchResponse =
          Map.of("hits", Map.of("hits", List.of(Map.of("_source", BASIC_HIT_SOURCE)), "total", 50));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).containsEntry("totalFound", 50);

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("total", 50);
    }

    @Test
    void aggregationsIncludedWhenFlagSet() {
      Map<String, Object> aggBucket = Map.of("key", "table", "doc_count", 42);
      Map<String, Object> aggField = Map.of("entityType", Map.of("buckets", List.of(aggBucket)));
      Map<String, Object> searchResponse = new HashMap<>();
      searchResponse.put("hits", Map.of("hits", List.of(), "total", Map.of("value", 0)));
      searchResponse.put("aggregations", aggField);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), true, 10);

      assertThat(result).containsKey("aggregations");
    }

    @Test
    void aggregationsNotIncludedWhenFlagFalse() {
      Map<String, Object> aggBucket = Map.of("key", "table", "doc_count", 42);
      Map<String, Object> aggField = Map.of("entityType", Map.of("buckets", List.of(aggBucket)));
      Map<String, Object> searchResponse = new HashMap<>();
      searchResponse.put("hits", Map.of("hits", List.of(), "total", Map.of("value", 0)));
      searchResponse.put("aggregations", aggField);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("aggregations");
    }

    @Test
    void aggregationTruncationWhenBucketsExceedMax() {
      List<Map<String, Object>> buckets = new ArrayList<>();
      for (int i = 0; i < 15; i++) {
        buckets.add(Map.of("key", "bucket_" + i, "doc_count", i));
      }
      Map<String, Object> aggField = Map.of("entityType", Map.of("buckets", buckets));
      Map<String, Object> searchResponse = new HashMap<>();
      searchResponse.put("hits", Map.of("hits", List.of(), "total", Map.of("value", 0)));
      searchResponse.put("aggregations", aggField);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), true, 10);

      assertThat(result).containsEntry("aggregationsTruncated", true);
      assertThat(result).containsKey("aggregationsMessage");
    }

    @Test
    void noAggregationTruncationWhenBucketsWithinMax() {
      List<Map<String, Object>> buckets = List.of(Map.of("key", "table", "doc_count", 42));
      Map<String, Object> aggField = Map.of("entityType", Map.of("buckets", buckets));
      Map<String, Object> searchResponse = new HashMap<>();
      searchResponse.put("hits", Map.of("hits", List.of(), "total", Map.of("value", 0)));
      searchResponse.put("aggregations", aggField);

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), true, 10);

      assertThat(result).doesNotContainKey("aggregationsTruncated");
    }

    @Test
    void requestedFieldsAddedToResults() {
      Map<String, Object> hitSource = new HashMap<>();
      hitSource.put("name", "test_table");
      hitSource.put("fullyQualifiedName", "svc.db.s.test_table");
      hitSource.put("myCustomField", "custom_value");

      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(hitSource), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of("myCustomField"), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);

      @SuppressWarnings("unchecked")
      Map<String, Object> cleaned = (Map<String, Object>) results.get(0);
      assertThat(cleaned).containsEntry("myCustomField", "custom_value");
    }

    @Test
    void requestedFieldNotInSource_omitted() {
      Map<String, Object> hitSource = new HashMap<>();
      hitSource.put("name", "test_table");
      hitSource.put("fullyQualifiedName", "svc.db.s.test_table");

      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(hitSource), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of("nonExistentField"), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);

      @SuppressWarnings("unchecked")
      Map<String, Object> cleaned = (Map<String, Object>) results.get(0);
      assertThat(cleaned).doesNotContainKey("nonExistentField");
    }

    @Test
    void descriptionTruncatedWhenTooLong() {
      String longDesc = "x".repeat(600); // exceeds DESCRIPTION_MAX_LENGTH (500)
      Map<String, Object> hitSource = new HashMap<>();
      hitSource.put("name", "test_table");
      hitSource.put("fullyQualifiedName", "svc.db.s.test_table");
      hitSource.put("description", longDesc);

      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(hitSource), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      @SuppressWarnings("unchecked")
      Map<String, Object> cleaned = (Map<String, Object>) results.get(0);
      String desc = (String) cleaned.get("description");
      assertThat(desc).endsWith("...");
      assertThat(desc.length()).isLessThan(500);
    }

    @Test
    void noNarrative() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("narrative");
    }

    @Test
    void noWarnings() {
      Map<String, Object> searchResponse =
          buildSearchResponse(List.of(BASIC_HIT_SOURCE), Map.of("value", 1));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      assertThat(result).doesNotContainKey("warnings");
    }

    @Test
    void hitWithMissingSource_skipped() {
      Map<String, Object> hitWithoutSource = Map.of(); // no _source key
      Map<String, Object> searchResponse =
          Map.of("hits", Map.of("hits", List.of(hitWithoutSource), "total", Map.of("value", 1)));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
      assertThat(result).containsEntry("returnedCount", 0);
    }

    @Test
    void hitWithNullSource_skipped() {
      Map<String, Object> hitWithNullSource = new HashMap<>();
      hitWithNullSource.put("_source", null);
      Map<String, Object> searchResponse = new HashMap<>();
      searchResponse.put(
          "hits", Map.of("hits", List.of(hitWithNullSource), "total", Map.of("value", 1)));

      Map<String, Object> result =
          SearchMetadataTool.buildEnhancedSearchResponse(
              searchResponse, "test", 0, 10, List.of(), false, 10);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
      assertThat(result).containsEntry("returnedCount", 0);
    }
  }

  // --- SearchMetadataTool.createEmptyResponse() detailed tests ---

  @Nested
  class CreateEmptyResponseTests {

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("pagination");
    }

    @Test
    void resultsIsEmptyList() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void paginationIsZeroed() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      @SuppressWarnings("unchecked")
      Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
      assertThat(pagination).containsEntry("from", 0);
      assertThat(pagination).containsEntry("size", 0);
      assertThat(pagination).containsEntry("total", 0);
      assertThat(pagination).doesNotContainKey("nextFrom");
    }

    @Test
    void backwardCompatFields() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).containsEntry("totalFound", 0);
      assertThat(result).containsEntry("returnedCount", 0);
      assertThat(result).containsKey("message");
      assertThat((String) result.get("message")).contains("No results");
    }

    @Test
    void noNarrative() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).doesNotContainKey("narrative");
    }

    @Test
    void noHasMore() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).doesNotContainKey("hasMore");
    }

    @Test
    void noAggregations() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).doesNotContainKey("aggregations");
    }

    @Test
    void noWarnings() {
      Map<String, Object> result = SearchMetadataTool.createEmptyResponse();

      assertThat(result).doesNotContainKey("warnings");
    }
  }
}
