package org.openmetadata.mcp.bench;

import java.util.List;

/**
 * Interface for an LLM client that the benchmark harness drives.
 *
 * <p>A deterministic implementation is provided for CI ({@link DeterministicBenchLlmClient});
 * a live OpenAI-compatible implementation can be toggled in for local/nightly runs.
 */
public interface BenchLlmClient {

  /**
   * Given a prompt and the list of available tool definitions, returns the sequence of tool calls
   * the LLM would make.
   *
   * @param prompt the user prompt from the fixture
   * @param availableTools list of tool names available to the LLM
   * @return ordered list of tool names the LLM selects
   */
  List<String> selectTools(String prompt, List<String> availableTools);

  /**
   * Given a prompt and the list of available prompt definitions, returns the sequence of MCP
   * prompts the LLM would invoke.
   *
   * @param prompt the user prompt from the fixture
   * @param availablePrompts list of prompt names available to the LLM
   * @return ordered list of prompt names the LLM selects
   */
  List<String> selectPrompts(String prompt, List<String> availablePrompts);

  /**
   * Given a prompt and the tool-call responses, returns the LLM's final answer text.
   *
   * @param prompt the user prompt from the fixture
   * @param toolCallResults list of (toolName, responseSummary) pairs
   * @return the LLM's final answer string
   */
  String generateAnswer(String prompt, List<ToolCallResult> toolCallResults);

  /** A single tool call result for answer generation. */
  record ToolCallResult(String toolName, String responseSummary) {}
}
