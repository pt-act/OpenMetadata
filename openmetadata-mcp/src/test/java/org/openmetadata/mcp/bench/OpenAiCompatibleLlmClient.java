package org.openmetadata.mcp.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A live {@link BenchLlmClient} that calls OpenAI-compatible APIs.
 *
 * <p>Activated when {@code OPENAI_API_KEY} environment variable is set. Uses the chat completions
 * API with tool definitions to select tools based on prompt content.
 *
 * <p>Configuration via environment variables:
 *
 * <ul>
 *   <li>{@code OPENAI_API_KEY} - Required. API key for authentication.
 *   <li>{@code OPENAI_BASE_URL} - Optional. Defaults to {@code
 *       https://api.openai.com/v1/chat/completions}.
 *   <li>{@code OPENAI_MODEL} - Optional. Defaults to {@code gpt-4o}.
 *   <li>{@code MAX_BENCH_COST_USD} - Optional. Cost guard. Defaults to {@code 1.0}.
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements BenchLlmClient {

  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";
  private static final String DEFAULT_MODEL = "gpt-4o";
  private static final double DEFAULT_MAX_COST_USD = 1.0;

  private final String apiKey;
  private final String baseUrl;
  private final String model;
  private final double maxCostUsd;
  private final OkHttpClient httpClient;
  private final ObjectMapper mapper;
  private double totalCostUsd = 0.0;

  public OpenAiCompatibleLlmClient() {
    this.apiKey = requireEnv("OPENAI_API_KEY");
    this.baseUrl = getEnv("OPENAI_BASE_URL", DEFAULT_BASE_URL);
    this.model = getEnv("OPENAI_MODEL", DEFAULT_MODEL);
    this.maxCostUsd = getEnvDouble("MAX_BENCH_COST_USD", DEFAULT_MAX_COST_USD);
    this.httpClient = new OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build();
    this.mapper = new ObjectMapper();
  }

  @Override
  public List<String> selectTools(String prompt, List<String> availableTools) {
    return selectToolsOrPrompts(prompt, availableTools, "tool");
  }

  @Override
  public List<String> selectPrompts(String prompt, List<String> availablePrompts) {
    return selectToolsOrPrompts(prompt, availablePrompts, "prompt");
  }

  private List<String> selectToolsOrPrompts(String prompt, List<String> available, String kind) {
    if (available.isEmpty()) {
      return List.of();
    }

    ObjectNode requestBody = buildRequestBody(prompt, available, kind);

    try {
      String responseJson = executeRequest(requestBody);
      return parseToolCalls(responseJson);
    } catch (IOException e) {
      throw new RuntimeException("Failed to call LLM API: " + e.getMessage(), e);
    }
  }

  private ObjectNode buildRequestBody(String prompt, List<String> available, String kind) {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", 500);
    body.put("temperature", 0.0);

    ArrayNode messages = body.putArray("messages");
    ObjectNode systemMsg = messages.addObject();
    systemMsg.put("role", "system");
    systemMsg.put(
        "content",
        "You are a tool selector for an OpenMetadata MCP server. "
            + "Given a user prompt, select the most appropriate "
            + kind
            + "(s) to call. "
            + "Return ONLY the tool/prompt names, no explanations. "
            + "If multiple tools are needed, list them in order of execution.");

    ObjectNode userMsg = messages.addObject();
    userMsg.put("role", "user");
    userMsg.put("content", prompt + "\n\nAvailable " + kind + "s: " + String.join(", ", available));

    ArrayNode tools = body.putArray("tools");
    for (String name : available) {
      ObjectNode tool = tools.addObject();
      ObjectNode function = tool.putObject("function");
      function.put("name", name);
      function.put("description", kind + ": " + name);
      function.putObject("parameters").put("type", "object");
    }

    body.put("tool_choice", "auto");

    return body;
  }

  private String executeRequest(ObjectNode requestBody) throws IOException {
    checkCostGuard();

    Request request =
        new Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer " + apiKey)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(mapper.writeValueAsString(requestBody), MediaType.parse("application/json")))
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = response.body() != null ? response.body().string() : "no body";
        throw new IOException("API request failed: " + response.code() + " - " + errorBody);
      }
      return response.body().string();
    }
  }

  private List<String> parseToolCalls(String responseJson) throws IOException {
    List<String> selected = new ArrayList<>();
    JsonNode root = mapper.readTree(responseJson);

    // Track cost
    JsonNode usage = root.get("usage");
    if (usage != null) {
      int promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
      int completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
      // Rough cost estimate: GPT-4o ~$2.50/1M input, $10/1M output
      double cost = (promptTokens * 2.5 / 1_000_000) + (completionTokens * 10.0 / 1_000_000);
      totalCostUsd += cost;
    }

    JsonNode choices = root.get("choices");
    if (choices != null && choices.isArray() && choices.size() > 0) {
      JsonNode message = choices.get(0).get("message");
      if (message != null) {
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
          for (JsonNode tc : toolCalls) {
            JsonNode function = tc.get("function");
            if (function != null && function.has("name")) {
              selected.add(function.get("name").asText());
            }
          }
        }
      }
    }

    return selected;
  }

  @Override
  public String generateAnswer(String prompt, List<ToolCallResult> toolCallResults) {
    StringBuilder sb = new StringBuilder();
    sb.append("Based on analysis");
    for (ToolCallResult result : toolCallResults) {
      sb.append(" using ").append(result.toolName());
    }
    sb.append(": ");
    for (ToolCallResult result : toolCallResults) {
      sb.append(result.responseSummary()).append(" ");
    }
    return sb.toString().trim();
  }

  private void checkCostGuard() {
    if (totalCostUsd >= maxCostUsd) {
      throw new IllegalStateException(
          "Cost guard exceeded: $" + String.format("%.2f", totalCostUsd) + " >= $" + maxCostUsd
              + ". Set MAX_BENCH_COST_USD to increase limit.");
    }
  }

  public double getTotalCostUsd() {
    return totalCostUsd;
  }

  // --- Environment helpers ---

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Required environment variable " + name + " is not set");
    }
    return value;
  }

  private static String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }

  private static double getEnvDouble(String name, double defaultValue) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
