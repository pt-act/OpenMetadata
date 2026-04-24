package org.openmetadata.mcp.tools;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured observability wrapper for MCP tool invocations.
 *
 * <p>Emits exactly one JSON log line per tool call tagged {@code mcp.tool_call}, containing
 * metadata keys but never parameter values (PII guard). When {@code mcp.observability.enabled}
 * is false, the wrapper becomes a transparent pass-through.
 *
 * <p>Replaces the ad-hoc logging from Fixes spec F7 with a formal, config-gated observer that
 * all Expansions tools depend on.
 */
public final class ToolObserver {

  private static final Logger LOG = LoggerFactory.getLogger(ToolObserver.class);

  /** Config key for observability gating. Read from MCPConfiguration at call time. */
  private static volatile boolean observabilityEnabled = true;

  private ToolObserver() {}

  /** Allows runtime toggle of observability. Called when MCPConfiguration is loaded/updated. */
  public static void setObservabilityEnabled(boolean enabled) {
    observabilityEnabled = enabled;
  }

  /** Returns whether observability is currently enabled. */
  public static boolean isObservabilityEnabled() {
    return observabilityEnabled;
  }

  /**
   * Wraps a tool execution body with structured logging.
   *
   * <p>Log format (one JSON line per call):
   *
   * <pre>{@code
   * {
   *   "ts": "2026-04-23T10:15:30.00Z",
   *   "mcp.tool_call": true,
   *   "tool": "search_metadata",
   *   "paramKeys": ["query","entityType","size"],
   *   "outcome": "ok",
   *   "durationMs": 142,
   *   "userId": "admin"
   * }
   * }</pre>
   *
   * <p>Error case adds {@code errorClass} and {@code errorMessage}. Parameter values are NEVER
   * logged — only keys.
   *
   * @param toolName the MCP tool name being invoked
   * @param params the tool parameter map (only keys are logged, never values)
   * @param securityContext the caller's security context (used for userId)
   * @param body the tool execution body; called exactly once
   * @return the result of {@code body.get()}
   * @throws RuntimeException if body throws, the exception is re-thrown unchanged after logging
   */
  public static Object observe(
      String toolName,
      Map<String, Object> params,
      CatalogSecurityContext securityContext,
      Supplier<Object> body) {

    if (!observabilityEnabled) {
      return body.get();
    }

    Set<String> paramKeys = params != null ? params.keySet() : Set.of();
    String userId = resolveUserId(securityContext);
    long startMs = System.currentTimeMillis();

    try {
      Object result = body.get();
      long durationMs = System.currentTimeMillis() - startMs;
      emitLog(toolName, paramKeys, userId, "ok", durationMs, null, null);
      return result;
    } catch (RuntimeException ex) {
      long durationMs = System.currentTimeMillis() - startMs;
      // Unwrap RuntimeException cause (e.g. IOException wrapped by lambda) so errorClass
      // reflects the real exception type, not the wrapper
      Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
      emitLog(
          toolName,
          paramKeys,
          userId,
          "error",
          durationMs,
          cause.getClass().getSimpleName(),
          truncateMessage(cause.getMessage()));
      throw ex;
    } catch (Exception ex) {
      long durationMs = System.currentTimeMillis() - startMs;
      emitLog(
          toolName,
          paramKeys,
          userId,
          "error",
          durationMs,
          ex.getClass().getSimpleName(),
          truncateMessage(ex.getMessage()));
      throw ex;
    }
  }

  /**
   * Emits a single structured JSON log line. Uses {@code log.info} so that production deployments
   * can route {@code mcp.tool_call} lines to a dedicated log appender if desired.
   */
  private static void emitLog(
      String toolName,
      Set<String> paramKeys,
      String userId,
      String outcome,
      long durationMs,
      String errorClass,
      String errorMessage) {

    Map<String, Object> record = new java.util.LinkedHashMap<>();
    record.put("ts", Instant.now().toString());
    record.put("mcp.tool_call", true);
    record.put("tool", toolName);
    record.put("paramKeys", paramKeys);
    record.put("outcome", outcome);
    record.put("durationMs", durationMs);
    record.put("userId", userId);

    if (errorClass != null) {
      record.put("errorClass", errorClass);
    }
    if (errorMessage != null) {
      record.put("errorMessage", errorMessage);
    }

    LOG.info(JsonUtils.pojoToJson(record));
  }

  private static String resolveUserId(CatalogSecurityContext securityContext) {
    if (securityContext != null && securityContext.getUserPrincipal() != null) {
      return securityContext.getUserPrincipal().getName();
    }
    return "anonymous";
  }

  /** Truncates error messages to 500 characters to prevent log bloat. */
  private static String truncateMessage(String message) {
    if (message == null) return null;
    return message.length() > 500 ? message.substring(0, 497) + "..." : message;
  }
}
