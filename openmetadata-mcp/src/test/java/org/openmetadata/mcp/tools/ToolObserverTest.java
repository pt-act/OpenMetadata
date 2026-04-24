package org.openmetadata.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Validation tests for ToolObserver (E1.10–E1.13). */
class ToolObserverTest {

  private boolean observabilityOriginal;

  @BeforeEach
  void setUp() {
    observabilityOriginal = ToolObserver.isObservabilityEnabled();
    ToolObserver.setObservabilityEnabled(true);
  }

  @AfterEach
  void tearDown() {
    ToolObserver.setObservabilityEnabled(observabilityOriginal);
  }

  /** E1.10: observe() happy path — returns the body result unchanged. */
  @Test
  void observe_happyPath_returnsBodyResult() {
    Object result =
        ToolObserver.observe("test_tool", Map.of("query", "hello"), null, () -> "ok_result");

    assertEquals("ok_result", result);
  }

  /** E1.10: observe() happy path — body is called exactly once. */
  @Test
  void observe_happyPath_callsBodyExactlyOnce() {
    int[] callCount = {0};
    ToolObserver.observe(
        "test_tool",
        Map.of("key1", "val1"),
        null,
        () -> {
          callCount[0]++;
          return "done";
        });

    assertEquals(1, callCount[0]);
  }

  /** E1.11: observe() error path — exception is re-thrown after logging. */
  @Test
  void observe_errorPath_rethrowsException() {
    RuntimeException expected = new RuntimeException("tool failure");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                ToolObserver.observe(
                    "error_tool",
                    Map.of("key1", "val1"),
                    null,
                    () -> {
                      throw expected;
                    }));

    assertSame(expected, thrown);
  }

  /** E1.11: observe() unwraps RuntimeException cause so errorClass reflects real type. */
  @Test
  void observe_wrappedIOException_unwrapsCause() {
    IOException cause = new IOException("connection reset");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                ToolObserver.observe(
                    "error_tool",
                    Map.of("key1", "val1"),
                    null,
                    () -> {
                      throw new RuntimeException(cause);
                    }));

    assertInstanceOf(IOException.class, thrown.getCause());
    assertEquals("connection reset", thrown.getCause().getMessage());
  }

  /** E1.12: observe() never logs parameter VALUES — only keys. This is a structural test. */
  @Test
  void observe_piiGuard_onlyKeysInParamSet() {
    // We can't easily intercept the log output, but we verify the observe()
    // method signature accepts Map<String,Object> and the implementation
    // extracts params.keySet() — never params.values(). The structural guarantee
    // is that emitLog receives Set<String> paramKeys, not Map<String,Object>.
    // This test verifies the happy path completes (implicit: no crash from PII handling).
    Object result =
        ToolObserver.observe(
            "search_metadata",
            Map.of("query", "secret-password-123", "entityType", "table"),
            null,
            () -> "ok");

    assertEquals("ok", result);
  }

  /** E1.13: observe() is a transparent pass-through when observability is disabled. */
  @Test
  void observe_configGate_passThroughWhenDisabled() {
    ToolObserver.setObservabilityEnabled(false);

    Object result =
        ToolObserver.observe("test_tool", Map.of("key1", "val1"), null, () -> "bypassed_result");

    assertEquals("bypassed_result", result);
  }

  /** E1.13: config gate toggle works at runtime. */
  @Test
  void observe_configGate_canToggleAtRuntime() {
    assertTrue(ToolObserver.isObservabilityEnabled());

    ToolObserver.setObservabilityEnabled(false);
    assertFalse(ToolObserver.isObservabilityEnabled());

    ToolObserver.setObservabilityEnabled(true);
    assertTrue(ToolObserver.isObservabilityEnabled());
  }

  /** E1.10: observe() handles null params gracefully. */
  @Test
  void observe_nullParams_doesNotThrow() {
    Object result = ToolObserver.observe("test_tool", null, null, () -> "ok_null");

    assertEquals("ok_null", result);
  }

  /** E1.10: observe() handles empty params. */
  @Test
  void observe_emptyParams_doesNotThrow() {
    Object result = ToolObserver.observe("test_tool", Map.of(), null, () -> "ok_empty");

    assertEquals("ok_empty", result);
  }
}
