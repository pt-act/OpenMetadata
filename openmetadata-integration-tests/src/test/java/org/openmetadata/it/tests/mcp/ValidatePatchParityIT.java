package org.openmetadata.it.tests.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openmetadata.schema.entity.data.Table;

/**
 * R9.8 Parity integration test — verifies that {@code validate_patch} afterSnapshot matches the
 * actual post-state produced by {@code patch_entity} for a set of "golden patches".
 *
 * <p>Strategy:
 * <ol>
 *   <li>Apply a precondition patch to set up the right entity state for the test
 *   <li>Call {@code validate_patch} with a golden patch → capture afterSnapshot
 *   <li>Call {@code patch_entity} with the same golden patch → apply it
 *   <li>GET the entity from the server to get the authoritative current state
 *   <li>Compare the afterSnapshot fields with the actual server state
 *   <li>Restore the entity to its clean baseline after each test
 * </ol>
 *
 * <p>Requires a running OpenMetadata server with MCP enabled.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("mcp")
class ValidatePatchParityIT extends McpTestBase {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ValidatePatchParityIT.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Golden patches to test. Each argument set is: [description, preconditionPatch (or null),
   * testPatch, fieldToCompare].
   *
   * <p>The precondition patch is applied before the test to set up the right entity state (e.g.,
   * adding a tier before testing "replace tier"). If null, no precondition is needed.
   */
  private static Stream<Arguments> goldenPatches() {
    return Stream.of(
        // 1. Replace description (description is always present)
        Arguments.of(
            "replace description",
            null,
            "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"Parity test description\"}]",
            "description"),
        // 2. Add a PII tag (tags may or may not be present — add always works)
        Arguments.of(
            "add tag",
            null,
            "[{\"op\":\"add\",\"path\":\"/tags/-\",\"value\":{\"tagFQN\":\"PII.Sensitive\",\"labelType\":\"Generated\",\"state\":\"Confirmed\",\"source\":\"Classification\"}}]",
            "tags"),
        // 3. Clear description (description is present)
        Arguments.of(
            "clear description",
            null,
            "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"\"}]",
            "description"),
        // 4. Restore description via "add" op
        Arguments.of(
            "restore description",
            "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"\"}]",
            "[{\"op\":\"add\",\"path\":\"/description\",\"value\":\"Restored description\"}]",
            "description"),
        // 5. Add tier (tier absent by default after restoreBaseline)
        Arguments.of(
            "add tier",
            null,
            "[{\"op\":\"add\",\"path\":\"/tier\",\"value\":{\"tagFQN\":\"Tier.Tier2\",\"labelType\":\"Generated\",\"state\":\"Confirmed\",\"source\":\"Classification\"}}]",
            "tier"),
        // 6. Replace tier (precondition: add tier first)
        Arguments.of(
            "replace tier",
            "[{\"op\":\"add\",\"path\":\"/tier\",\"value\":{\"tagFQN\":\"Tier.Tier2\",\"labelType\":\"Generated\",\"state\":\"Confirmed\",\"source\":\"Classification\"}}]",
            "[{\"op\":\"replace\",\"path\":\"/tier\",\"value\":{\"tagFQN\":\"Tier.Tier3\",\"labelType\":\"Generated\",\"state\":\"Confirmed\",\"source\":\"Classification\"}}]",
            "tier"),
        // 7. Remove tier (precondition: add tier first)
        Arguments.of(
            "remove tier",
            "[{\"op\":\"add\",\"path\":\"/tier\",\"value\":{\"tagFQN\":\"Tier.Tier2\",\"labelType\":\"Generated\",\"state\":\"Confirmed\",\"source\":\"Classification\"}}]",
            "[{\"op\":\"remove\",\"path\":\"/tier\"}]",
            "tier"),
        // 8. Add displayName (displayName absent by default)
        Arguments.of(
            "add displayName",
            null,
            "[{\"op\":\"add\",\"path\":\"/displayName\",\"value\":\"Parity Display Name\"}]",
            "displayName"),
        // 9. Replace displayName (precondition: add displayName first)
        Arguments.of(
            "replace displayName",
            "[{\"op\":\"add\",\"path\":\"/displayName\",\"value\":\"Original Display\"}]",
            "[{\"op\":\"replace\",\"path\":\"/displayName\",\"value\":\"Updated Display Name\"}]",
            "displayName"),
        // 10. Remove displayName (precondition: add displayName first)
        Arguments.of(
            "remove displayName",
            "[{\"op\":\"add\",\"path\":\"/displayName\",\"value\":\"To Be Removed\"}]",
            "[{\"op\":\"remove\",\"path\":\"/displayName\"}]",
            "displayName"),
        // 11. Add retentionPeriod (absent by default; note: retentionPeriod can be inherited
        //     from parent database/schema, but createServiceDatabaseSchemaTable creates entities
        //     without a parent retentionPeriod, so inheritance is not a concern here)
        Arguments.of(
            "add retentionPeriod",
            null,
            "[{\"op\":\"add\",\"path\":\"/retentionPeriod\",\"value\":\"P30D\"}]",
            "retentionPeriod"),
        // 12. Replace retentionPeriod (precondition: add first)
        Arguments.of(
            "replace retentionPeriod",
            "[{\"op\":\"add\",\"path\":\"/retentionPeriod\",\"value\":\"P30D\"}]",
            "[{\"op\":\"replace\",\"path\":\"/retentionPeriod\",\"value\":\"P60D\"}]",
            "retentionPeriod"),
        // 13. Remove retentionPeriod (precondition: add first)
        Arguments.of(
            "remove retentionPeriod",
            "[{\"op\":\"add\",\"path\":\"/retentionPeriod\",\"value\":\"P30D\"}]",
            "[{\"op\":\"remove\",\"path\":\"/retentionPeriod\"}]",
            "retentionPeriod"),
        // 14. Add sourceUrl (sourceUrl absent by default on test table)
        Arguments.of(
            "add sourceUrl",
            null,
            "[{\"op\":\"add\",\"path\":\"/sourceUrl\",\"value\":\"https://example.com/source\"}]",
            "sourceUrl"),
        // 15. Replace sourceUrl (precondition: add first)
        Arguments.of(
            "replace sourceUrl",
            "[{\"op\":\"add\",\"path\":\"/sourceUrl\",\"value\":\"https://example.com/source\"}]",
            "[{\"op\":\"replace\",\"path\":\"/sourceUrl\",\"value\":\"https://example.com/updated\"}]",
            "sourceUrl"),
        // 16. Remove sourceUrl (precondition: add first)
        Arguments.of(
            "remove sourceUrl",
            "[{\"op\":\"add\",\"path\":\"/sourceUrl\",\"value\":\"https://example.com/source\"}]",
            "[{\"op\":\"remove\",\"path\":\"/sourceUrl\"}]",
            "sourceUrl"),
        // 17. Add schemaDefinition (schemaDefinition absent by default on test table)
        Arguments.of(
            "add schemaDefinition",
            null,
            "[{\"op\":\"add\",\"path\":\"/schemaDefinition\",\"value\":\"CREATE VIEW test_vw AS SELECT * FROM orders\"}]",
            "schemaDefinition"),
        // 18. Replace schemaDefinition (precondition: add first)
        Arguments.of(
            "replace schemaDefinition",
            "[{\"op\":\"add\",\"path\":\"/schemaDefinition\",\"value\":\"CREATE VIEW test_vw AS SELECT * FROM orders\"}]",
            "[{\"op\":\"replace\",\"path\":\"/schemaDefinition\",\"value\":\"CREATE VIEW updated_vw AS SELECT * FROM products\"}]",
            "schemaDefinition"),
        // 19. Remove schemaDefinition (precondition: add first)
        Arguments.of(
            "remove schemaDefinition",
            "[{\"op\":\"add\",\"path\":\"/schemaDefinition\",\"value\":\"CREATE VIEW test_vw AS SELECT * FROM orders\"}]",
            "[{\"op\":\"remove\",\"path\":\"/schemaDefinition\"}]",
            "schemaDefinition"));
  }

  private static Table testTable;
  private static String testTableFqn;
  private static String originalDescription;
  private static String originalRetentionPeriod;
  private static String originalSourceUrl;
  private static String originalSchemaDefinition;
  private static Set<String> originalTagFqns;
  private static Set<String> originalOwnerIds;
  private static Set<String> originalDomainIds;
  private static JsonNode originalEntityState;

  // Resolved IDs for owners/domains patches (populated in @BeforeAll)
  private static String adminUserId;
  private static String testUser2Id;
  private static String testUser2Name;
  private static String testDomainId;
  private static String testDomainFqn;
  private static String testDomain2Id;
  private static String testDomain2Fqn;

  @BeforeAll
  static void setUp() throws Exception {
    initAuth();
    testTable = createServiceDatabaseSchemaTable("parity_r98");
    testTableFqn = testTable.getFullyQualifiedName();
    originalDescription = testTable.getDescription();
    originalRetentionPeriod = testTable.getRetentionPeriod();
    originalSourceUrl = testTable.getSourceUrl();
    originalSchemaDefinition = testTable.getSchemaDefinition();

    // Resolve admin user ID for owner patches
    adminUserId = resolveAdminUserId();

    // Create a second test user for "replace owners with different user" patch
    String[] user2 = createTestUser("parity_user2");
    testUser2Id = user2[0];
    testUser2Name = user2[1];

    // Create test domains for domain patches
    String[] domain1 = createTestDomain("parity_domain");
    testDomainId = domain1[0];
    testDomainFqn = domain1[1];
    String[] domain2 = createTestDomain("parity_domain2");
    testDomain2Id = domain2[0];
    testDomain2Fqn = domain2[1];

    // Capture original entity state so we can restore tags, owners, domains, and other fields
    originalEntityState = getEntityFromServerStatic();
    originalTagFqns = extractTagFqns(originalEntityState);
    originalOwnerIds = extractEntityRefIds(originalEntityState.path("owners"));
    originalDomainIds = extractEntityRefIds(originalEntityState.path("domains"));
  }

  @AfterAll
  static void tearDown() {
    try {
      delete("tables/name/" + testTableFqn);
    } catch (Exception e) {
      LOG.warn("Failed to clean up test table {}: {}", testTableFqn, e.getMessage());
    }
    try {
      delete("domains/name/" + testDomainFqn);
    } catch (Exception e) {
      LOG.warn("Failed to clean up test domain {}: {}", testDomainFqn, e.getMessage());
    }
    try {
      delete("domains/name/" + testDomain2Fqn);
    } catch (Exception e) {
      LOG.warn("Failed to clean up test domain2 {}: {}", testDomain2Fqn, e.getMessage());
    }
    try {
      delete("users/name/" + testUser2Name);
    } catch (Exception e) {
      LOG.warn("Failed to clean up test user2 {}: {}", testUser2Name, e.getMessage());
    }
  }

  /**
   * Parameterized parity test: for each golden patch, verify that validate_patch afterSnapshot
   * matches patch_entity post-state.
   */
  @ParameterizedTest(name = "parity: {0}")
  @MethodSource("goldenPatches")
  void testParityWithPatchEntity(
      String description, String preconditionPatch, String testPatch, String fieldToCompare)
      throws Exception {
    // Step 1: Restore to clean baseline
    restoreBaseline();

    // Step 2: Apply precondition patch if needed (e.g., add tier before testing "remove tier")
    if (preconditionPatch != null) {
      Map<String, Object> preCall =
          McpTestUtils.createPatchEntityToolCall("table", testTableFqn, preconditionPatch);
      executeToolCall(preCall);
    }

    // Step 3: Call validate_patch → capture afterSnapshot
    Map<String, Object> validateCall =
        McpTestUtils.createValidatePatchToolCall("table", testTableFqn, testPatch);
    JsonNode validateResult = executeToolCall(validateCall);

    JsonNode validateContent = parseContentText(validateResult);
    assertThat(validateContent.has("afterSnapshot"))
        .as("validate_patch must return afterSnapshot for patch: %s", description)
        .isTrue();
    JsonNode afterSnapshot = validateContent.get("afterSnapshot");

    // Step 4: Call patch_entity with the same patch → apply it
    Map<String, Object> patchCall =
        McpTestUtils.createPatchEntityToolCall("table", testTableFqn, testPatch);
    JsonNode patchResult = executeToolCall(patchCall);

    // Verify patch_entity succeeded
    JsonNode patchContent = parseContentText(patchResult);
    assertThat(patchContent.has("entity") || patchContent.has("id"))
        .as("patch_entity must succeed before parity comparison for: %s", description)
        .isTrue();

    // Step 5: GET the entity from the server to get the authoritative current state
    JsonNode actualState = getEntityFromServer();

    // Step 6: Compare the afterSnapshot field with the actual server state
    compareFields(afterSnapshot, actualState, fieldToCompare, description);

    // Step 7: Restore for the next test
    restoreBaseline();
  }

  // ====================== Owner parity tests ======================
  // Owner/domain patches require runtime-resolved IDs (admin user, test domain), so they
  // cannot use @MethodSource (which runs before @BeforeAll). They are separate @Test methods.

  /** Parity test: add owner. */
  @Test
  @Order(4)
  void testParityAddOwner() throws Exception {
    String addOwnerPatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/owners/-\",\"value\":{\"id\":\"%s\",\"type\":\"user\"}}]",
            adminUserId);
    runParityTest("add owner", null, addOwnerPatch, "owners");
  }

  /** Parity test: replace owners. */
  @Test
  @Order(5)
  void testParityReplaceOwners() throws Exception {
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/owners/-\",\"value\":{\"id\":\"%s\",\"type\":\"user\"}}]",
            adminUserId);
    String replacePatch =
        String.format(
            "[{\"op\":\"replace\",\"path\":\"/owners\",\"value\":[{\"id\":\"%s\",\"type\":\"user\"}]}]",
            adminUserId);
    runParityTest("replace owners", prePatch, replacePatch, "owners");
  }

  /** Parity test: replace owners with a different user. */
  @Test
  @Order(6)
  void testParityReplaceOwnersWithDifferentUser() throws Exception {
    // Precondition: add admin as owner first
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/owners/-\",\"value\":{\"id\":\"%s\",\"type\":\"user\"}}]",
            adminUserId);
    // Replace with a different user (testUser2)
    String replacePatch =
        String.format(
            "[{\"op\":\"replace\",\"path\":\"/owners\",\"value\":[{\"id\":\"%s\",\"type\":\"user\"}]}]",
            testUser2Id);
    runParityTest("replace owners with different user", prePatch, replacePatch, "owners");
  }

  /** Parity test: remove owners. */
  @Test
  @Order(7)
  void testParityRemoveOwners() throws Exception {
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/owners/-\",\"value\":{\"id\":\"%s\",\"type\":\"user\"}}]",
            adminUserId);
    runParityTest(
        "remove owners", prePatch, "[{\"op\":\"remove\",\"path\":\"/owners\"}]", "owners");
  }

  // ====================== Domain parity tests ======================

  /** Parity test: add domain. */
  @Test
  @Order(8)
  void testParityAddDomain() throws Exception {
    String addDomainPatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/domains/-\",\"value\":{\"id\":\"%s\",\"type\":\"domain\"}}]",
            testDomainId);
    runParityTest("add domain", null, addDomainPatch, "domains");
  }

  /** Parity test: replace domains. */
  @Test
  @Order(9)
  void testParityReplaceDomains() throws Exception {
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/domains/-\",\"value\":{\"id\":\"%s\",\"type\":\"domain\"}}]",
            testDomainId);
    String replacePatch =
        String.format(
            "[{\"op\":\"replace\",\"path\":\"/domains\",\"value\":[{\"id\":\"%s\",\"type\":\"domain\"}]}]",
            testDomainId);
    runParityTest("replace domains", prePatch, replacePatch, "domains");
  }

  /** Parity test: replace domains with a different domain. */
  @Test
  @Order(10)
  void testParityReplaceDomainsWithDifferentDomain() throws Exception {
    // Precondition: add domain1 first
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/domains/-\",\"value\":{\"id\":\"%s\",\"type\":\"domain\"}}]",
            testDomainId);
    // Replace with domain2 (different domain)
    String replacePatch =
        String.format(
            "[{\"op\":\"replace\",\"path\":\"/domains\",\"value\":[{\"id\":\"%s\",\"type\":\"domain\"}]}]",
            testDomain2Id);
    runParityTest("replace domains with different domain", prePatch, replacePatch, "domains");
  }

  /** Parity test: remove domains. */
  @Test
  @Order(11)
  void testParityRemoveDomains() throws Exception {
    String prePatch =
        String.format(
            "[{\"op\":\"add\",\"path\":\"/domains/-\",\"value\":{\"id\":\"%s\",\"type\":\"domain\"}}]",
            testDomainId);
    runParityTest(
        "remove domains", prePatch, "[{\"op\":\"remove\",\"path\":\"/domains\"}]", "domains");
  }

  // ====================== Extension parity tests ======================

  /** Parity test: add extension field. */
  @Test
  @Order(12)
  void testParityAddExtensionField() throws Exception {
    runParityTest(
        "add extension field",
        null,
        "[{\"op\":\"add\",\"path\":\"/extension/customField\",\"value\":\"customValue\"}]",
        "extension");
  }

  /** Parity test: remove extension field. */
  @Test
  @Order(13)
  void testParityRemoveExtensionField() throws Exception {
    runParityTest(
        "remove extension field",
        "[{\"op\":\"add\",\"path\":\"/extension/customField\",\"value\":\"toRemove\"}]",
        "[{\"op\":\"remove\",\"path\":\"/extension/customField\"}]",
        "extension");
  }

  /**
   * Reusable parity test runner. Same logic as the parameterized test, but constructs the patch
   * at execution time (when static fields like adminUserId and testDomainId are populated).
   */
  private void runParityTest(
      String description, String preconditionPatch, String testPatch, String fieldToCompare)
      throws Exception {
    restoreBaseline();

    if (preconditionPatch != null) {
      Map<String, Object> preCall =
          McpTestUtils.createPatchEntityToolCall("table", testTableFqn, preconditionPatch);
      executeToolCall(preCall);
    }

    Map<String, Object> validateCall =
        McpTestUtils.createValidatePatchToolCall("table", testTableFqn, testPatch);
    JsonNode validateResult = executeToolCall(validateCall);

    JsonNode validateContent = parseContentText(validateResult);
    assertThat(validateContent.has("afterSnapshot"))
        .as("validate_patch must return afterSnapshot for patch: %s", description)
        .isTrue();
    JsonNode afterSnapshot = validateContent.get("afterSnapshot");

    Map<String, Object> patchCall =
        McpTestUtils.createPatchEntityToolCall("table", testTableFqn, testPatch);
    JsonNode patchResult = executeToolCall(patchCall);

    JsonNode patchContent = parseContentText(patchResult);
    assertThat(patchContent.has("entity") || patchContent.has("id"))
        .as("patch_entity must succeed before parity comparison for: %s", description)
        .isTrue();

    JsonNode actualState = getEntityFromServer();
    compareFields(afterSnapshot, actualState, fieldToCompare, description);

    restoreBaseline();
  }

  /** Tests that validate_patch does NOT mutate the entity (read-only guarantee). */
  @Test
  @Order(2)
  void testValidatePatchDoesNotMutate() throws Exception {
    restoreBaseline();

    // Capture entity state before validate_patch
    JsonNode beforeValidate = getEntityFromServer();
    String descBefore = beforeValidate.path("description").asText("");

    // Call validate_patch with a description-replacing patch
    String patchJson =
        "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"Should not persist\"}]";
    Map<String, Object> validateCall =
        McpTestUtils.createValidatePatchToolCall("table", testTableFqn, patchJson);
    executeToolCall(validateCall);

    // GET the entity again — description must NOT have changed
    JsonNode afterValidate = getEntityFromServer();
    String descAfter = afterValidate.path("description").asText("");

    assertThat(descAfter)
        .as("validate_patch must not mutate the entity (R9.4 non-mutation guarantee)")
        .isEqualTo(descBefore);
  }

  /** Tests that validate_patch returns all required response fields. */
  @Test
  @Order(3)
  void testValidatePatchResponseShape() throws Exception {
    restoreBaseline();

    String patchJson = "[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"Shape test\"}]";
    Map<String, Object> validateCall =
        McpTestUtils.createValidatePatchToolCall("table", testTableFqn, patchJson);
    JsonNode result = executeToolCall(validateCall);
    JsonNode content = parseContentText(result);

    // Verify all required fields are present (R9.2)
    assertThat(content.has("fqn")).isTrue();
    assertThat(content.has("entityType")).isTrue();
    assertThat(content.has("beforeSnapshot")).isTrue();
    assertThat(content.has("afterSnapshot")).isTrue();
    assertThat(content.has("diff")).isTrue();
    assertThat(content.has("affectedDownstreamCount")).isTrue();
    assertThat(content.has("affectedDownstreamCountNote")).isTrue();

    // Verify fqn and entityType match
    assertThat(content.get("fqn").asText()).isEqualTo(testTableFqn);
    assertThat(content.get("entityType").asText()).isEqualTo("table");

    // Verify before and after differ
    JsonNode before = content.get("beforeSnapshot");
    JsonNode after = content.get("afterSnapshot");
    assertThat(before.path("description").asText(""))
        .isNotEqualTo(after.path("description").asText(""));
    assertThat(after.path("description").asText("")).isEqualTo("Shape test");
  }

  // ====================== Helper methods ======================

  /** Executes an MCP tool call and returns the result JsonNode. */
  private JsonNode executeToolCall(Map<String, Object> toolCall) throws Exception {
    JsonNode responseJson = executeMcpRequest(toolCall);
    assertThat(responseJson.has("result")).isTrue();
    return responseJson.get("result");
  }

  /** Parses the content[0].text from an MCP tool result into a JsonNode. */
  private JsonNode parseContentText(JsonNode mcpResult) throws Exception {
    assertThat(mcpResult.has("content")).isTrue();
    JsonNode content = mcpResult.get("content");
    assertThat(content.isArray()).isTrue();
    assertThat(content.size()).isGreaterThan(0);

    JsonNode firstItem = content.get(0);
    assertThat(firstItem.has("text")).isTrue();
    return MAPPER.readTree(firstItem.get("text").asText());
  }

  /** GETs the current entity state from the server via REST API. */
  private JsonNode getEntityFromServer() throws Exception {
    return getEntityFromServer(testTableFqn);
  }

  /** GETs the current entity state from the server via REST API (static version for @BeforeAll). */
  private static JsonNode getEntityFromServerStatic() throws Exception {
    return getEntityFromServer(testTableFqn);
  }

  /** GETs the current entity state from the server via REST API (includes owners, domains). */
  private static JsonNode getEntityFromServer(String fqn) throws Exception {
    String baseUrl =
        getMcpUrlStatic(
            "/api/v1/tables/name/" + fqn + "?fields=owners,domains,extension,schemaDefinition");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Authorization", authToken)
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  /**
   * Resolves the admin user ID by looking up the admin user via REST API.
   * The admin user always exists in a fresh OpenMetadata installation.
   */
  private static String resolveAdminUserId() throws Exception {
    String baseUrl = getMcpUrlStatic("/api/v1/users/name/admin");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Authorization", authToken)
            .header("Accept", "application/json")
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode adminUser = MAPPER.readTree(response.body());
    String id = adminUser.path("id").asText("");
    assertThat(id).as("Admin user must have an id").isNotEmpty();
    return id;
  }

  /**
   * Creates a test domain via REST API. Returns [id, fqn].
   * Domains are cleaned up in @AfterAll.
   */
  private static String[] createTestDomain(String namePrefix) throws Exception {
    String domainName = namePrefix + "_" + UUID.nameUUIDFromBytes(namePrefix.getBytes());
    String domainFqn = domainName;
    String jsonBody =
        String.format(
            "{\"name\":\"%s\",\"domainType\":\"Aggregate\",\"description\":\"Test domain for parity tests\"}",
            domainName);
    String baseUrl = getMcpUrlStatic("/api/v1/domains");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", authToken)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isIn(200, 201);
    JsonNode domain = MAPPER.readTree(response.body());
    String id = domain.path("id").asText("");
    assertThat(id).as("Created domain must have an id").isNotEmpty();
    return new String[] {id, domainFqn};
  }

  /**
   * Creates a test user via REST API. Returns [id, name].
   * The user is cleaned up in @AfterAll.
   */
  private static String[] createTestUser(String namePrefix) throws Exception {
    String userName = namePrefix + "_" + UUID.nameUUIDFromBytes(namePrefix.getBytes());
    String email = userName + "@parity-test.example.com";
    String jsonBody =
        String.format(
            "{\"name\":\"%s\",\"email\":\"%s\",\"description\":\"Test user for parity tests\"}",
            userName, email);
    String baseUrl = getMcpUrlStatic("/api/v1/users");
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", authToken)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(30))
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isIn(200, 201);
    JsonNode user = MAPPER.readTree(response.body());
    String id = user.path("id").asText("");
    assertThat(id).as("Created user must have an id").isNotEmpty();
    return new String[] {id, userName};
  }

  /** Extracts entity IDs from an EntityReference array node (e.g., owners, domains). */
  private static Set<String> extractEntityRefIds(JsonNode refArray) {
    if (!refArray.isArray()) {
      return Set.of();
    }
    return StreamSupport.stream(refArray.spliterator(), false)
        .map(ref -> ref.path("id").asText(""))
        .filter(id -> !id.isEmpty())
        .collect(Collectors.toSet());
  }

  /** Static version of getMcpUrl for use in static @BeforeAll. */
  private static String getMcpUrlStatic(String path) {
    return org.openmetadata.it.bootstrap.TestSuiteBootstrap.getBaseUrl() + path;
  }

  /** Extracts tag FQN values from an entity JSON node (navigates to /tags). */
  private static Set<String> extractTagFqns(JsonNode entityNode) {
    return extractTagFqnsFromField(entityNode.path("tags"));
  }

  /** Extracts tag FQN values directly from a tags array node. */
  private static Set<String> extractTagFqnsFromField(JsonNode tagsField) {
    if (!tagsField.isArray()) {
      return Set.of();
    }
    return StreamSupport.stream(tagsField.spliterator(), false)
        .map(tag -> tag.path("tagFQN").asText(""))
        .filter(fqn -> !fqn.isEmpty())
        .collect(Collectors.toSet());
  }

  /**
   * Compares a specific field between the validate_patch afterSnapshot and the actual server state.
   */
  private void compareFields(
      JsonNode afterSnapshot, JsonNode actualState, String fieldToCompare, String description) {

    JsonNode snapshotField = afterSnapshot.path(fieldToCompare);
    JsonNode actualField = actualState.path(fieldToCompare);

    // EntityReference arrays (owners, domains) — compare by sets of IDs (order-independent)
    // Must be checked BEFORE the generic array branch, since these are also arrays.
    if (fieldToCompare.equals("owners") || fieldToCompare.equals("domains")) {
      Set<String> snapshotIds = extractEntityRefIds(snapshotField);
      Set<String> actualIds = extractEntityRefIds(actualField);
      // Both absent/null is equivalent
      if (snapshotIds.isEmpty() && actualIds.isEmpty()) {
        return;
      }
      assertThat(snapshotIds)
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot ids=%s, actual ids=%s",
              fieldToCompare, description, snapshotIds, actualIds)
          .isEqualTo(actualIds);
    } else if (fieldToCompare.equals("extension")) {
      // Extension is a free-form object — compare as JSON trees
      assertThat(snapshotField)
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot=%s, actual=%s",
              fieldToCompare, description, snapshotField, actualField)
          .isEqualTo(actualField);
    } else if (snapshotField.isArray() && actualField.isArray()) {
      // For tag arrays, compare using sets of tagFQN values (order-independent)
      Set<String> snapshotTags = extractTagFqnsFromField(snapshotField);
      Set<String> actualTags = extractTagFqnsFromField(actualField);
      assertThat(snapshotTags)
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot tags=%s, actual tags=%s",
              fieldToCompare, description, snapshotTags, actualTags)
          .isEqualTo(actualTags);
    } else if (snapshotField.isMissingNode() || snapshotField.isNull()) {
      // Field was removed or absent in afterSnapshot — actual should also be absent/null
      assertThat(actualField.isMissingNode() || actualField.isNull())
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot is absent/null but actual is present",
              fieldToCompare, description)
          .isTrue();
    } else if (fieldToCompare.equals("description")) {
      assertThat(snapshotField.asText(""))
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot='%s', actual='%s'",
              fieldToCompare, description, snapshotField.asText(""), actualField.asText(""))
          .isEqualTo(actualField.asText(""));
    } else if (fieldToCompare.equals("tier")) {
      String snapshotTier = snapshotField.path("tagFQN").asText("");
      String actualTier = actualField.path("tagFQN").asText("");
      // Both absent is equivalent
      if (snapshotTier.isEmpty() && actualTier.isEmpty()) {
        return;
      }
      assertThat(snapshotTier)
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot.tagFQN='%s', actual.tagFQN='%s'",
              fieldToCompare, description, snapshotTier, actualTier)
          .isEqualTo(actualTier);
    } else if (fieldToCompare.equals("displayName")
        || fieldToCompare.equals("retentionPeriod")
        || fieldToCompare.equals("sourceUrl")
        || fieldToCompare.equals("schemaDefinition")) {
      String snapshotVal = snapshotField.asText("");
      String actualVal = actualField.asText("");
      // If afterSnapshot has empty string and actual is absent, that's equivalent
      if (snapshotVal.isEmpty() && (actualField.isMissingNode() || actualField.isNull())) {
        return;
      }
      assertThat(snapshotVal)
          .as(
              "Parity mismatch for field '%s' in patch '%s': afterSnapshot='%s', actual='%s'",
              fieldToCompare, description, snapshotVal, actualVal)
          .isEqualTo(actualVal);
    } else {
      assertThat(snapshotField.asText(""))
          .as("Parity mismatch for field '%s' in patch '%s'", fieldToCompare, description)
          .isEqualTo(actualField.asText(""));
    }
  }

  /**
   * Restores the test entity to its clean baseline state (original description, no tier, no
   * displayName, original tags, original owners, original domains, original extension). Catches
   * exceptions to prevent cascading failures.
   */
  private void restoreBaseline() {
    try {
      JsonNode current = getEntityFromServer();
      String currentDesc = current.path("description").asText("");
      Set<String> currentTags = extractTagFqns(current);
      Set<String> currentOwnerIds = extractEntityRefIds(current.path("owners"));
      Set<String> currentDomainIds = extractEntityRefIds(current.path("domains"));

      List<String> restorePatches = new ArrayList<>();

      // Restore description
      if (!originalDescription.equals(currentDesc)) {
        restorePatches.add(
            String.format(
                "{\"op\":\"replace\",\"path\":\"/description\",\"value\":%s}",
                MAPPER.writeValueAsString(originalDescription)));
      }

      // Remove tier if present
      if (!current.path("tier").isMissingNode() && !current.path("tier").isNull()) {
        restorePatches.add("{\"op\":\"remove\",\"path\":\"/tier\"}");
      }

      // Remove displayName if present
      if (!current.path("displayName").isMissingNode()
          && !current.path("displayName").isNull()
          && !current.path("displayName").asText("").isEmpty()) {
        restorePatches.add("{\"op\":\"remove\",\"path\":\"/displayName\"}");
      }

      // Restore retentionPeriod
      String currentRetention = current.path("retentionPeriod").asText("");
      String origRetention = originalRetentionPeriod != null ? originalRetentionPeriod : "";
      if (!currentRetention.equals(origRetention)) {
        if (origRetention.isEmpty()) {
          restorePatches.add("{\"op\":\"remove\",\"path\":\"/retentionPeriod\"}");
        } else {
          restorePatches.add(
              String.format(
                  "{\"op\":\"replace\",\"path\":\"/retentionPeriod\",\"value\":%s}",
                  MAPPER.writeValueAsString(origRetention)));
        }
      }

      // Restore sourceUrl
      String currentSourceUrl = current.path("sourceUrl").asText("");
      String origSourceUrl = originalSourceUrl != null ? originalSourceUrl : "";
      if (!currentSourceUrl.equals(origSourceUrl)) {
        if (origSourceUrl.isEmpty()) {
          restorePatches.add("{\"op\":\"remove\",\"path\":\"/sourceUrl\"}");
        } else {
          restorePatches.add(
              String.format(
                  "{\"op\":\"replace\",\"path\":\"/sourceUrl\",\"value\":%s}",
                  MAPPER.writeValueAsString(origSourceUrl)));
        }
      }

      // Restore schemaDefinition
      String currentSchemaDef = current.path("schemaDefinition").asText("");
      String origSchemaDef = originalSchemaDefinition != null ? originalSchemaDefinition : "";
      if (!currentSchemaDef.equals(origSchemaDef)) {
        if (origSchemaDef.isEmpty()) {
          restorePatches.add("{\"op\":\"remove\",\"path\":\"/schemaDefinition\"}");
        } else {
          restorePatches.add(
              String.format(
                  "{\"op\":\"replace\",\"path\":\"/schemaDefinition\",\"value\":%s}",
                  MAPPER.writeValueAsString(origSchemaDef)));
        }
      }

      // Remove extra tags by replacing the entire tags array with the original set
      if (!currentTags.equals(originalTagFqns)) {
        JsonNode originalTagsNode = originalEntityState.path("tags");
        restorePatches.add(
            String.format(
                "{\"op\":\"replace\",\"path\":\"/tags\",\"value\":%s}",
                originalTagsNode.isMissingNode() ? "[]" : originalTagsNode.toString()));
      }

      // Restore owners to original state
      if (!currentOwnerIds.equals(originalOwnerIds)) {
        JsonNode originalOwnersNode = originalEntityState.path("owners");
        restorePatches.add(
            String.format(
                "{\"op\":\"replace\",\"path\":\"/owners\",\"value\":%s}",
                originalOwnersNode.isMissingNode() || originalOwnersNode.isNull()
                    ? "null"
                    : originalOwnersNode.toString()));
      }

      // Restore domains to original state
      if (!currentDomainIds.equals(originalDomainIds)) {
        JsonNode originalDomainsNode = originalEntityState.path("domains");
        restorePatches.add(
            String.format(
                "{\"op\":\"replace\",\"path\":\"/domains\",\"value\":%s}",
                originalDomainsNode.isMissingNode() || originalDomainsNode.isNull()
                    ? "null"
                    : originalDomainsNode.toString()));
      }

      // Restore extension to original state (remove any added keys, restore removed ones)
      JsonNode currentExtension = current.path("extension");
      JsonNode originalExtension = originalEntityState.path("extension");
      if (!currentExtension.equals(originalExtension)) {
        // Remove added keys that weren't in original
        if (currentExtension.isObject()) {
          currentExtension
              .fields()
              .forEachRemaining(
                  entry -> {
                    if (!originalExtension.has(entry.getKey())) {
                      restorePatches.add(
                          String.format(
                              "{\"op\":\"remove\",\"path\":\"/extension/%s\"}", entry.getKey()));
                    }
                  });
        }
      }

      if (!restorePatches.isEmpty()) {
        String restorePatch = "[" + String.join(",", restorePatches) + "]";
        Map<String, Object> patchCall =
            McpTestUtils.createPatchEntityToolCall("table", testTableFqn, restorePatch);
        executeToolCall(patchCall);
      }
    } catch (Exception e) {
      LOG.warn("Failed to restore baseline: {}", e.getMessage());
    }
  }
}
