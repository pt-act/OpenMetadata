package org.openmetadata.mcp.prompts;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;

/**
 * Prompt handler for the ownership stewardship workflow.
 *
 * <p>Generates a structured prompt message that guides a data steward through the 3-step
 * ownership assignment workflow:
 * <ol>
 *   <li>{@code find_unowned_assets} — discover assets without owners, ranked by downstream impact</li>
 *   <li>{@code suggest_owner_for} — identify the best owner candidate for each unowned asset</li>
 *   <li>{@code draft_ownership_patch} — generate a review-ready JSONPatch for ownership assignment</li>
 * </ol>
 */
public class OwnershipStewardshipPrompt implements McpPrompt {

  @Override
  public McpSchema.GetPromptResult callPrompt(McpSchema.GetPromptRequest promptRequest) {
    Map<String, Object> params = promptRequest.arguments();

    String entityType = (String) params.getOrDefault("entityType", "table");
    int limit = 25;
    if (params.containsKey("limit")) {
      Object limitObj = params.get("limit");
      if (limitObj instanceof Number) {
        limit = ((Number) limitObj).intValue();
      } else if (limitObj instanceof String) {
        try {
          limit = Integer.parseInt((String) limitObj);
        } catch (NumberFormatException ignored) {
        }
      }
    }

    StringBuilder scopeHint = new StringBuilder();
    Object scopeObj = params.get("scope");
    if (scopeObj instanceof String scopeStr && !scopeStr.isBlank()) {
      scopeHint.append(" scoped to the `").append(scopeStr).append("` domain");
    } else if (scopeObj instanceof Map<?, ?> scopeMap) {
      scopeHint
          .append(" scoped to ")
          .append(scopeMap.get("type"))
          .append(" `")
          .append(scopeMap.get("value"))
          .append("`");
    }

    String message =
        String.format(
            "You are a data steward performing an ownership stewardship review.%n%n"
                + "**Step 1:** Call `find_unowned_assets` with entityType=`%s` and limit=%d%s"
                + " to discover assets without owners, ranked by downstream impact.%n%n"
                + "**Step 2:** For each high-priority unowned asset from Step 1, call"
                + " `suggest_owner_for` with the asset's fullyQualifiedName and entityType"
                + " to identify the best owner candidate.%n%n"
                + "**Step 3:** For each suggested owner, call `draft_ownership_patch` with"
                + " the entity's fullyQualifiedName, the owner name, and mode=`add` to generate"
                + " a review-ready JSONPatch. Review each patch carefully before calling"
                + " `patch_entity` to apply it.%n%n"
                + "**Important:** `draft_ownership_patch` does NOT apply the patch — it only"
                + " drafts it for review. You must call `patch_entity` to actually assign ownership.%n%n"
                + "Present the results as a prioritized action list: for each unowned asset,"
                + " show the suggested owner, their score, and the draft patch summary.",
            entityType, limit, scopeHint);

    return new McpSchema.GetPromptResult(
        "Ownership stewardship workflow prompt",
        List.of(
            new McpSchema.PromptMessage(
                McpSchema.Role.ASSISTANT, new McpSchema.TextContent(message))));
  }
}
