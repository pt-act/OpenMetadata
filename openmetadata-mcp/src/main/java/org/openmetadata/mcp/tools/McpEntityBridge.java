package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.ChangeEventRepository;
import org.openmetadata.service.jdbi3.EntityRepository;
import org.openmetadata.service.jdbi3.EntityTimeSeriesRepository;
import org.openmetadata.service.jdbi3.LineageRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.CreateResourceContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/**
 * Shared functional interfaces and factory methods for decoupling MCP tools from {@link Entity}
 * static method calls.
 *
 * <h3>Problem</h3>
 *
 * <p>Most MCP tools call static methods on {@link Entity} (e.g. {@code getEntityRepository()},
 * {@code getSearchRepository()}, {@code getEntityByName()}) which fail at test time because
 * {@code Entity}'s static initializers are not satisfied. Tests currently work around this with
 * {@code mockStatic(Entity.class)}, which is verbose, fragile, and hides accidental calls to
 * unmocked Entity methods.
 *
 * <h3>Solution</h3>
 *
 * <p>Each functional interface in this class wraps one category of {@link Entity} access. Tools
 * provide a production overload that creates the default implementation (delegating to {@link
 * Entity}), and a test-friendly overload that accepts injected interfaces. Tests inject no-op or
 * capturing lambdas, eliminating the need for {@code mockStatic(Entity.class)}.
 *
 * <h3>Usage pattern</h3>
 *
 * <pre>{@code
 * // Production call — uses default Entity delegation
 * public Map<String, Object> execute(Authorizer auth, CatalogSecurityContext ctx, Map<String, Object> params) {
 *   return execute(params, McpEntityBridge.defaultEntityFetcher(), McpEntityBridge.defaultAuthorizer(auth, ctx));
 * }
 *
 * // Test-friendly overload — accepts injected interfaces
 * @VisibleForTesting
 * Map<String, Object> execute(Map<String, Object> params, EntityFetcher fetcher, McpAuthorizer authorizer) {
 *   EntityReference ref = fetcher.getEntityReferenceByName("table", "db.schema.t", Include.NON_DELETED);
 *   authorizer.authorize("table", MetadataOperation.VIEW_ALL);
 *   Object entity = fetcher.getEntityByName("table", "db.schema.t", "*", null);
 *   ...
 * }
 *
 * // Test — no mockStatic needed
 * Map<String, Object> result = tool.execute(params, (type, fqn, inc) -> mockRef, (type, op) -> {});
 * }</pre>
 *
 * <h3>Interfaces</h3>
 *
 * <ul>
 *   <li>{@link McpAuthorizer} — authorization check (bypasses ResourceContext/OperationContext
 *       construction which requires Entity.getEntityRepository())
 *   <li>{@link EntityFetcher} — all Entity lookups (getEntityByName, getEntityReferenceByName,
 *       getEntity, etc.)
 *   <li>{@link RepositoryProvider} — typed repository access (getEntityRepository,
 *       getLineageRepository, getSearchRepository, etc.)
 * </ul>
 */
public final class McpEntityBridge {

  private McpEntityBridge() {}

  // ═══════════════════════════════════════════════════════════════════════════
  // Functional Interfaces
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Authorization check for MCP tool operations.
   *
   * <p>Production implementation constructs {@link OperationContext}/{@link ResourceContext} and
   * delegates to {@link Authorizer#authorize}. Tests inject a no-op {@code (entityType, op) -> {}}
   * to bypass {@code ResourceContext} construction, which requires {@code
   * Entity.getEntityRepository()} to be initialized.
   *
   * @param entityType the entity type being operated on (e.g. "table", "glossary")
   * @param operation the metadata operation being performed
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface McpAuthorizer {
    /**
     * Authorizes an operation on an entity type. Tests inject a no-op {@code (entityType, op)
     * -> {}}.
     *
     * @throws RuntimeException if authorization fails (wraps checked exceptions from the real
     *     authorizer)
     */
    void authorize(String entityType, MetadataOperation operation);
  }

  /**
   * Functional interface for Entity lookups. Wraps all static methods on {@link Entity} that
   * retrieve entities by name, reference, or ID.
   *
   * <p>Returns {@code Object} rather than a generic {@code <T>} because callers always pass the
   * result to {@code JsonUtils.getMap(Object)} — the generic type parameter is never used and
   * causes lambda type-inference failures in tests.
   *
   * <p>Production implementation delegates to {@link Entity} static methods. Tests inject
   * stubs/capturing lambdas to avoid {@code mockStatic(Entity.class)}.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface EntityFetcher {
    /**
     * Gets an entity by its type, name (FQN), fields, and include scope. Matches the most common
     * {@link Entity#getEntityByName} signature.
     */
    Object getEntityByName(String entityType, String fqn, String fields, Include include);
  }

