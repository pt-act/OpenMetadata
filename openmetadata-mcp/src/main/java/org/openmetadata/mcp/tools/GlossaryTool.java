package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.data.CreateGlossary;
import org.openmetadata.schema.entity.data.Glossary;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.GlossaryRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.glossary.GlossaryMapper;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

@Slf4j
public class GlossaryTool implements McpTool {
  private static GlossaryMapper glossaryMapper = new GlossaryMapper();

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    throw new UnsupportedOperationException("GlossaryTool requires limit validation.");
  }

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer/limits.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    return execute(
        securityContext,
        params,
        McpEntityBridge.defaultCreateOperationAuthorizer(authorizer, limits, securityContext),
        McpEntityBridge.defaultRepositoryProvider(),
        McpEntityBridge.defaultChangeEventPublisher());
  }

  /**
   * Test-friendly overload — accepts a {@link McpEntityBridge.CreateOperationAuthorizer},
   * {@link McpEntityBridge.RepositoryProvider}, and {@link McpEntityBridge.ChangeEventPublisher}
   * for dependency injection. Tests inject a no-op authorizer, a lambda that returns a mock
   * repository, and a no-op publisher, eliminating the need for {@code
   * mockStatic(Entity.class)} — the {@code CreateResourceContext} constructor and {@code
   * Entity.getCollectionDAO()} are never called.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      CatalogSecurityContext securityContext,
      Map<String, Object> params,
      McpEntityBridge.CreateOperationAuthorizer<Glossary> createOpAuthorizer,
      McpEntityBridge.RepositoryProvider repositoryProvider,
      McpEntityBridge.ChangeEventPublisher changeEventPublisher) {
    CreateGlossary createGlossary = new CreateGlossary();
    createGlossary.setName((String) params.get("name"));
    createGlossary.setDescription((String) params.get("description"));
    if (params.containsKey("owners")) {
      CommonUtils.setOwners(createGlossary, params);
    }
    if (params.containsKey("reviewers")) {
      setReviewers(createGlossary, params);
    }
    if (params.containsKey("mutuallyExclusive")) {
      Object meObj = params.get("mutuallyExclusive");
      if (meObj instanceof Boolean b) {
        createGlossary.setMutuallyExclusive(b);
      } else if (meObj instanceof String s) {
        createGlossary.setMutuallyExclusive("true".equalsIgnoreCase(s));
      }
    }

    Glossary glossary =
        glossaryMapper.createToEntity(createGlossary, securityContext.getUserPrincipal().getName());

    // Validate limits + authorize via injected CreateOperationAuthorizer —
    // no CreateResourceContext constructed when a test injects a no-op
    createOpAuthorizer.authorizeCreate(Entity.GLOSSARY, glossary);

    GlossaryRepository glossaryRepository =
        (GlossaryRepository) repositoryProvider.getEntityRepository(Entity.GLOSSARY);

    glossaryRepository.prepareInternal(glossary, false);

    String impersonatedBy = ImpersonationContext.getImpersonatedBy();

    String userName = securityContext.getUserPrincipal().getName();
    RestUtil.PutResponse<Glossary> response =
        glossaryRepository.createOrUpdate(null, glossary, userName, impersonatedBy);
    changeEventPublisher.publishChangeEvent(
        response.getEntity(), response.getChangeType(), userName);

    // Wrap in envelope for consistency with other MCP tools (E1.8)
    Map<String, Object> entityData = JsonUtils.convertValue(response.getEntity(), Map.class);
    return buildGlossaryResponse(entityData, createGlossary.getName());
  }

  /**
   * Builds the glossary creation response envelope. Extracted as a static method for unit testing
   * since GlossaryRepository and GlossaryMapper require extensive mocking.
   *
   * @param entityData the serialized glossary entity data (may be null)
   * @param glossaryName the name of the created glossary
   * @return envelope map with results, narrative, and backward-compat fields
   */
  @VisibleForTesting
  static Map<String, Object> buildGlossaryResponse(
      Map<String, Object> entityData, String glossaryName) {
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(entityData != null ? List.of(entityData) : List.of())
            .narrative(String.format("Created glossary '%s'.", glossaryName));
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("glossaryName", glossaryName);
    return result;
  }

  public static void setReviewers(CreateGlossary entity, Map<String, Object> params) {
    List<EntityReference> reviewers = CommonUtils.getTeamsOrUsers(params.get("reviewers"));
    if (!reviewers.isEmpty()) {
      entity.setReviewers(reviewers);
    }
  }
}
