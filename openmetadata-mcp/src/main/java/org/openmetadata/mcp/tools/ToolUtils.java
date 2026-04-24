package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.exception.EntityNotFoundException;

/**
 * Shared utility methods for MCP tools. Centralizes common parameter parsing and validation
 * logic so that individual tools stay focused on their domain.
 *
 * <p>Designed for extensibility: the resolution order in {@link #resolveFqn} is an explicit
 * list so that additional key strategies can be inserted without changing call sites. The
 * {@link #resolveEntityRef} method extends this with id, entityLink, and name+service support.
 */
public final class ToolUtils {

  /** Ordered resolution strategies for entity FQN lookup. Extensible for future forms. */
  private static final List<String> FQN_RESOLUTION_ORDER = List.of("fqn", "fullyQualifiedName");

  private ToolUtils() {}

  /**
   * Resolves the fully qualified name from tool parameters, accepting multiple alias keys.
   *
   * <p>Resolution order: {@code fqn} → {@code fullyQualifiedName} → throw. This order ensures
   * backward compatibility (existing {@code fqn} callers are unaffected) while allowing MCP
   * clients that receive {@code fullyQualifiedName} from search tools to pass it directly.
   *
   * <p>Empty and blank strings are treated as absent, preventing silent null failures.
   *
   * @param params the tool parameter map
   * @return the resolved FQN (non-blank)
   * @throws IllegalArgumentException if none of the accepted keys are present or all are blank
   */
  public static String resolveFqn(Map<String, Object> params) {
    for (String key : FQN_RESOLUTION_ORDER) {
      Object value = params.get(key);
      if (value instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    throw new IllegalArgumentException(
        "Parameter 'fqn' (or 'fullyQualifiedName') is required and cannot be empty");
  }

  /**
   * Resolves an entity reference from tool parameters, supporting five input forms.
   *
   * <p>Resolution order (per Expansions spec R1.3):
   * <ol>
   *   <li>{@code fqn} / {@code fullyQualifiedName} — resolved via {@link #resolveFqn}</li>
   *   <li>{@code id} — UUID lookup via {@link Entity#getEntityReferenceById}</li>
   *   <li>{@code entityLink} — Markdown link parse via {@link #parseEntityLink}</li>
   *   <li>{@code name} + {@code service} (+ {@code database} + {@code schema}) — composite
   *       FQN construction and lookup</li>
   * </ol>
   *
   * <p>If none resolve, throws {@link IllegalArgumentException} naming the keys attempted.
   *
   * @param params the tool parameter map
   * @param entityType the expected entity type (e.g. "table", "database"); used for id lookup
   *     and composite FQN construction. Must not be null.
   * @return a canonical {@link EntityReference} for the resolved entity
   * @throws IllegalArgumentException if no input form resolves or entityType is null
   */
  public static EntityReference resolveEntityRef(Map<String, Object> params, String entityType) {
    return resolveEntityRef(params, entityType, McpEntityBridge.defaultEntityReferenceResolver());
  }

  /**
   * Test-friendly overload — accepts an {@link McpEntityBridge.EntityReferenceResolver} for
   * dependency injection. Tests can inject a lambda that returns stub references without needing
   * {@code mockStatic(Entity.class)}.
   *
   * <p>Resolution order (per Expansions spec R1.3):
   * <ol>
   *   <li>{@code fqn} / {@code fullyQualifiedName} — resolved via {@link #resolveFqn}</li>
   *   <li>{@code id} — UUID lookup via {@link Entity#getEntityReferenceById}</li>
   *   <li>{@code entityLink} — Markdown link parse via {@link #parseEntityLink}</li>
   *   <li>{@code name} + {@code service} (+ {@code database} + {@code schema}) — composite
   *       FQN construction and lookup</li>
   * </ol>
   *
   * <p>If none resolve, throws {@link IllegalArgumentException} naming the keys attempted.
   */
  @VisibleForTesting
  public static EntityReference resolveEntityRef(
      Map<String, Object> params,
      String entityType,
      McpEntityBridge.EntityReferenceResolver referenceResolver) {
    if (entityType == null || entityType.isBlank()) {
      throw new IllegalArgumentException("entityType is required for resolveEntityRef");
    }

    // Strategy 1: fqn / fullyQualifiedName — delegates to resolveFqn for backward compat
    boolean fqnPresent = hasFqnKey(params);
    if (fqnPresent) {
      String fqn = resolveFqn(params);
      try {
        EntityReference ref =
            referenceResolver.getEntityReferenceByName(entityType, fqn, Include.NON_DELETED);
        if (ref != null) return ref;
      } catch (EntityNotFoundException ex) {
        throw new IllegalArgumentException(
            String.format(
                "Entity '%s' of type '%s' not found (resolved via fqn/fullyQualifiedName). %s",
                fqn, entityType, ex.getMessage()));
      }
    }

    // Strategy 2: id — UUID lookup via the injected referenceResolver
    Object idValue = params.get("id");
    if (idValue != null) {
      try {
        UUID uuid =
            idValue instanceof UUID ? (UUID) idValue : UUID.fromString(idValue.toString().trim());
        EntityReference ref =
            referenceResolver.getEntityReferenceById(entityType, uuid, Include.NON_DELETED);
        if (ref != null) return ref;
      } catch (EntityNotFoundException ex) {
        throw new IllegalArgumentException(
            String.format("Entity of type '%s' with id '%s' not found.", entityType, idValue));
      } catch (IllegalArgumentException ex) {
        // Not a valid UUID format — fall through to next strategy
      }
    }

    // Strategy 3: entityLink — Markdown link parse (e.g. <#E::table::svc.db.s.t>)
    Object linkValue = params.get("entityLink");
    if (linkValue instanceof String linkStr && !linkStr.isBlank()) {
      ParsedEntityLink parsed = parseEntityLink(linkStr);
      if (parsed != null && parsed.entityType != null && parsed.fqn != null) {
        String lookupType =
            parsed.entityType.equalsIgnoreCase(entityType) ? entityType : parsed.entityType;
        try {
          EntityReference ref =
              referenceResolver.getEntityReferenceByName(
                  lookupType, parsed.fqn, Include.NON_DELETED);
          if (ref != null) return ref;
        } catch (EntityNotFoundException ex) {
          throw new IllegalArgumentException(
              String.format(
                  "Entity '%s' of type '%s' not found (resolved via entityLink). %s",
                  parsed.fqn, lookupType, ex.getMessage()));
        }
      }
    }

    // Strategy 4: name + service (+ database + schema) — composite FQN construction
    Object nameValue = params.get("name");
    Object serviceValue = params.get("service");
    if (nameValue instanceof String name && !name.isBlank()) {
      String compositeFqn =
          buildCompositeFqn(name, serviceValue, params.get("database"), params.get("schema"));
      try {
        EntityReference ref =
            referenceResolver.getEntityReferenceByName(
                entityType, compositeFqn, Include.NON_DELETED);
        if (ref != null) return ref;
      } catch (EntityNotFoundException ex) {
        throw new IllegalArgumentException(
            String.format(
                "Entity '%s' of type '%s' not found (resolved via name+service). %s",
                compositeFqn, entityType, ex.getMessage()));
      }
    }

    // None resolved — structured error naming keys attempted
    throw new IllegalArgumentException(
        String.format(
            "Could not resolve entity reference. "
                + "Provide one of: fqn/fullyQualifiedName (string), id (UUID), "
                + "entityLink (<#E::type::fqn>), or name+service (+database+schema)."));
  }

  /** Returns true if any FQN-related key is present and non-blank in params. */
  private static boolean hasFqnKey(Map<String, Object> params) {
    for (String key : FQN_RESOLUTION_ORDER) {
      Object value = params.get(key);
      if (value instanceof String s && !s.isBlank()) return true;
    }
    return false;
  }

  /**
   * Parses an OpenMetadata Markdown entity link string.
   *
   * <p>Entity link format: {@code <#E::entityType::fqn[:field[:arrayField[:arrayValue]]]>}
   *
   * <p>Example: {@code <#E::table::svc.db.schema.table>} → entityType="table", fqn="svc.db.schema.table"
   *
   * <p>Example: {@code <#E::table::svc.db.schema.table::columns::col_name>} → entityType="table",
   * fqn="svc.db.schema.table", field="columns", arrayField="col_name"
   *
   * @param link the entity link string
   * @return parsed components, or null if the string is not a valid entity link
   * @throws IllegalArgumentException if the link format is invalid
   */
  public static ParsedEntityLink parseEntityLink(String link) {
    if (link == null || link.isBlank()) {
      return null;
    }

    String trimmed = link.trim();
    if (!trimmed.startsWith("<#E::") || !trimmed.endsWith(">")) {
      throw new IllegalArgumentException(
          "Invalid entityLink format. Expected <#E::type::fqn[:field[:arrayField[:arrayValue]]]>, got: "
              + trimmed);
    }

    // Strip prefix <#E:: and suffix >
    String content = trimmed.substring(5, trimmed.length() - 1);

    // Split by :: — parts[0] = entityType, parts[1] = fqn, parts[2+] = field/arrayField/arrayValue
    String[] parts = content.split("::", -1);
    if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw new IllegalArgumentException(
          "Invalid entityLink format. Expected <#E::type::fqn>, got: " + trimmed);
    }

    ParsedEntityLink result = new ParsedEntityLink();
    result.entityType = parts[0];
    result.fqn = parts[1];
    if (parts.length > 2) {
      result.field = parts[2];
    }
    if (parts.length > 3) {
      result.arrayField = parts[3];
    }
    if (parts.length > 4) {
      result.arrayValue = parts[4];
    }
    return result;
  }

  /**
   * Builds a composite FQN from name, service, database, and schema parts.
   *
   * <p>Concatenates all non-blank parts in order: service.database.schema.name.
   * Callers should only provide the parts relevant to their entity type; for example,
   * a "database" entity would pass name and service but not database or schema.
   */
  private static String buildCompositeFqn(
      Object name, Object service, Object database, Object schema) {
    StringBuilder fqn = new StringBuilder();

    if (service instanceof String s && !s.isBlank()) {
      fqn.append(s);
    }

    if (database instanceof String db && !db.isBlank()) {
      if (fqn.length() > 0) fqn.append(".");
      fqn.append(db);
    }

    if (schema instanceof String sc && !sc.isBlank()) {
      if (fqn.length() > 0) fqn.append(".");
      fqn.append(sc);
    }

    if (name instanceof String n && !n.isBlank()) {
      if (fqn.length() > 0) fqn.append(".");
      fqn.append(n);
    }

    return fqn.toString();
  }

  /**
   * Parsed components of an OpenMetadata entity link string.
   *
   * <p>Format: {@code <#E::entityType::fqn[:field[:arrayField[:arrayValue]]]>}
   */
  public static class ParsedEntityLink {
    public String entityType;
    public String fqn;
    public String field;
    public String arrayField;
    public String arrayValue;
  }
}
