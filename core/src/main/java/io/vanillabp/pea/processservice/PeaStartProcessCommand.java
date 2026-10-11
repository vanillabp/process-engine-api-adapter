package io.vanillabp.pea.processservice;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.process.StartProcessCommand;

/**
 * The adapter's own {@link StartProcessCommand}.
 * <p>
 * The built-in start commands of the Process-Engine-API, for example
 * {@code StartProcessByDefinitionCmd}, are Kotlin {@code data class}es. They do <b>not</b>
 * override {@code ExecutionModeAware.executionMode()}. So their execution mode is always the
 * default ({@link ExecutionMode#DEFAULT}). It cannot be set to
 * {@link ExecutionMode#PREFLIGHT_CHECK} or {@link ExecutionMode#SYNC}. The two-phase start of
 * VanillaBP needs exactly these two modes, so the adapter brings its own
 * {@link StartProcessCommand} which carries the mode. See {@code GAPS.md}.
 * <p>
 * The command also carries the BPMN process id (the {@code definitionKey} of the
 * Process-Engine-API) and the process variables. The id of the workflow aggregate is an
 * ordinary payload variable. The start command has no slot for a business key or a
 * correlation (see {@code GAPS.md}).
 * <p>
 * Decision 4 in the repository's DECISIONS.md says why this adapter sends commands of its
 * own at all: the built-in command classes cannot carry a mode other than the default.
 */
public final class PeaStartProcessCommand implements StartProcessCommand {

  private final String bpmnProcessId;

  private final Map<String, Object> payload;

  private final ExecutionMode executionMode;

  /**
   * Builds the command for one phase.
   *
   * @param bpmnProcessId The process to start, scoped for the workflow module already
   * @param payload The shared aggregate values plus the aggregate-ID variable, which is
   *          the only way back from the instance to the workflow
   * @param executionMode {@link ExecutionMode#PREFLIGHT_CHECK} to ask before the commit,
   *          {@link ExecutionMode#SYNC} to really start the workflow after it
   */
  public PeaStartProcessCommand(
      final String bpmnProcessId,
      final Map<String, Object> payload,
      final ExecutionMode executionMode) {

    this.bpmnProcessId = bpmnProcessId;
    this.payload = payload;
    this.executionMode = executionMode;

  }

  /**
   * What the engine is asked to start. The API calls it a {@code definitionKey}. This
   * adapter puts the scoped BPMN process id there (see decision 2 in the repository's
   * DECISIONS.md).
   *
   * @return The BPMN process id (the Process-Engine-API's process {@code definitionKey})
   *         to start.
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  @Override
  public Map<String, Object> get() {

    return payload;

  }

  @Override
  public ExecutionMode executionMode() {

    return executionMode;

  }

}
