package org.openmetadata.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.events.ChangeEventHandler;
import org.openmetadata.service.formatter.util.FormatterUtil;

/**
 * Unit tests for McpChangeEventUtil.publishChangeEvent().
 *
 * <p>Covers: null/skip guards, successful DAO insertion, and exception swallowing.
 */
class McpChangeEventUtilTest {

  @Test
  void publishChangeEvent_nullEntity_doesNothing() {
    assertThatNoException()
        .isThrownBy(
            () -> McpChangeEventUtil.publishChangeEvent(null, EventType.ENTITY_CREATED, "admin"));
  }

  @Test
  void publishChangeEvent_nullChangeType_doesNothing() {
    EntityInterface entity = mock(EntityInterface.class);
    assertThatNoException()
        .isThrownBy(() -> McpChangeEventUtil.publishChangeEvent(entity, null, "admin"));
    verify(entity, never()).getId();
  }

  @Test
  void publishChangeEvent_entityNoChange_doesNothing() {
    EntityInterface entity = mock(EntityInterface.class);
    assertThatNoException()
        .isThrownBy(
            () ->
                McpChangeEventUtil.publishChangeEvent(entity, EventType.ENTITY_NO_CHANGE, "admin"));
    verify(entity, never()).getId();
  }

  @Test
  void publishChangeEvent_success_insertsChangeEventToDao() {
    EntityInterface entity = mock(EntityInterface.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());

    ChangeEvent changeEvent = mock(ChangeEvent.class);
    when(changeEvent.getEntity()).thenReturn(entity);
    when(changeEvent.getEntityId()).thenReturn(UUID.randomUUID());
    when(changeEvent.getEventType()).thenReturn(EventType.ENTITY_CREATED);
    when(changeEvent.getEntityType()).thenReturn("table");

    ChangeEvent copy = mock(ChangeEvent.class);
    when(copy.getEntity()).thenReturn(null);

    // Capture inserted JSON strings via the injected inserter — no mockStatic(Entity.class) needed
    List<String> insertedJson = new ArrayList<>();
    McpEntityBridge.ChangeEventDaoInserter inserter = insertedJson::add;

    try (MockedStatic<FormatterUtil> formatterMock = mockStatic(FormatterUtil.class);
        MockedStatic<ChangeEventHandler> handlerMock = mockStatic(ChangeEventHandler.class);
        MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

      formatterMock
          .when(
              () ->
                  FormatterUtil.createChangeEventForEntity(
                      "admin", EventType.ENTITY_CREATED, entity))
          .thenReturn(changeEvent);
      handlerMock.when(() -> ChangeEventHandler.copyChangeEvent(changeEvent)).thenReturn(copy);
      jsonMock.when(() -> JsonUtils.pojoToMaskedJson(entity)).thenReturn("{}");
      jsonMock.when(() -> JsonUtils.pojoToJson(copy)).thenReturn("{\"event\":\"copy\"}");

      McpChangeEventUtil.publishChangeEvent(entity, EventType.ENTITY_CREATED, "admin", inserter);

      assertThat(insertedJson).containsExactly("{\"event\":\"copy\"}");
    }
  }

  @Test
  void publishChangeEvent_daoException_isSwallowedAndDoesNotPropagate() {
    EntityInterface entity = mock(EntityInterface.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());

    // Use the production overload — exception is thrown before DAO is called
    try (MockedStatic<FormatterUtil> formatterMock = mockStatic(FormatterUtil.class)) {
      formatterMock
          .when(() -> FormatterUtil.createChangeEventForEntity(anyString(), any(), any()))
          .thenThrow(new RuntimeException("DB connection refused"));

      assertThatNoException()
          .isThrownBy(
              () ->
                  McpChangeEventUtil.publishChangeEvent(entity, EventType.ENTITY_UPDATED, "admin"));
    }
  }

  @Test
  void publishChangeEvent_setsUserNameOnChangeEvent() {
    EntityInterface entity = mock(EntityInterface.class);
    when(entity.getId()).thenReturn(UUID.randomUUID());

    ChangeEvent changeEvent = mock(ChangeEvent.class);
    when(changeEvent.getEntity()).thenReturn(null);

    // Capture inserted JSON strings via the injected inserter — no mockStatic(Entity.class) needed
    List<String> insertedJson = new ArrayList<>();
    McpEntityBridge.ChangeEventDaoInserter inserter = insertedJson::add;

    try (MockedStatic<FormatterUtil> formatterMock = mockStatic(FormatterUtil.class);
        MockedStatic<JsonUtils> jsonMock = mockStatic(JsonUtils.class)) {

      formatterMock
          .when(
              () ->
                  FormatterUtil.createChangeEventForEntity(
                      "alice", EventType.ENTITY_CREATED, entity))
          .thenReturn(changeEvent);
      jsonMock.when(() -> JsonUtils.pojoToJson(changeEvent)).thenReturn("{\"user\":\"alice\"}");

      McpChangeEventUtil.publishChangeEvent(entity, EventType.ENTITY_CREATED, "alice", inserter);

      verify(changeEvent).setUserName("alice");
      assertThat(insertedJson).containsExactly("{\"user\":\"alice\"}");
    }
  }
}
