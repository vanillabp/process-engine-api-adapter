package io.vanillabp.pea.processservice;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.process.StartProcessCommand;

/**
 * The adapter's own {@link StartProcessCommand} implementation.
 * <p>
 * The Process-Engine-API's built-in start commands (e.g. {@code StartProcessByDefinitionCmd})
 * are Kotlin {@code data class}es that do <b>not</b> override
 * {@code ExecutionModeAware.executionMode()} - so their execution mode is always the default
 * ({@link ExecutionMode#DEFAULT}) and cannot be set to
 * {@link ExecutionMode#PREFLIGHT_CHECK} or {@link ExecutionMode#SYNC}. Because
 * VanillaBP's two-phase start needs exactly these two modes, the adapter has to supply its
 * own {@link StartProcessCommand} carrying the mode. See {@code GAPS.md}.
 * <p>
 * The command also carries the BPMN process id (the Process-Engine-API's
 * {@code definitionKey}) and the process variables. The workflow-aggregate id is passed as
 * an ordinary payload variable (there is no dedicated business-key/correlation slot on the
 * start command - see {@code GAPS.md}).
 */
public final class PeaStartProcessCommand implements StartProcessCommand {

  private final String bpmnProcessId;

  private final Map<String, Object> payload;

  private final ExecutionMode executionMode;

  public PeaStartProcessCommand(
      final String bpmnProcessId,
      final Map<String, Object> payload,
      final ExecutionMode executionMode) {

    this.bpmnProcessId = bpmnProcessId;
    this.payload = payload;
    this.executionMode = executionMode;

  }

  /**
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
