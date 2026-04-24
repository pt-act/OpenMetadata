package org.openmetadata.mcp.bench;

import java.util.ArrayList;
import java.util.List;

/** Result of evaluating a single benchmark fixture. */
public class BenchResult {

  private final String fixtureId;
  private boolean correctTool;
  private boolean noForbiddenTools;
  private boolean toolCallCountOk;
  private boolean answerCorrect;
  private boolean correctPrompts = true;
  private boolean noForbiddenPrompts = true;
  private int toolCallCount;
  private List<String> toolCalls = new ArrayList<>();
  private List<String> promptCalls = new ArrayList<>();
  private List<String> failures = new ArrayList<>();
  private long latencyMs;

  public BenchResult(String fixtureId) {
    this.fixtureId = fixtureId;
  }

  public String getFixtureId() {
    return fixtureId;
  }

  public boolean isCorrectTool() {
    return correctTool;
  }

  public void setCorrectTool(boolean correctTool) {
    this.correctTool = correctTool;
  }

  public boolean isNoForbiddenTools() {
    return noForbiddenTools;
  }

  public void setNoForbiddenTools(boolean noForbiddenTools) {
    this.noForbiddenTools = noForbiddenTools;
  }

  public boolean isToolCallCountOk() {
    return toolCallCountOk;
  }

  public void setToolCallCountOk(boolean toolCallCountOk) {
    this.toolCallCountOk = toolCallCountOk;
  }

  public boolean isAnswerCorrect() {
    return answerCorrect;
  }

  public void setAnswerCorrect(boolean answerCorrect) {
    this.answerCorrect = answerCorrect;
  }

  public int getToolCallCount() {
    return toolCallCount;
  }

  public void setToolCallCount(int toolCallCount) {
    this.toolCallCount = toolCallCount;
  }

  public List<String> getToolCalls() {
    return toolCalls;
  }

  public void setToolCalls(List<String> toolCalls) {
    this.toolCalls = toolCalls;
  }

  public List<String> getPromptCalls() {
    return promptCalls;
  }

  public void setPromptCalls(List<String> promptCalls) {
    this.promptCalls = promptCalls;
  }

  public boolean isCorrectPrompts() {
    return correctPrompts;
  }

  public void setCorrectPrompts(boolean correctPrompts) {
    this.correctPrompts = correctPrompts;
  }

  public boolean isNoForbiddenPrompts() {
    return noForbiddenPrompts;
  }

  public void setNoForbiddenPrompts(boolean noForbiddenPrompts) {
    this.noForbiddenPrompts = noForbiddenPrompts;
  }

  public List<String> getFailures() {
    return failures;
  }

  public void addFailure(String failure) {
    this.failures.add(failure);
  }

  public long getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(long latencyMs) {
    this.latencyMs = latencyMs;
  }

  /** Overall pass/fail: all deterministic checks must pass (tools + prompts). */
  public boolean isPass() {
    return correctTool
        && noForbiddenTools
        && toolCallCountOk
        && correctPrompts
        && noForbiddenPrompts;
  }

  @Override
  public String toString() {
    return "BenchResult{fixture='"
        + fixtureId
        + "', pass="
        + isPass()
        + ", correctTool="
        + correctTool
        + ", correctPrompts="
        + correctPrompts
        + ", toolCalls="
        + toolCallCount
        + '}';
  }
}
