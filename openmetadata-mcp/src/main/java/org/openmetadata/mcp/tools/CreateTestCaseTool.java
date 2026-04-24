package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.api.tests.CreateTestCase;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.TestCaseParameterValue;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.TestCaseRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.dqtests.TestCaseMapper;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.ImpersonationContext;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

@Slf4j
public class CreateTestCaseTool implements McpTool {
  private final TestCaseMapper testCaseMapper = new TestCaseMapper();

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> params) {
    throw new UnsupportedOperationException("CreateTestCaseTool requires limit validation.");
  }

  private TestCase getTestCase(
      String name,
      String description,
      String entityLinkValue,
      String testDefinitionName,
      List<TestCaseParameterValue> parameterValue,
      String updatedBy) {
    return testCaseMapper.createToEntity(
        new CreateTestCase()
            .withName(name)
            .withDisplayName(name)
            .withDescription(description)
            .withEntityLink(entityLinkValue)
            .withParameterValues(parameterValue)
            .withComputePassedFailedRowCount(false)
            .withUseDynamicAssertion(false)
            .withTestDefinition(testDefinitionName),
        updatedBy);
  }

  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer/limits.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> params) {
    return execute(
        catalogSecurityContext,
        params,
        McpEntityBridge.defaultCreateOperationAuthorizer(
            authorizer, limits, catalogSecurityContext),
        McpEntityBridge.defaultRepositoryProvider(),
        McpEntityBridge.defaultEntityReferenceResolver(),
        McpEntityBridge.defaultChangeEventPublisher());
  }

  /**
   * Test-friendly overload — accepts a {@link McpEntityBridge.CreateOperationAuthorizer},
   * {@link McpEntityBridge.RepositoryProvider}, {@link McpEntityBridge.EntityReferenceResolver},
   * and {@link McpEntityBridge.ChangeEventPublisher} for dependency injection. Tests inject a
   * no-op authorizer and lambdas that return mock repositories/references, eliminating the need
   * for {@code mockStatic(Entity.class)} — the {@code CreateResourceContext} constructor and
   * {@code Entity.getCollectionDAO()} are never called.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> params,
      McpEntityBridge.CreateOperationAuthorizer<TestCase> createOpAuthorizer,
      McpEntityBridge.RepositoryProvider repoProvider,
      McpEntityBridge.EntityReferenceResolver referenceResolver,
      McpEntityBridge.ChangeEventPublisher changeEventPublisher) {
    String testDefinitionName = (String) params.get("testDefinitionName");
    if (testDefinitionName == null || testDefinitionName.trim().isEmpty()) {
      throw new IllegalArgumentException("Parameter 'testDefinitionName' is required");
    }

    String entityType =
        params.containsKey("entityType") ? (String) params.get("entityType") : "table";

    // Use resolveEntityRef for multi-form entity identification (E1.5)
    // Supports: fqn, fullyQualifiedName, id, entityLink
    // Note: 'name' key is excluded from entityRefParams because 'name' means
    // "test case name" in this tool, not "entity name". This prevents
    // resolveEntityRef Strategy 4 from incorrectly constructing a composite FQN
    // using the test case name as the entity name.
    Map<String, Object> entityRefParams = new HashMap<>(params);
    entityRefParams.remove("name");
    entityRefParams.remove("parameterValues");
    entityRefParams.remove("description");
    entityRefParams.remove("testDefinitionName");
    entityRefParams.remove("columnName");
    EntityReference entityRef =
        ToolUtils.resolveEntityRef(entityRefParams, entityType, referenceResolver);
    String fqn = entityRef.getFullyQualifiedName();
    String description =
        params.containsKey("description")
            ? (String) params.get("description")
            : "Test case created by MCP tool";
    String name =
        params.containsKey("name")
            ? (String) params.get("name")
            : "TestCase_" + System.currentTimeMillis();
    String columnName = params.containsKey("columnName") ? (String) params.get("columnName") : null;
    MessageParser.EntityLink entityLink;
    if (columnName != null && !columnName.trim().isEmpty()) {
      entityLink =
          new MessageParser.EntityLink(entityType, fqn, "columns", columnName.trim(), null);
    } else {
      entityLink = new MessageParser.EntityLink(entityType, fqn);
    }
    String entityLinkValue = entityLink.getLinkString();
    List<TestCaseParameterValue> parameterValue =
        params.containsKey("parameterValues")
            ? JsonUtils.readOrConvertValues(
                params.get("parameterValues"), TestCaseParameterValue.class)
            : new ArrayList<>();

    String updatedBy = catalogSecurityContext.getUserPrincipal().getName();
    TestCase testCase =
        getTestCase(
            name, description, entityLinkValue, testDefinitionName, parameterValue, updatedBy);

    // Use injected RepositoryProvider instead of Entity.getEntityRepository() directly
    TestCaseRepository repository =
        (TestCaseRepository) repoProvider.getEntityRepository(Entity.TEST_CASE);
    repository.setFullyQualifiedName(testCase);
    repository.prepare(testCase, false);

    // Use injected CreateOperationAuthorizer — no CreateResourceContext constructed when
    // a test injects a no-op authorizer, so Entity.getEntityRepository() is never called
    createOpAuthorizer.authorizeCreate(Entity.TEST_CASE, testCase);

    LOG.info(
        "Creating test case '{}' with definition '{}' for entity: {}",
        name,
        testDefinitionName,
        fqn);
    String impersonatedBy = ImpersonationContext.getImpersonatedBy();
    RestUtil.PutResponse<TestCase> response =
        repository.createOrUpdate(null, testCase, updatedBy, impersonatedBy);
    changeEventPublisher.publishChangeEvent(
        response.getEntity(), response.getChangeType(), updatedBy);

    // Wrap in envelope for consistency with other MCP tools (E1.8)
    Map<String, Object> entityData = JsonUtils.getMap(response.getEntity());
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(entityData != null ? List.of(entityData) : List.of())
            .narrative(
                String.format(
                    "Created test case '%s' with definition '%s' for entity '%s'.",
                    name, testDefinitionName, fqn));
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("fqn", fqn);
    result.put("entityType", entityType);
    result.put("testDefinitionName", testDefinitionName);
    result.put("testCaseName", name);
    return result;
  }
}
