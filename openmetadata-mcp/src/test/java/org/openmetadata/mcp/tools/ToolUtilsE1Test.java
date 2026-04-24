package org.openmetadata.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Validation tests for ToolUtils resolveEntityRef() and parseEntityLink() (E1.14–E1.15). */
class ToolUtilsE1Test {

  // --- parseEntityLink tests (E1.14) ---

  @Test
  void parseEntityLink_simpleTableLink() {
    ToolUtils.ParsedEntityLink parsed = ToolUtils.parseEntityLink("<#E::table::svc.db.s.t>");

    assertNotNull(parsed);
    assertEquals("table", parsed.entityType);
    assertEquals("svc.db.s.t", parsed.fqn);
    assertNull(parsed.field);
  }

  @Test
  void parseEntityLink_withFieldAndArray() {
    ToolUtils.ParsedEntityLink parsed =
        ToolUtils.parseEntityLink("<#E::table::svc.db.s.t::columns::col1>");

    assertNotNull(parsed);
    assertEquals("table", parsed.entityType);
    assertEquals("svc.db.s.t", parsed.fqn);
    assertEquals("columns", parsed.field);
    assertEquals("col1", parsed.arrayField);
  }

  @Test
  void parseEntityLink_withFullFiveParts() {
    ToolUtils.ParsedEntityLink parsed =
        ToolUtils.parseEntityLink("<#E::table::svc.db.s.t::columns::col1::arrVal>");

    assertNotNull(parsed);
    assertEquals("table", parsed.entityType);
    assertEquals("svc.db.s.t", parsed.fqn);
    assertEquals("columns", parsed.field);
    assertEquals("col1", parsed.arrayField);
    assertEquals("arrVal", parsed.arrayValue);
  }

  @Test
  void parseEntityLink_nullInput_returnsNull() {
    assertNull(ToolUtils.parseEntityLink(null));
  }

  @Test
  void parseEntityLink_blankInput_returnsNull() {
    assertNull(ToolUtils.parseEntityLink("   "));
  }

  @Test
  void parseEntityLink_invalidFormat_throwsException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.parseEntityLink("not-a-valid-link"));

    assertTrue(ex.getMessage().contains("Invalid entityLink format"));
  }

  @Test
  void parseEntityLink_missingType_throwsException() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.parseEntityLink("<#E::::svc.db.s.t>"));

    assertTrue(ex.getMessage().contains("Invalid entityLink format"));
  }

  // --- resolveEntityRef tests (E1.15) ---

  @Test
  void resolveEntityRef_nullEntityType_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ToolUtils.resolveEntityRef(Map.of("fqn", "test"), null));

    assertTrue(ex.getMessage().contains("entityType is required"));
  }

  @Test
  void resolveEntityRef_blankEntityType_throws() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ToolUtils.resolveEntityRef(Map.of("fqn", "test"), "  "));

    assertTrue(ex.getMessage().contains("entityType is required"));
  }

  @Test
  void resolveEntityRef_noMatchingKeys_throwsStructuredError() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ToolUtils.resolveEntityRef(Map.of("unknown", "value"), "table"));

    assertTrue(ex.getMessage().contains("Could not resolve entity reference"));
    assertTrue(ex.getMessage().contains("Provide one of"));
  }

  @Test
  void resolveEntityRef_fqnRecognized_reportsNotFoundWithContext() {
    // When fqn is present but entity doesn't exist, the error should say
    // "Entity X not found (resolved via fqn)" — NOT the generic
    // "Could not resolve entity reference" which means no key was provided.
    Map<String, Object> params = Map.of("fqn", "some.table.fqn");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.resolveEntityRef(params, "table"));
    // The key WAS recognized — error should mention the specific fqn, not generic message
    assertTrue(
        ex.getMessage().contains("some.table.fqn"),
        "Expected specific entity-not-found error, got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("not found"),
        "Expected 'not found' in error, got: " + ex.getMessage());
  }

  @Test
  void resolveEntityRef_idRecognized_reportsNotFound() {
    UUID testId = UUID.randomUUID();
    Map<String, Object> params = Map.of("id", testId.toString());
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.resolveEntityRef(params, "table"));
    // The id key WAS recognized — error should mention the specific id
    assertTrue(
        ex.getMessage().contains(testId.toString()),
        "Expected specific entity-not-found error with id, got: " + ex.getMessage());
  }

  @Test
  void resolveEntityRef_entityLinkRecognized_reportsNotFound() {
    Map<String, Object> params = Map.of("entityLink", "<#E::table::svc.db.s.t>");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.resolveEntityRef(params, "table"));
    // The entityLink key WAS recognized — error should mention the fqn from the link
    assertTrue(
        ex.getMessage().contains("svc.db.s.t"),
        "Expected specific entity-not-found error from entityLink, got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("entityLink"),
        "Expected 'entityLink' in error, got: " + ex.getMessage());
  }

  @Test
  void resolveEntityRef_nameAndServiceRecognized_reportsNotFound() {
    Map<String, Object> params = Map.of("name", "myTable", "service", "postgres");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.resolveEntityRef(params, "table"));
    // The name+service keys WERE recognized — error should mention the composite fqn
    assertTrue(
        ex.getMessage().contains("postgres.myTable"),
        "Expected specific entity-not-found error with composite FQN, got: " + ex.getMessage());
    assertTrue(
        ex.getMessage().contains("name+service"),
        "Expected 'name+service' in error, got: " + ex.getMessage());
  }

  // --- resolveFqn tests (existing behavior, regression) ---

  @Test
  void resolveFqn_prefersFqnOverFullyQualifiedName() {
    Map<String, Object> params = Map.of("fqn", "a.b.c", "fullyQualifiedName", "x.y.z");
    assertEquals("a.b.c", ToolUtils.resolveFqn(params));
  }

  @Test
  void resolveFqn_fallsBackToFullyQualifiedName() {
    Map<String, Object> params = Map.of("fullyQualifiedName", "x.y.z");
    assertEquals("x.y.z", ToolUtils.resolveFqn(params));
  }

  @Test
  void resolveFqn_throwsWhenNeitherPresent() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> ToolUtils.resolveFqn(Map.of("name", "foo")));

    assertTrue(ex.getMessage().contains("Parameter 'fqn'"));
  }

  @Test
  void resolveFqn_ignoresBlankStrings() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ToolUtils.resolveFqn(Map.of("fqn", "   ", "fullyQualifiedName", "")));

    assertTrue(ex.getMessage().contains("cannot be empty"));
  }
}
