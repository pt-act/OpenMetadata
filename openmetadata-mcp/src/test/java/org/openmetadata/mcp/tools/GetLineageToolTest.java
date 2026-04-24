package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/**
 * Unit tests for GetLineageTool.
 *
 * <p>Tests verify parameter validation, depth clamping logic, and FQN alias resolution.
 * Repository integration is tested via integration tests since LineageRepository has
 * a static initializer that requires a running search client.
 *
 * <p>Tests verify:
 * - Missing entityType throws IllegalArgumentException
 * - Missing fqn/fullyQualifiedName throws IllegalArgumentException (via ToolUtils)
 * - Empty/null params throws IllegalArgumentException
 * - fullyQualifiedName alias resolves correctly (via ToolUtils.resolveEntityRef)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetLineageToolTest {

  private GetLineageTool tool;
  private Authorizer authorizer;
  private CatalogSecurityContext securityContext;

  @BeforeEach
  void setUp() {
    tool = new GetLineageTool();
    authorizer = mock(Authorizer.class);
    securityContext = mock(CatalogSecurityContext.class);
    Principal principal = mock(Principal.class);
    when(principal.getName()).thenReturn("test-user");
    when(securityContext.getUserPrincipal()).thenReturn(principal);
  }

  @Test
  void execute_missingEntityType_throwsIllegalArgumentException() {
    Map<String, Object> params = Map.of("fqn", "db.schema.table");

    assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entityType");
  }

  @Test
  void execute_missingFqnAndAlias_throwsIllegalArgumentException() {
    Map<String, Object> params = Map.of("entityType", "table");

    assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fqn");
  }

  @Test
  void execute_emptyParams_throwsIllegalArgumentException() {
    Map<String, Object> params = new HashMap<>();

    assertThatThrownBy(() -> tool.execute(authorizer, securityContext, params))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Parameters cannot be null or empty");
  }

  @Test
  void execute_nullParams_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> tool.execute(authorizer, securityContext, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Parameters cannot be null or empty");
  }

  @Test
  void execute_fullyQualifiedNameAlias_validatedByToolUtils() throws Exception {
    // ToolUtils.resolveFqn is tested separately in ToolUtilsTest.
    // Here we verify the tool correctly passes fullyQualifiedName through resolveEntityRef.
    Map<String, Object> params = new HashMap<>();
    params.put("entityType", "table");
    params.put("fullyQualifiedName", "db.schema.table");

    EntityReference entityRef = mock(EntityReference.class);
    when(entityRef.getFullyQualifiedName()).thenReturn("db.schema.table");

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.EntityReferenceResolver referenceResolver =
        (entityType, fqn, include) -> "table".equals(entityType) ? entityRef : null;
    McpEntityBridge.McpAuthorizer noopAuthorizer = (entityType, op) -> {};
    // Lineage repo returns null → tool returns graceful error (not exception)
    McpEntityBridge.LineageRepositoryProvider nullLineageProvider = () -> null;

    Map<String, Object> result =
        tool.execute(params, referenceResolver, noopAuthorizer, nullLineageProvider);

    // The FQN alias was resolved (no "Could not resolve entity reference" error)
    // and the tool returned a result (graceful null-repo handling)
    assertThat(result).containsEntry("fqn", "db.schema.table");
    assertThat(result).containsEntry("entityType", "table");
    assertThat(result).containsKey("error");
    assertThat(result.get("error").toString()).doesNotContain("Could not resolve entity reference");
  }
}
