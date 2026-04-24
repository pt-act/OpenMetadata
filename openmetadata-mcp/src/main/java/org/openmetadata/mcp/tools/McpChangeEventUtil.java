package org.openmetadata.mcp.tools;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.formatter.util.FormatterUtil;

@Slf4j
public final class McpChangeEventUtil {
  private McpChangeEventUtil() {}

  /**
   * Production call — uses the default {@link McpEntityBridge.ChangeEventDaoInserter} that
   * delegates to {@code Entity.getCollectionDAO().changeEventDAO().insert()}.
   */
  public static <T extends EntityInterface> void publishChangeEvent(
      T entity, EventType changeType, String userName) {
    publishChangeEvent(
        entity, changeType, userName, McpEntityBridge.defaultChangeEventDaoInserter());
  }

  /**
   * Test-friendly overload — accepts an injected {@link McpEntityBridge.ChangeEventDaoInserter}
   * to eliminate the need for {@code mockStatic(Entity.class)}.
   */
  @VisibleForTesting
  static <T extends EntityInterface> void publishChangeEvent(
      T entity,
      EventType changeType,
      String userName,
      McpEntityBridge.ChangeEventDaoInserter daoInserter) {
    if (entity == null || changeType == null || changeType.equals(EventType.ENTITY_NO_CHANGE)) {
      return;
    }
    try {
      ChangeEvent changeEvent =
          FormatterUtil.createChangeEventForEntity(userName, changeType, entity);
      changeEvent.setUserName(userName);

      if (changeEvent.getEntity() != null) {
        Object rawEntity = changeEvent.getEntity();
        ChangeEvent copy =
            org.openmetadata.service.events.ChangeEventHandler.copyChangeEvent(changeEvent);
        copy.setEntity(JsonUtils.pojoToMaskedJson(rawEntity));
        daoInserter.insert(JsonUtils.pojoToJson(copy));
      } else {
        daoInserter.insert(JsonUtils.pojoToJson(changeEvent));
      }

      LOG.debug(
          "Published MCP change event {}:{}:{}",
          changeEvent.getEntityId(),
          changeEvent.getEventType(),
          changeEvent.getEntityType());
    } catch (Exception e) {
      LOG.error("Failed to publish MCP change event for {}", entity.getId(), e);
    }
  }
}
