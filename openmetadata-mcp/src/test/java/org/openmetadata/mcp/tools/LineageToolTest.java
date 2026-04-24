package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Unit tests for LineageTool.
 *
 * <p>Tests verify:
 * - Parameter validation (missing fromEntity/toEntity, missing type/id)
 * - Envelope response structure via buildLineageResponse (results, narrative, backward-compat fields)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LineageToolTest {

  private LineageTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;

  @BeforeEach
  void setUp() {
    tool = new LineageTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    // Stub getUserPrincipal() so the production execute() overload doesn't NPE
    // before delegating to the test-friendly overload where validation happens.
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);
  }

  @Test
  void execute_missingFromEntity_throwsException() {
    Map<String, Object> toEntityMap = new HashMap<>();
    toEntityMap.put("id", UUID.randomUUID().toString());
    toEntityMap.put("type", "table");
    toEntityMap.put("name", "target_table");

    Map<String, Object> params = new HashMap<>();
    params.put("toEntity", toEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("fromEntity");
  }

  @Test
  void execute_missingToEntity_throwsException() {
    Map<String, Object> fromEntityMap = new HashMap<>();
    fromEntityMap.put("id", UUID.randomUUID().toString());
    fromEntityMap.put("type", "table");
    fromEntityMap.put("name", "source_table");

    Map<String, Object> params = new HashMap<>();
    params.put("fromEntity", fromEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("toEntity");
  }

  @Test
  void execute_fromEntityMissingType_throwsException() {
    Map<String, Object> fromEntityMap = new HashMap<>();
    fromEntityMap.put("id", UUID.randomUUID().toString());
    fromEntityMap.put("name", "source_table");
    // type is missing

    Map<String, Object> toEntityMap = new HashMap<>();
    toEntityMap.put("id", UUID.randomUUID().toString());
    toEntityMap.put("type", "table");
    toEntityMap.put("name", "target_table");

    Map<String, Object> params = new HashMap<>();
    params.put("fromEntity", fromEntityMap);
    params.put("toEntity", toEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("fromEntity");
  }

  @Test
  void execute_fromEntityMissingId_throwsException() {
    Map<String, Object> fromEntityMap = new HashMap<>();
    fromEntityMap.put("type", "table");
    fromEntityMap.put("name", "source_table");
    // id is missing

    Map<String, Object> toEntityMap = new HashMap<>();
    toEntityMap.put("id", UUID.randomUUID().toString());
    toEntityMap.put("type", "table");
    toEntityMap.put("name", "target_table");

    Map<String, Object> params = new HashMap<>();
    params.put("fromEntity", fromEntityMap);
    params.put("toEntity", toEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("fromEntity");
  }

  @Test
  void execute_toEntityMissingType_throwsException() {
    Map<String, Object> fromEntityMap = new HashMap<>();
    fromEntityMap.put("id", UUID.randomUUID().toString());
    fromEntityMap.put("type", "table");
    fromEntityMap.put("name", "source_table");

    Map<String, Object> toEntityMap = new HashMap<>();
    toEntityMap.put("id", UUID.randomUUID().toString());
    toEntityMap.put("name", "target_table");
    // type is missing

    Map<String, Object> params = new HashMap<>();
    params.put("fromEntity", fromEntityMap);
    params.put("toEntity", toEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("toEntity");
  }

  @Test
  void execute_toEntityMissingId_throwsException() {
    Map<String, Object> fromEntityMap = new HashMap<>();
    fromEntityMap.put("id", UUID.randomUUID().toString());
    fromEntityMap.put("type", "table");
    fromEntityMap.put("name", "source_table");

    Map<String, Object> toEntityMap = new HashMap<>();
    toEntityMap.put("type", "table");
    toEntityMap.put("name", "target_table");
    // id is missing

    Map<String, Object> params = new HashMap<>();
    params.put("fromEntity", fromEntityMap);
    params.put("toEntity", toEntityMap);

    IllegalArgumentException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> tool.execute(authorizer, securityContext, params));

    assertThat(thrown.getMessage()).contains("toEntity");
  }

  @Nested
  class BuildLineageResponseTests {

    private EntityReference fromEntity;
    private EntityReference toEntity;

    @BeforeEach
    void setUpEntities() {
      fromEntity =
          new EntityReference()
              .withId(UUID.randomUUID())
              .withType("table")
              .withName("source_table");
      toEntity =
          new EntityReference()
              .withId(UUID.randomUUID())
              .withType("dashboard")
              .withName("target_dashboard");
    }

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
    }

    @Test
    void resultsIsEmptyList() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void narrativeDescribesTheEdge() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("table.source_table");
      assertThat(narrative).contains("dashboard.target_dashboard");
      assertThat(narrative).startsWith("Created lineage edge");
    }

    @Test
    void backwardCompatFromEntity() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      @SuppressWarnings("unchecked")
      Map<String, Object> fromCompat = (Map<String, Object>) result.get("fromEntity");
      assertThat(fromCompat).containsEntry("type", "table");
      assertThat(fromCompat).containsEntry("name", "source_table");
      assertThat(fromCompat).hasSize(2); // only type and name, no id leak
    }

    @Test
    void backwardCompatToEntity() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      @SuppressWarnings("unchecked")
      Map<String, Object> toCompat = (Map<String, Object>) result.get("toEntity");
      assertThat(toCompat).containsEntry("type", "dashboard");
      assertThat(toCompat).containsEntry("name", "target_dashboard");
      assertThat(toCompat).hasSize(2); // only type and name, no id leak
    }

    @Test
    void noPaginationBlock() {
      Map<String, Object> result = LineageTool.buildLineageResponse(fromEntity, toEntity);

      assertThat(result).doesNotContainKey("pagination");
    }
  }
}
