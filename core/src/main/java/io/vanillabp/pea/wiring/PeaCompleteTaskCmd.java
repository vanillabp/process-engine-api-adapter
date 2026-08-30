package io.vanillabp.pea.wiring;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;

/**
 * A {@code CompleteTaskCmd} carrying {@link ExecutionMode#SYNC}: completing a task
 * ADVANCES the process and happens AFTER the handler's local transaction committed
 * - per the two-phase rules this is the phase-two shape ({@code SYNC}: embedded
 * engines execute in the caller's thread, remote engines write an outbox entry).
 * The API's built-in command cannot carry a non-default execution mode (see
 * {@code GAPS.md}), so the adapter subclasses it.
 * <p>
 * Why a subclass is needed to carry the execution mode is decision 4 in the repository's
 * DECISIONS.md.
 */
public class PeaCompleteTaskCmd extends CompleteTaskCmd {

  private final ExecutionMode executionMode;

  public PeaCompleteTaskCmd(
      final String taskId) {

    this(taskId, ExecutionMode.SYNC);

  }

  /**
   * The completion carrying the aggregate state shared with the engine - a gateway right
   * behind the completed task has to see the values the
   * {@code @WorkflowTask} method produced.
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
   * @param taskId The task to complete
   * @param executionMode {@link ExecutionMode#SYNC} for the actual completion
   *        (phase two) or {@link ExecutionMode#PREFLIGHT_CHECK} for the
   *        non-advancing phase-one existence check / awareness probe
   */
  public PeaCompleteTaskCmd(
      final String taskId,
      final ExecutionMode executionMode) {

    super(taskId);
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
