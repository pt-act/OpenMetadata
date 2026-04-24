package org.openmetadata.mcp.bench;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single benchmark fixture describing an expected tool-call plan.
 *
 * <p>Fixtures are loaded from YAML files under {@code src/test/resources/bench/fixtures/}.
 * Each fixture has a prompt (what the LLM would say), expected tool-call metadata, and
 * gold-answer substring checks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BenchFixture {

  private String id;
  private String prompt;
  private List<String> expectedTools = new ArrayList<>();
  private List<String> forbiddenTools = new ArrayList<>();
  private List<String> expectedPrompts = new ArrayList<>();
  private List<String> forbiddenPrompts = new ArrayList<>();
  private List<String> goldAnswerContains = new ArrayList<>();
  private int maxToolCalls = 5;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getPrompt() {
    return prompt;
  }

  public void setPrompt(String prompt) {
    this.prompt = prompt;
  }

  public List<String> getExpectedTools() {
    return expectedTools == null ? Collections.emptyList() : expectedTools;
  }

  public void setExpectedTools(List<String> expectedTools) {
    this.expectedTools = expectedTools;
  }

  public List<String> getForbiddenTools() {
    return forbiddenTools == null ? Collections.emptyList() : forbiddenTools;
  }

  public void setForbiddenTools(List<String> forbiddenTools) {
    this.forbiddenTools = forbiddenTools;
  }

  public List<String> getExpectedPrompts() {
    return expectedPrompts == null ? Collections.emptyList() : expectedPrompts;
  }

  public void setExpectedPrompts(List<String> expectedPrompts) {
    this.expectedPrompts = expectedPrompts;
  }

  public List<String> getForbiddenPrompts() {
    return forbiddenPrompts == null ? Collections.emptyList() : forbiddenPrompts;
  }

  public void setForbiddenPrompts(List<String> forbiddenPrompts) {
    this.forbiddenPrompts = forbiddenPrompts;
  }

  public List<String> getGoldAnswerContains() {
    return goldAnswerContains == null ? Collections.emptyList() : goldAnswerContains;
  }

  public void setGoldAnswerContains(List<String> goldAnswerContains) {
    this.goldAnswerContains = goldAnswerContains;
  }

  @JsonProperty("maxToolCalls")
  public int getMaxToolCalls() {
    return maxToolCalls;
  }

  public void setMaxToolCalls(int maxToolCalls) {
    this.maxToolCalls = maxToolCalls;
  }

  @Override
  public String toString() {
    return "BenchFixture{id='" + id + "', prompt='" + prompt + "'}";
  }
}
