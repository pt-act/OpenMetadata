package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.GlossaryTermRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.glossary.GlossaryTermMapper;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

@Slf4j
public class GlossaryTermTool implements McpTool {
  private static GlossaryTermMapper glossaryTermMapper = new GlossaryTermMapper();

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    throw new UnsupportedOperationException("GlossaryTermTool requires limit validation.");
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
      McpEntityBridge.CreateOperationAuthorizer<GlossaryTerm> createOpAuthorizer,
      McpEntityBridge.RepositoryProvider repositoryProvider,
      McpEntityBridge.ChangeEventPublisher changeEventPublisher) {
    org.openmetadata.schema.api.data.CreateGlossaryTerm createGlossaryTerm =
        new org.openmetadata.schema.api.data.CreateGlossaryTerm();
    createGlossaryTerm.setName((String) params.get("name"));
    createGlossaryTerm.setGlossary((String) params.get("glossary"));
    createGlossaryTerm.setParent((String) params.get("parentTerm"));
    createGlossaryTerm.setDescription((String) params.get("description"));
    if (params.containsKey("owners")) {
      CommonUtils.setOwners(createGlossaryTerm, params);
    }
    if (params.containsKey("reviewers")) {
      createGlossaryTerm.setReviewers(CommonUtils.getTeamsOrUsers(params.get("reviewers")));
    }

    GlossaryTerm glossaryTerm =
        glossaryTermMapper.createToEntity(
            createGlossaryTerm, securityContext.getUserPrincipal().getName());

    // Validate limits + authorize via injected CreateOperationAuthorizer —
    // no CreateResourceContext constructed when a test injects a no-op
    createOpAuthorizer.authorizeCreate(Entity.GLOSSARY_TERM, glossaryTerm);

    GlossaryTermRepository glossaryTermRepository =
        (GlossaryTermRepository) repositoryProvider.getEntityRepository(Entity.GLOSSARY_TERM);
    glossaryTermRepository.prepareInternal(glossaryTerm, false);

    String impersonatedBy = ImpersonationContext.getImpersonatedBy();

    String userName = securityContext.getUserPrincipal().getName();
    RestUtil.PutResponse<GlossaryTerm> response =
        glossaryTermRepository.createOrUpdate(null, glossaryTerm, userName, impersonatedBy);
    changeEventPublisher.publishChangeEvent(
        response.getEntity(), response.getChangeType(), userName);

    // Wrap in envelope for consistency with other MCP tools (E1.8)
    Map<String, Object> entityData = JsonUtils.getMap(response.getEntity());
    return buildGlossaryTermResponse(
        entityData, createGlossaryTerm.getName(), createGlossaryTerm.getGlossary());
  }

  /**
   * Builds the glossary term creation response envelope. Extracted as a static method for unit
   * testing since GlossaryTermRepository and GlossaryTermMapper require extensive mocking.
   *
   * @param entityData the serialized glossary term entity data (may be null)
   * @param termName the name of the created glossary term
   * @param glossaryName the name of the parent glossary
   * @return envelope map with results, narrative, and backward-compat fields
   */
  @VisibleForTesting
  static Map<String, Object> buildGlossaryTermResponse(
      Map<String, Object> entityData, String termName, String glossaryName) {
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(entityData != null ? List.of(entityData) : List.of())
            .narrative(
                String.format(
                    "Created glossary term '%s' in glossary '%s'.", termName, glossaryName));
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("glossaryTermName", termName);
    result.put("glossary", glossaryName);
    return result;
  }
}
