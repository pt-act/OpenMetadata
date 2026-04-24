package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolUtils.resolveFqn().
 *
 * <p>Tests verify the FQN parameter resolution strategy chain:
 * - fqn → fullyQualifiedName → throw
 * - Empty/blank strings treated as absent
 * - Precedence: fqn wins over fullyQualifiedName when both present
 */
class ToolUtilsTest {

  @Test
  void resolveFqn_fqnPresent_returnsFqn() {
    Map<String, Object> params = Map.of("fqn", "db.schema.table");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("db.schema.table");
  }

  @Test
  void resolveFqn_onlyFullyQualifiedNamePresent_returnsIt() {
    Map<String, Object> params = Map.of("fullyQualifiedName", "db.schema.table");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("db.schema.table");
  }

  @Test
  void resolveFqn_bothPresent_fqnTakesPrecedence() {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", "from.fqn");
    params.put("fullyQualifiedName", "from.fqn.alias");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("from.fqn");
  }

  @Test
  void resolveFqn_neitherPresent_throws() {
    Map<String, Object> params = Map.of("entityType", "table");
    assertThatThrownBy(() -> ToolUtils.resolveFqn(params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fqn")
        .hasMessageContaining("fullyQualifiedName");
  }

  @Test
  void resolveFqn_emptyParams_throws() {
    assertThatThrownBy(() -> ToolUtils.resolveFqn(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveFqn_emptyStringTreatedAsAbsent() {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", "");
    params.put("fullyQualifiedName", "db.schema.table");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("db.schema.table");
  }

  @Test
  void resolveFqn_blankStringTreatedAsAbsent() {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", "   ");
    params.put("fullyQualifiedName", "db.schema.table");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("db.schema.table");
  }

  @Test
  void resolveFqn_bothEmpty_throws() {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", "");
    params.put("fullyQualifiedName", "  ");
    assertThatThrownBy(() -> ToolUtils.resolveFqn(params))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveFqn_nullFqnValue_fallsThrough() {
    Map<String, Object> params = new HashMap<>();
    params.put("fqn", null);
    params.put("fullyQualifiedName", "db.schema.table");
    assertThat(ToolUtils.resolveFqn(params)).isEqualTo("db.schema.table");
  }
}
