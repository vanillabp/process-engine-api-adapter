package io.vanillabp.pea.wiring;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;

/**
 * A {@code CompleteTaskByErrorCmd} which carries {@link ExecutionMode#SYNC}. The reason is
 * the same as for {@link PeaCompleteTaskCmd}.
 * <p>
 * Why the execution mode needs a subclass is decision 4 in the repository's DECISIONS.md.
 */
public class PeaCompleteTaskByErrorCmd extends CompleteTaskByErrorCmd {

  private final ExecutionMode executionMode;

  /**
   * The BPMN error without any values. A task raises this one where the model branches on
   * the error code alone.
   *
   * @param taskId The task to cancel
   * @param errorCode The BPMN error code
   * @param errorMessage The BPMN error message
   */
  public PeaCompleteTaskByErrorCmd(
      final String taskId,
      final String errorCode,
      final String errorMessage) {

    this(taskId, errorCode, errorMessage, ExecutionMode.SYNC);

  }

  /**
   * The BPMN error which carries the aggregate state shared with the engine. The path out
   * of the error boundary event may branch on it.
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
      final Map<String, Object> payload) {

    super(taskId, errorCode, errorMessage, payload);
    this.executionMode = ExecutionMode.SYNC;

  }

  /**
   * The BPMN error in the phase the caller is in.
   *
   * @param taskId The task to cancel
   * @param errorCode The BPMN error code
   * @param errorMessage The BPMN error message
   * @param executionMode {@link ExecutionMode#SYNC} for the real cancellation in phase
   *        two, or {@link ExecutionMode#PREFLIGHT_CHECK} for the check in phase one, which
   *        only asks whether the task exists and moves nothing on
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
  // javac warns that this overrides a bridge method. 'executionMode()' is a default method
  // of the Kotlin interface 'ExecutionModeAware', and the superclass turns it into a bridge.
  // @SuppressWarnings does not silence this warning. The API has no constructor parameter
  // for the mode, so overriding is the only way.
  public ExecutionMode executionMode() {

    return executionMode;

  }

}
