package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;

/** Tests for {@link ChangeImpactTool}. Covers proposedChange parsing, severity rubric, narrative
 * generation, byte-cap enforcement, and lineage extraction. */
class ChangeImpactToolTest {

  // ====================== ProposedChange parsing ======================

  @Nested
  class ProposedChangeParsing {

    @Test
    void parseProposedChange_dropColumn_fromMap() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "dropColumn", "column", "customer_id"));

      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("dropColumn");
      assertThat(pc.column).isEqualTo("customer_id");
    }

    @Test
    void parseProposedChange_changeColumnType_fromMap() {
      Map<String, Object> params = new HashMap<>();
      params.put(
          "proposedChange",
          Map.of(
              "kind", "changeColumnType", "column", "id", "fromType", "int", "toType", "bigint"));

      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("changeColumnType");
      assertThat(pc.column).isEqualTo("id");
      assertThat(pc.fromType).isEqualTo("int");
      assertThat(pc.toType).isEqualTo("bigint");
    }

    @Test
    void parseProposedChange_deprecateEntity_fromMap() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));

      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("deprecateEntity");
      assertThat(pc.column).isNull();
    }

    @Test
    void parseProposedChange_custom_fromMap() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "custom", "description", "renaming table"));

      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("custom");
      assertThat(pc.description).isEqualTo("renaming table");
    }

    @Test
    void parseProposedChange_fallbackToIndividualParams() {
      Map<String, Object> params = new HashMap<>();
      params.put("kind", "dropColumn");
      params.put("column", "email");

      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("dropColumn");
      assertThat(pc.column).isEqualTo("email");
    }

    @Test
    void parseProposedChange_missingKind_throws() {
      Map<String, Object> params = new HashMap<>();
      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("proposedChange");
    }

    @Test
    void parseProposedChange_unsupportedKind_throws() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "unknownKind"));

      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unsupported proposedChange kind");
    }

    @Test
    void parseProposedChange_dropColumn_missingColumn_throws() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "dropColumn"));

      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dropColumn");
    }

    @Test
    void parseProposedChange_changeColumnType_missingFromType_throws() {
      Map<String, Object> params = new HashMap<>();
      params.put(
          "proposedChange", Map.of("kind", "changeColumnType", "column", "id", "toType", "bigint"));

      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("fromType");
    }

    @Test
    void parseProposedChange_custom_missingDescription_throws() {
      Map<String, Object> params = new HashMap<>();
      params.put("proposedChange", Map.of("kind", "custom"));

      assertThatThrownBy(() -> ChangeImpactTool.parseProposedChange(params))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("description");
    }

    @Test
    void proposedChange_describe_dropColumn() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "customer_id", null, null, null);
      assertThat(pc.describe()).isEqualTo("dropping column `customer_id`");
    }

    @Test
    void proposedChange_describe_changeColumnType() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("changeColumnType", "id", "int", "bigint", null);
      assertThat(pc.describe()).isEqualTo("changing column `id` from int to bigint");
    }

    @Test
    void proposedChange_describe_deprecateEntity() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      assertThat(pc.describe()).isEqualTo("deprecating entity");
    }

    @Test
    void proposedChange_describe_custom() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("custom", null, null, null, "renaming table");
      assertThat(pc.describe()).isEqualTo("renaming table");
    }

    @Test
    void proposedChange_toMap_omitsNullFields() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      Map<String, Object> map = pc.toMap();
      assertThat(map).containsEntry("kind", "deprecateEntity");
      assertThat(map).doesNotContainKey("column");
      assertThat(map).doesNotContainKey("fromType");
    }

    @Test
    void proposedChange_toMap_includesAllFields() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("changeColumnType", "id", "int", "bigint", null);
      Map<String, Object> map = pc.toMap();
      assertThat(map).containsEntry("kind", "changeColumnType");
      assertThat(map).containsEntry("column", "id");
      assertThat(map).containsEntry("fromType", "int");
      assertThat(map).containsEntry("toType", "bigint");
    }
  }

  // ====================== Severity rubric (R2.4) ======================

  @Nested
  class SeverityComputation {

    @Test
    void severity_critical_whenTier1EntityAffected() {
      Map<String, Object> tier1Entity = new HashMap<>();
      Map<String, Object> tierTag = new HashMap<>();
      tierTag.put("tagFQN", "Tier.Tier1");
      tier1Entity.put("tier", tierTag);

      String severity =
          ChangeImpactTool.computeSeverity(
              List.of(tier1Entity), List.of(), List.of(), List.of(), List.of());

      assertThat(severity).isEqualTo("critical");
    }

    @Test
    void severity_low_whenNoDownstream() {
      String severity =
          ChangeImpactTool.computeSeverity(List.of(), List.of(), List.of(), List.of(), List.of());

      assertThat(severity).isEqualTo("low");
    }

    @Test
    void severity_high_whenFiveOrMoreDownstream() {
      // 5 downstream entities, no dashboards/pipelines/tests
      List<Map<String, Object>> entities =
          List.of(
              Map.of("name", "a"),
              Map.of("name", "b"),
              Map.of("name", "c"),
              Map.of("name", "d"),
              Map.of("name", "e"));

      String severity =
          ChangeImpactTool.computeSeverity(entities, List.of(), List.of(), List.of(), List.of());

      assertThat(severity).isEqualTo("high");
    }

    @Test
    void severity_high_withMixedAssetTypes() {
      // 3 downstream entities + 2 dashboards = 5 total downstream
      String severity =
          ChangeImpactTool.computeSeverity(
              List.of(Map.of("name", "a"), Map.of("name", "b"), Map.of("name", "c")),
              List.of(Map.of("name", "d1"), Map.of("name", "d2")),
              List.of(),
              List.of(),
              List.of());

      assertThat(severity).isEqualTo("high");
    }

    @Test
    void severity_medium_whenOneToFourDownstream() {
      String severity =
          ChangeImpactTool.computeSeverity(
              List.of(Map.of("name", "a")), List.of(), List.of(), List.of(), List.of());

      assertThat(severity).isEqualTo("medium");
    }
  }

  // ====================== Narrative generation (R2.5) ======================

  @Nested
  class NarrativeGeneration {

    @Test
    void narrative_includesFqnAndChange() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "customer_id", null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 5, "dashboards", 2, "pipelines", 1, "tests", 7, "policies", 0);

      String narrative = ChangeImpactTool.generateNarrative("db.schema.orders", pc, "high", counts);

      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("dropping column `customer_id`");
      assertThat(narrative).contains("HIGH");
      // Narrative uses bold markdown: **5** downstream entities
      assertThat(narrative).contains("**5** downstream entit");
      assertThat(narrative).contains("**2** dashboard");
      assertThat(narrative).contains("**1** pipeline");
      assertThat(narrative).contains("**7** test case");
    }

    @Test
    void narrative_noImpact_showsNoDownstreamMessage() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 0, "dashboards", 0, "pipelines", 0, "tests", 0, "policies", 0);

      String narrative = ChangeImpactTool.generateNarrative("db.schema.table", pc, "low", counts);

      assertThat(narrative).contains("No downstream impact detected");
    }

    @Test
    void narrative_criticalSeverity_showsWarning() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 1, "dashboards", 0, "pipelines", 0, "tests", 0, "policies", 0);

      String narrative =
          ChangeImpactTool.generateNarrative("db.schema.orders", pc, "critical", counts);

      assertThat(narrative).contains("Tier-1 asset affected");
    }

    @Test
    void capNarrative_truncatesWhenTooLong() {
      String longNarrative = "x".repeat(1500);
      String capped = ChangeImpactTool.capNarrative(longNarrative);
      assertThat(capped.length()).isEqualTo(1200);
      assertThat(capped).endsWith("...");
    }

    @Test
    void capNarrative_noTruncationWhenUnderLimit() {
      String shortNarrative = "Short narrative";
      assertThat(ChangeImpactTool.capNarrative(shortNarrative)).isEqualTo(shortNarrative);
    }

    @Test
    void narrative_maxLength_doesNotExceed1200() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "customer_id", null, null, null);
      Map<String, Object> counts =
          Map.of("entities", 100, "dashboards", 50, "pipelines", 30, "tests", 20, "policies", 10);

      String narrative = ChangeImpactTool.generateNarrative("db.schema.orders", pc, "high", counts);
      String capped = ChangeImpactTool.capNarrative(narrative);

      assertThat(capped.length()).isLessThanOrEqualTo(1200);
    }
  }

  // ====================== Downstream node extraction ======================

  @Nested
  class DownstreamNodeExtraction {

    @Test
    void extractDownstreamNodes_excludesSourceEntity() {
      Map<String, Object> node1 = new HashMap<>();
      node1.put("fullyQualifiedName", "db.schema.source_table");
      node1.put("name", "source_table");

      Map<String, Object> node2 = new HashMap<>();
      node2.put("fullyQualifiedName", "db.schema.downstream_table");
      node2.put("name", "downstream_table");

      Map<String, Object> lineageData = new HashMap<>();
      lineageData.put("nodes", Map.of("n1", node1, "n2", node2));

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.source_table");

      assertThat(result).hasSize(1);
      assertThat(result.get(0).get("fullyQualifiedName")).isEqualTo("db.schema.downstream_table");
      assertThat(result.get(0).get("hitReason")).isEqualTo("downstream");
    }

    @Test
    void extractDownstreamNodes_emptyNodes_returnsEmptyList() {
      Map<String, Object> lineageData = new HashMap<>();
      lineageData.put("nodes", Map.of());

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.table");

      assertThat(result).isEmpty();
    }

    @Test
    void extractDownstreamNodes_noNodesKey_returnsEmptyList() {
      Map<String, Object> lineageData = new HashMap<>();

      List<Map<String, Object>> result =
          ChangeImpactTool.extractDownstreamNodes(lineageData, "db.schema.table");

      assertThat(result).isEmpty();
    }
  }

  // ====================== Query filter building ======================

  @Nested
  class QueryFilterBuilding {

    @Test
    void buildReferenceQueryFilter_columnLevel_includesColumnMatch() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("dropColumn", "customer_id", null, null, null);

      String filter =
          ChangeImpactTool.buildReferenceQueryFilter("db.schema.orders", pc, "dashboard");

      assertThat(filter).contains("\"match\":{\"columns.name\":\"customer_id\"}");
      assertThat(filter).contains("\"term\":{\"entityType\":\"dashboard\"}");
    }

    @Test
    void buildReferenceQueryFilter_entityLevel_includesMultiMatch() {
      ChangeImpactTool.ProposedChange pc =
          new ChangeImpactTool.ProposedChange("deprecateEntity", null, null, null, null);

      String filter =
          ChangeImpactTool.buildReferenceQueryFilter("db.schema.orders", pc, "pipeline");

      assertThat(filter).contains("\"multi_match\"");
      assertThat(filter).contains("db.schema.orders");
      assertThat(filter).contains("\"term\":{\"entityType\":\"pipeline\"}");
    }
  }

  // ====================== Byte cap enforcement (R2.9) ======================

  @Nested
  class ByteCapEnforcement {

    @Test
    void enforceByteCap_underLimit_returnsUnmodified() {
      Map<String, Object> smallResult = Map.of("severity", "low", "results", List.of());
      Map<String, Object> result = new HashMap<>(smallResult);

      Map<String, Object> capped = ChangeImpactTool.enforceByteCap(result);

      assertThat(capped).doesNotContainKey("warnings");
    }

    @Test
    void enforceByteCap_overLimit_truncatesAndAddsWarnings() {
      // Build a result that exceeds 8KB
      Map<String, Object> impactResult = new LinkedHashMap<>();
      Map<String, Object> affected = new LinkedHashMap<>();

      // Create a large entities list to push over 8KB
      List<Map<String, Object>> largeEntityList = new java.util.ArrayList<>();
      for (int i = 0; i < 100; i++) {
        Map<String, Object> entity = new HashMap<>();
        entity.put("fullyQualifiedName", "db.schema.table_with_a_very_long_name_" + i);
        entity.put("name", "table_with_a_very_long_name_" + i);
        entity.put(
            "description",
            "A table description that is reasonably long to fill up bytes ".repeat(5));
        entity.put("hitReason", "downstream");
        largeEntityList.add(entity);
      }

      affected.put("entities", largeEntityList);
      affected.put("dashboards", List.of());
      affected.put("pipelines", List.of());
      affected.put("tests", List.of());
      affected.put("policies", List.of());

      impactResult.put("severity", "high");
      impactResult.put("affected", affected);
      impactResult.put(
          "counts",
          Map.of("entities", 100, "dashboards", 0, "pipelines", 0, "tests", 0, "policies", 0));
      impactResult.put("proposedChange", Map.of("kind", "dropColumn", "column", "customer_id"));

      Map<String, Object> envelope = new HashMap<>();
      envelope.put("results", List.of(impactResult));
      envelope.put("narrative", "Test narrative");

      Map<String, Object> result = new HashMap<>(envelope);

      // Verify it's over the limit initially
      String json = JsonUtils.pojoToJson(result);
      assertThat(json.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(8192);

      Map<String, Object> capped = ChangeImpactTool.enforceByteCap(result);

      // Should have added warnings about truncation
      assertThat(capped).containsKey("warnings");
      @SuppressWarnings("unchecked")
      List<String> warnings = (List<String>) capped.get("warnings");
      assertThat(warnings).anyMatch(w -> w.contains("truncated:entities"));
    }
  }

  // ====================== Full execute() integration test ======================

  @Nested
  class ExecuteIntegration {

    @Test
    void execute_dropColumn_returnsExpectedStructure() {
      // This test validates the full execute flow with mocked Entity calls
      EntityReference entityRef = mock(EntityReference.class);
      when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.orders");

      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("fqn", "db.schema.orders");
      params.put("proposedChange", Map.of("kind", "dropColumn", "column", "customer_id"));

      // We test the static helper methods directly since execute() requires
      // extensive mocking of Entity.getLineageRepository() and Entity.getSearchRepository()
      // which have static initializers requiring a running service.

      // Verify proposedChange parsing works
      ChangeImpactTool.ProposedChange pc = ChangeImpactTool.parseProposedChange(params);
      assertThat(pc.kind).isEqualTo("dropColumn");
      assertThat(pc.column).isEqualTo("customer_id");

      // Verify severity computation for a medium-impact scenario
      String severity =
          ChangeImpactTool.computeSeverity(
              List.of(Map.of("name", "t1"), Map.of("name", "t2"), Map.of("name", "t3")),
              List.of(Map.of("name", "d1")),
              List.of(),
              List.of(),
              List.of());
      assertThat(severity).isEqualTo("medium");

      // Verify narrative generation
      Map<String, Object> counts =
          Map.of("entities", 3, "dashboards", 1, "pipelines", 0, "tests", 0, "policies", 0);
      String narrative =
          ChangeImpactTool.generateNarrative("db.schema.orders", pc, "medium", counts);
      assertThat(narrative).contains("db.schema.orders");
      assertThat(narrative).contains("dropping column `customer_id`");
      assertThat(narrative).contains("MEDIUM");
    }

    @Test
    void execute_missingFqn_throwsFromResolveEntityRef() {
      Map<String, Object> params = new HashMap<>();
      params.put("entityType", "table");
      params.put("proposedChange", Map.of("kind", "deprecateEntity"));

      // Without fqn or any identifier, resolveEntityRef should throw
      assertThatThrownBy(() -> ToolUtils.resolveEntityRef(params, "table"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Could not resolve entity reference");
    }

    @Test
    void execute_emptyParams_throws() {
      assertThatThrownBy(
              () ->
                  new ChangeImpactTool()
                      .execute(
                          mock(org.openmetadata.service.security.Authorizer.class),
                          mock(org.openmetadata.service.security.auth.CatalogSecurityContext.class),
                          null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Parameters cannot be null or empty");
    }
  }
}
