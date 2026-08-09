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
   * The BPMN error carrying the aggregate state shared with the engine (story
   * 28b) - the error boundary's outgoing path may branch on it.
   *
   * @param taskId The task to cancel
   * @param errorCode The BPMN error code
   * @param errorMessage The BPMN error message
   * @param payload The shared values plus the aggregate-ID variable
   */
  public PeaCompleteTaskByErrorCmd(
      final String taskId,
      final String errorCode,
      final String errorMessage,
      final java.util.Map<String, Object> payload) {

    super(taskId, errorCode, errorMessage, payload);
    this.executionMode = ExecutionMode.SYNC;

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
  // javac warns that the overridden method is a bridge: 'executionMode()' is a default
  // method of the Kotlin interface 'ExecutionModeAware', materialized as a bridge in the
  // superclass. The warning is unavoidable - @SuppressWarnings does not cover it - and the
  // API offers no constructor parameter to set the mode instead.
  public ExecutionMode executionMode() {

    return executionMode;

  }

}
