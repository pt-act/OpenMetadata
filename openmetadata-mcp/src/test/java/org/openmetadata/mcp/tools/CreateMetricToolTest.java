package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openmetadata.schema.entity.data.Metric;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.service.jdbi3.MetricRepository;
import org.openmetadata.service.resources.metrics.MetricMapper;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.util.RestUtil;

/**
 * Unit tests for CreateMetricTool.
 *
 * <p>Integration tests use {@link McpEntityBridge.CreateOperationAuthorizer}, {@link
 * McpEntityBridge.RepositoryProvider}, and {@link McpEntityBridge.ChangeEventPublisher} functional
 * interfaces instead of {@code mockStatic(Entity.class)}, eliminating the need to mock Entity
 * static initializers. The {@code CreateResourceContext} constructor (which calls {@code
 * Entity.getEntityRepository()} internally) and {@code Entity.getCollectionDAO()} are never
 * invoked because the injected no-op authorizer and publisher bypass them.
 */
@ExtendWith(MockitoExtension.class)
class CreateMetricToolTest {

  private CatalogSecurityContext securityContext;

  @BeforeEach
  void setUp() {
    securityContext = mock(CatalogSecurityContext.class);

    Principal mockPrincipal = mock(Principal.class);
    lenient().when(mockPrincipal.getName()).thenReturn("test-user");
    lenient().when(securityContext.getUserPrincipal()).thenReturn(mockPrincipal);
  }

  @Test
  void testNonLimitsOverloadThrows() {
    CreateMetricTool tool = new CreateMetricTool();
    Map<String, Object> params = new HashMap<>();
    Authorizer auth = mock(Authorizer.class);
    assertThatThrownBy(() -> tool.execute(auth, securityContext, params))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testExecuteCallsPrepareInternal() {
    MetricRepository repo = mock(MetricRepository.class);
    Metric metric = new Metric();
    metric.setId(UUID.randomUUID());
    metric.setName("TestMetric");

    RestUtil.PutResponse<Metric> putResponse =
        new RestUtil.PutResponse<>(Response.Status.CREATED, metric, EventType.ENTITY_CREATED);

    when(repo.createOrUpdate(isNull(), any(Metric.class), anyString(), any()))
        .thenReturn(putResponse);

    // Inject functional interfaces — no mockStatic(Entity.class) needed
    McpEntityBridge.RepositoryProvider repoProvider = (entityType) -> repo;

    try (MockedConstruction<MetricMapper> mapperMock =
        mockConstruction(
            MetricMapper.class,
            (mapper, context) ->
                when(mapper.createToEntity(any(), anyString())).thenReturn(metric))) {

      Map<String, Object> params = new HashMap<>();
      params.put("name", "TestMetric");
      params.put("metricExpressionLanguage", "SQL");
      params.put("metricExpressionCode", "SELECT COUNT(*) FROM orders");

      CreateMetricTool tool = new CreateMetricTool();
      Map<String, Object> result =
          tool.execute(
              securityContext,
              params,
              (entityType, entity) -> {}, // no-op CreateOperationAuthorizer
              repoProvider,
              (entity, changeType, userName) -> {}); // no-op ChangeEventPublisher

      assertNotNull(result);
      verify(repo).prepareInternal(any(Metric.class), eq(false));

      // Envelope structure assertions
      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isNotEmpty();

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("TestMetric");
      assertThat(narrative).contains("SQL");

      // Backward-compat fields
      assertThat(result).containsEntry("metricName", "TestMetric");
    }
  }

  @Nested
  class BuildMetricResponseTests {

    @Test
    void hasEnvelopeFields() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestMetric");

      Map<String, Object> result =
          CreateMetricTool.buildMetricResponse(entityData, "TestMetric", "SQL");

      assertThat(result).containsKey("results");
      assertThat(result).containsKey("narrative");
    }

    @Test
    void resultsContainsEntityData() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestMetric");
      entityData.put("description", "A test metric");

      Map<String, Object> result =
          CreateMetricTool.buildMetricResponse(entityData, "TestMetric", "SQL");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).hasSize(1);
      assertThat(results.get(0)).isSameAs(entityData);
    }

    @Test
    void resultsIsEmptyListWhenEntityDataIsNull() {
      Map<String, Object> result = CreateMetricTool.buildMetricResponse(null, "TestMetric", "SQL");

      @SuppressWarnings("unchecked")
      List<Object> results = (List<Object>) result.get("results");
      assertThat(results).isEmpty();
    }

    @Test
    void narrativeDescribesTheCreation() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "OrderCount");

      Map<String, Object> result =
          CreateMetricTool.buildMetricResponse(entityData, "OrderCount", "Python");

      String narrative = (String) result.get("narrative");
      assertThat(narrative).contains("OrderCount");
      assertThat(narrative).contains("Python");
      assertThat(narrative).startsWith("Created metric");
    }

    @Test
    void backwardCompatMetricName() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "MyMetric");

      Map<String, Object> result =
          CreateMetricTool.buildMetricResponse(entityData, "MyMetric", "SQL");

      assertThat(result).containsEntry("metricName", "MyMetric");
    }

    @Test
    void noPaginationBlock() {
      Map<String, Object> entityData = new HashMap<>();
      entityData.put("name", "TestMetric");

      Map<String, Object> result =
          CreateMetricTool.buildMetricResponse(entityData, "TestMetric", "SQL");

      assertThat(result).doesNotContainKey("pagination");
    }
  }
}
