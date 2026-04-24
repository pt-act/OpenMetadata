package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.tests.TestPlatform;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.jdbi3.TestDefinitionRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

@Slf4j
public class TestDefinitionsTool implements McpTool {
  /**
   * Production call — creates default bridge interfaces that delegate to {@link Entity} static
   * methods and the real authorizer.
   */
  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> params) {
    return execute(
        params,
        McpEntityBridge.defaultAuthorizer(authorizer, catalogSecurityContext),
        McpEntityBridge.defaultRepositoryProvider());
  }

  /**
   * Test-friendly overload — accepts injected functional interfaces for all {@link Entity}
   * static method calls and authorizer delegation, eliminating the need for {@code
   * mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  Map<String, Object> execute(
      Map<String, Object> params,
      McpEntityBridge.McpAuthorizer authorizer,
      McpEntityBridge.RepositoryProvider repoProvider) {
    int limit = 10;
    if (params.containsKey("limit")) {
      Object limitObj = params.get("limit");
      if (limitObj instanceof Number) {
        limit = ((Number) limitObj).intValue();
      } else if (limitObj instanceof String string) {
        try {
          limit = Integer.parseInt(string);
        } catch (NumberFormatException e) {
          limit = 10;
        }
      }
    }
    String entityType =
        params.containsKey("entityType") ? (String) params.get("entityType") : "TABLE";
    String testPlatformParam =
        params.containsKey("testPlatform")
            ? (String) params.get("testPlatform")
            : TestPlatform.OPEN_METADATA.value();
    String after = params.containsKey("after") ? (String) params.get("after") : null;

    String testDefEntityType = Entity.TEST_DEFINITION;
    TestDefinitionRepository repository =
        (TestDefinitionRepository) repoProvider.getEntityRepository(testDefEntityType);

    authorizer.authorize(testDefEntityType, MetadataOperation.VIEW_BASIC);
    LOG.info(
        "Listing test definitions for entityType: {}, testPlatform: {}, limit: {}",
        entityType,
        testPlatformParam,
        limit);
    ListFilter filter = new ListFilter(Include.NON_DELETED);
    if (entityType != null) {
      filter.addQueryParam("entityType", entityType);
    }
    if (testPlatformParam != null) {
      filter.addQueryParam("testPlatform", testPlatformParam);
    }

    Map<String, Object> listResult =
        JsonUtils.getMap(
            repository.listAfter(null, repository.getFields("*"), filter, limit, after));

    return buildTestDefinitionsResponse(listResult, entityType, testPlatformParam, after);
  }

  /**
   * Builds the test definitions list response envelope. Extracted as a static method for unit
   * testing since TestDefinitionRepository requires extensive mocking.
   *
   * <p>This tool uses cursor-based pagination (via 'after' param), not offset-based. The
   * EnvelopeBuilder.pagination() block is omitted because from/nextFrom are meaningless with
   * cursor-based models. Instead, we use hasMore + pagingAfter cursor.
   *
   * @param listResult the raw result map from JsonUtils.getMap(repository.listAfter(...))
   * @param entityType the entity type filter (e.g. "TABLE")
   * @param testPlatform the test platform filter
   * @param after the cursor used for this request (may be null)
   * @return envelope map with results, narrative, backward-compat fields, and cursor pagination
   */
  @VisibleForTesting
  static Map<String, Object> buildTestDefinitionsResponse(
      Map<String, Object> listResult, String entityType, String testPlatform, String after) {
    @SuppressWarnings("unchecked")
    List<Object> data =
        listResult.containsKey("data") && listResult.get("data") instanceof List
            ? (List<Object>) listResult.get("data")
            : List.of();
    EnvelopeBuilder envelope =
        EnvelopeBuilder.create()
            .results(data)
            .narrative(
                String.format(
                    "Listed %d test definition(s) on this page for entityType '%s'.",
                    data.size(), entityType));
    Map<String, Object> result = new HashMap<>(envelope.build());
    // Backward-compat fields kept for existing consumers
    result.put("entityType", entityType);
    result.put("testPlatform", testPlatform);
    if (after != null) {
      result.put("after", after);
    }
    // Cursor-based pagination: extract cursors from nested paging object and derive hasMore
    Object pagingObj = listResult.get("paging");
    if (pagingObj instanceof Map<?, ?> pagingMap) {
      // Paging serializes as {"before": "...", "after": "...", "total": N}
      Object pagingBefore = pagingMap.get("before");
      Object pagingAfter = pagingMap.get("after");
      if (pagingBefore != null) {
        result.put("pagingBefore", pagingBefore);
      }
      if (pagingAfter != null) {
        result.put("pagingAfter", pagingAfter);
        // If pagingAfter exists, there are more results beyond this page
        result.put("hasMore", true);
      }
    }
    return result;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext catalogSecurityContext,
      Map<String, Object> map) {
    throw new UnsupportedOperationException(
        "TestDefinitionsTool does not require limit validation.");
  }
}
