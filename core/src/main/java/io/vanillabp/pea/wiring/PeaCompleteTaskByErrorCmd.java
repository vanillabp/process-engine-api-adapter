package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;

/**
 * A {@code CompleteTaskByErrorCmd} carrying {@link ExecutionMode#SYNC} - see
 * {@link PeaCompleteTaskCmd} for the reasoning.
 */
public class PeaCompleteTaskByErrorCmd extends CompleteTaskByErrorCmd {

  private final ExecutionMode executionMode;

  public PeaCompleteTaskByErrorCmd(
      final String taskId,
      final String errorCode,
      final String errorMessage) {

    this(taskId, errorCode, errorMessage, ExecutionMode.SYNC);

  }

  /**
   * @param taskId The task to cancel
   * @param errorCode The BPMN error code
   * @param errorMessage The BPMN error message
   * @param executionMode {@link ExecutionMode#SYNC} for the actual cancellation
   *        (phase two) or {@link ExecutionMode#PREFLIGHT_CHECK} for the
   *        non-advancing phase-one existence check
   */
  public PeaCompleteTaskByErrorCmd(
      final String taskId,
      final String errorCode,
      final String errorMessage,
      final ExecutionMode executionMode) {

    super(taskId, errorCode, errorMessage);
    this.executionMode = executionMode;

  }

  @Override
  public ExecutionMode executionMode() {

    return executionMode;

  }

}
