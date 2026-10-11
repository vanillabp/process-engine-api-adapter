package io.vanillabp.pea.wiring;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;

/**
 * A {@code CompleteTaskCmd} which carries {@link ExecutionMode#SYNC}. Completing a task
 * moves the process on, and it happens after the local transaction of the handler has
 * committed. Under the two-phase rules this is phase two, and phase two means {@code SYNC}:
 * an embedded engine runs the command in the caller's thread, a remote engine writes an
 * outbox entry. The command the API brings cannot carry any mode but the default (see
 * {@code GAPS.md}), so the adapter subclasses it.
 * <p>
 * Why the execution mode needs a subclass is decision 4 in the repository's DECISIONS.md.
 */
public class PeaCompleteTaskCmd extends CompleteTaskCmd {

  private final ExecutionMode executionMode;

  /**
   * A completion which carries nothing but the task. It is for a caller which shares no
   * aggregate values at all.
   *
   * @param taskId The task to complete
   */
  public PeaCompleteTaskCmd(
      final String taskId) {

    this(taskId, ExecutionMode.SYNC);

  }

  /**
   * The completion which carries the aggregate state shared with the engine. A gateway right
   * after the completed task has to see the values the {@code @WorkflowTask} method wrote.
   *
   * @param taskId The task to complete
   * @param payload The shared values plus the aggregate-ID variable
   */
  public PeaCompleteTaskCmd(
      final String taskId,
      final Map<String, Object> payload) {

    super(taskId, payload);
    this.executionMode = ExecutionMode.SYNC;

  }

  /**
   * The completion in the phase the caller is in.
   *
   * @param taskId The task to complete
   * @param executionMode {@link ExecutionMode#SYNC} for the real completion in phase two,
   *        or {@link ExecutionMode#PREFLIGHT_CHECK} for the check in phase one, which only
   *        asks whether the task exists or is known and moves nothing on
   */
  public PeaCompleteTaskCmd(
      final String taskId,
      final ExecutionMode executionMode) {

    super(taskId);
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