  /**
   * Functional interface for resolving entity references by name. Separated from {@link
   * EntityFetcher} because many tools only need the reference (not the full entity).
   *
   * <p>Includes a {@link #getEntityReferenceById} default method for id-based lookups so that
   * {@link ToolUtils#resolveEntityRef} can fully delegate to the resolver without calling {@link
   * Entity} directly. Tests can override the default if they need to mock id-based lookups.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface EntityReferenceResolver {
    EntityReference getEntityReferenceByName(String entityType, String fqn, Include include);

    /**
     * Looks up an entity reference by ID. Default implementation delegates to {@link
     * Entity#getEntityReferenceById}. Override in tests to avoid {@code mockStatic(Entity.class)}
     * for id-based resolution paths.
     */
    default EntityReference getEntityReferenceById(
        String entityType, java.util.UUID id, Include include) {
      return Entity.getEntityReferenceById(entityType, id, include);
    }
  }

  /**
   * Functional interface for accessing typed repositories. Wraps all {@code
   * Entity.getXxxRepository()} static methods.
   *
   * <p>Production implementation delegates to {@link Entity} static methods. Tests inject lambdas
   * that return mock repositories.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface RepositoryProvider {
    EntityRepository<?> getEntityRepository(String entityType);
  }

  /**
   * Functional interface for accessing the search repository. Separated from {@link
   * RepositoryProvider} because the search repository is not an {@link EntityRepository}.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface SearchRepositoryProvider {
    SearchRepository getSearchRepository();
  }

  /**
   * Functional interface for accessing the lineage repository. Separated because it's used
   * independently of the entity repository in several tools.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface LineageRepositoryProvider {
    LineageRepository getLineageRepository();
  }

  /**
   * Functional interface for accessing the change event repository.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface ChangeEventRepositoryProvider {
    ChangeEventRepository getChangeEventRepository();
  }

  /**
   * Functional interface for authorizing and validating a CREATE operation. Wraps the combined
   * {@code limits.enforceLimits + authorizer.authorize} call that requires a {@link
   * CreateResourceContext}, which internally calls {@code Entity.getEntityRepository()}.
   *
   * <p>Production implementation constructs the {@link CreateResourceContext}, {@link
   * OperationContext}, and delegates to the real {@link Authorizer} and {@link Limits}. Tests
   * inject a no-op {@code (entityType, entity) -> {}} to bypass {@code CreateResourceContext}
   * construction, eliminating the need for {@code mockStatic(Entity.class)}.
   *
   * @param <T> the entity type being created (e.g. {@code Glossary}, {@code GlossaryTerm})
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface CreateOperationAuthorizer<T extends EntityInterface> {
    /**
     * Validates limits and authorizes a CREATE operation for the given entity. Tests inject a
     * no-op.
     *
     * @throws RuntimeException if authorization or limit validation fails
     */
    void authorizeCreate(String entityType, T entity);
  }

  /**
   * Functional interface for publishing change events after entity creation. Wraps {@link
   * McpChangeEventUtil#publishChangeEvent}, which internally calls {@code
   * Entity.getCollectionDAO()}.
   *
   * <p>Production implementation delegates to {@link McpChangeEventUtil#publishChangeEvent}. Tests
   * inject a no-op {@code (entity, changeType, userName) -> {}} to bypass {@code
   * Entity.getCollectionDAO()} entirely, eliminating the last remaining static {@link Entity}
   * dependency in tool code paths.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface ChangeEventPublisher {
    /**
     * Publishes a change event for the given entity. Tests inject a no-op.
     *
     * <p>Uses {@code EntityInterface} rather than a generic {@code <T>} because no caller ever
     * uses the specific type — the entity is always passed through to {@link
     * McpChangeEventUtil#publishChangeEvent}, and a generic method parameter prevents lambda
     * type inference in Java.
     *
     * @param entity the entity that was created/updated
     * @param changeType the type of change event
     * @param userName the user who performed the operation
     */
    void publishChangeEvent(EntityInterface entity, EventType changeType, String userName);
  }

  /**
   * Functional interface for authorizing a PATCH operation. Wraps the {@code
   * authorizer.authorize(securityContext, OperationContext(entityType, jsonPatch),
   * ResourceContext(entityType, null, fqn))} call that requires {@link OperationContext} and
   * {@link ResourceContext} construction.
   *
   * <p>Production implementation constructs {@link OperationContext} from the {@link JsonPatch}
   * and delegates to the real authorizer. Tests inject a no-op {@code (entityType, jsonPatch, fqn)
   * -> {}} to bypass {@code OperationContext}/{@code ResourceContext} construction.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface PatchAuthorizer {
    /**
     * Authorizes a JSON Patch operation on an entity. Tests inject a no-op.
     *
     * @param entityType the entity type being patched
     * @param jsonPatch the JSON Patch operations being applied
     * @param fqn the fully qualified name of the entity being patched
     * @throws RuntimeException if authorization fails
     */
    void authorize(String entityType, jakarta.json.JsonPatch jsonPatch, String fqn);
  }

  /**
   * Functional interface for accessing the entity time-series repository.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface TimeSeriesRepositoryProvider {
    EntityTimeSeriesRepository<?> getEntityTimeSeriesRepository(String entityType);
  }

  /**
   * Functional interface for fetching an entity by {@link EntityReference}. Wraps {@link
   * Entity#getEntity(EntityReference, String, Include)} which is used by tools that need to fetch
   * entity details by reference (e.g. for owner/domain resolution).
   *
   * <p>Production implementation delegates to {@link Entity#getEntity}. Tests inject a lambda
   * that returns a mock or stub map/object.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface EntityByReferenceFetcher {
    /**
     * Gets an entity by its reference, fields, and include scope. Matches {@link
     * Entity#getEntity(EntityReference, String, Include)}.
     */
    Object getEntity(EntityReference entityRef, String fields, Include include);
  }

  /**
   * Functional interface for inserting a change event JSON string into the change_event DAO.
   * Wraps {@code Entity.getCollectionDAO().changeEventDAO().insert(json)} which is the only
   * call site in {@link McpChangeEventUtil} that requires {@code Entity.getCollectionDAO()}.
   *
   * <p>Production implementation delegates to {@link Entity#getCollectionDAO}. Tests inject a
   * lambda that captures the mock DAO, eliminating the need for {@code mockStatic(Entity.class)}.
   */
  /**
   * Functional interface for accessing the OpenSearch vector service instance. Wraps {@code
   * OpenSearchVectorService.getInstance()} which is a static method that cannot be mocked without
   * {@code mockStatic(OpenSearchVectorService.class)}.
   *
   * <p>Production implementation delegates to {@link
   * org.openmetadata.service.search.vector.OpenSearchVectorService#getInstance}. Tests inject a
   * lambda that returns a mock vector service.
   */
  @FunctionalInterface
  @VisibleForTesting
  public interface VectorServiceProvider {
    org.openmetadata.service.search.vector.OpenSearchVectorService getVectorService();
  }

  @FunctionalInterface
  @VisibleForTesting
  public interface ChangeEventDaoInserter {
    void insert(String json);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Default factory methods (production implementations)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Creates a production {@link McpAuthorizer} that constructs {@link OperationContext}/{@link
   * ResourceContext} and delegates to the real authorizer.
   */
  public static McpAuthorizer defaultAuthorizer(
      Authorizer authorizer, CatalogSecurityContext securityContext) {
    return (entityType, operation) -> {
      try {
        authorizer.authorize(
            securityContext,
            new OperationContext(entityType, operation),
            new ResourceContext<>(entityType));
      } catch (RuntimeException e) {
        throw e; // preserve original type (e.g. ForbiddenException)
      } catch (Exception e) {
        throw new RuntimeException("Authorization failed for " + entityType + "/" + operation, e);
      }
    };
  }

  /**
   * Creates a production {@link EntityFetcher} that delegates to {@link Entity} static methods.
   *
   * <p>Uses an explicit lambda instead of {@code Entity::getEntityByName} because the interface
   * returns {@code Object} while the static method returns generic {@code <T> T} — the method
   * reference would fail to compile due to the type erasure mismatch.
   */
  public static EntityFetcher defaultEntityFetcher() {
    return (entityType, fqn, fields, include) ->
        Entity.getEntityByName(entityType, fqn, fields, include);
  }

  /**
   * Creates a production {@link EntityReferenceResolver} that delegates to {@link Entity} static
   * methods.
   */
  public static EntityReferenceResolver defaultEntityReferenceResolver() {
    return Entity::getEntityReferenceByName;
  }

  /** Creates a production {@link RepositoryProvider} that delegates to {@link Entity}. */
  public static RepositoryProvider defaultRepositoryProvider() {
    return Entity::getEntityRepository;
  }

  /** Creates a production {@link SearchRepositoryProvider} that delegates to {@link Entity}. */
  public static SearchRepositoryProvider defaultSearchRepositoryProvider() {
    return Entity::getSearchRepository;
  }

  /** Creates a production {@link LineageRepositoryProvider} that delegates to {@link Entity}. */
  public static LineageRepositoryProvider defaultLineageRepositoryProvider() {
    return Entity::getLineageRepository;
  }

  /** Creates a production {@link ChangeEventRepositoryProvider} that delegates to {@link Entity}. */
  public static ChangeEventRepositoryProvider defaultChangeEventRepositoryProvider() {
    return Entity::getChangeEventRepository;
  }

  /**
   * Creates a production {@link CreateOperationAuthorizer} that constructs {@link
   * CreateResourceContext} and {@link OperationContext}, then delegates to {@link
   * Limits#enforceLimits} and {@link Authorizer#authorize}.
   *
   * <p>This is the only bridge factory that constructs {@code CreateResourceContext}, which is the
   * call site that forces {@code Entity.getEntityRepository()} to be initialized. Tests inject a
   * no-op lambda to bypass this entirely.
   */
  public static <T extends EntityInterface>
      CreateOperationAuthorizer<T> defaultCreateOperationAuthorizer(
          Authorizer authorizer, Limits limits, CatalogSecurityContext securityContext) {
    return (entityType, entity) -> {
      try {
        OperationContext operationContext =
            new OperationContext(entityType, MetadataOperation.CREATE);
        CreateResourceContext<T> createResourceContext =
            new CreateResourceContext<>(entityType, entity);
        limits.enforceLimits(securityContext, createResourceContext, operationContext);
        authorizer.authorize(securityContext, operationContext, createResourceContext);
      } catch (RuntimeException e) {
        throw e; // preserve original type (e.g. ForbiddenException)
      } catch (Exception e) {
        throw new RuntimeException(
            "Authorization/limit validation failed for CREATE " + entityType, e);
      }
    };
  }

  /**
   * Creates a production {@link ChangeEventPublisher} that delegates to {@link
   * McpChangeEventUtil#publishChangeEvent}.
   */
  public static ChangeEventPublisher defaultChangeEventPublisher() {
    return McpChangeEventUtil::publishChangeEvent;
  }

  /** Creates a production {@link TimeSeriesRepositoryProvider} that delegates to {@link Entity}. */
  public static TimeSeriesRepositoryProvider defaultTimeSeriesRepositoryProvider() {
    return Entity::getEntityTimeSeriesRepository;
  }

  /** Creates a production {@link EntityByReferenceFetcher} that delegates to {@link Entity}. */
  public static EntityByReferenceFetcher defaultEntityByReferenceFetcher() {
    return Entity::getEntity;
  }

  /**
   * Creates a production {@link ChangeEventDaoInserter} that delegates to {@link
   * Entity#getCollectionDAO}.
   */
  /** Creates a production {@link VectorServiceProvider} that delegates to {@code
   * OpenSearchVectorService.getInstance()}. */
  public static VectorServiceProvider defaultVectorServiceProvider() {
    return org.openmetadata.service.search.vector.OpenSearchVectorService::getInstance;
  }

  public static ChangeEventDaoInserter defaultChangeEventDaoInserter() {
    return json -> Entity.getCollectionDAO().changeEventDAO().insert(json);
  }

  /**
   * Creates a production {@link PatchAuthorizer} that constructs {@link OperationContext} from the
   * JSON Patch and delegates to the real authorizer.
   */
  public static PatchAuthorizer defaultPatchAuthorizer(
      Authorizer authorizer, CatalogSecurityContext securityContext) {
    return (entityType, jsonPatch, fqn) -> {
      try {
        authorizer.authorize(
            securityContext,
            new OperationContext(entityType, jsonPatch),
            new ResourceContext<>(entityType, null, fqn));
      } catch (RuntimeException e) {
        throw e; // preserve original type (e.g. ForbiddenException)
      } catch (Exception e) {
        throw new RuntimeException("Authorization failed for PATCH " + entityType + "/" + fqn, e);
      }
    };
  }
}
