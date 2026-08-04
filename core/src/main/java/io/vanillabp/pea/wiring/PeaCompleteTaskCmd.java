package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;

/**
 * A {@code CompleteTaskCmd} carrying {@link ExecutionMode#SYNC}: completing a task
 * ADVANCES the process and happens AFTER the handler's local transaction committed
 * - per the two-phase rules this is the phase-two shape ({@code SYNC}: embedded
 * engines execute in the caller's thread, remote engines write an outbox entry).
 * The API's built-in command cannot carry a non-default execution mode (see
 * {@code GAPS.md}), so the adapter subclasses it.
 */
public class PeaCompleteTaskCmd extends CompleteTaskCmd {

  private final ExecutionMode executionMode;

  public PeaCompleteTaskCmd(
      final String taskId) {

    this(taskId, ExecutionMode.SYNC);

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
  public ExecutionMode executionMode() {

    return executionMode;

  }

}
