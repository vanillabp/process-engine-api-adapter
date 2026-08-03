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

  public PeaCompleteTaskCmd(
      final String taskId) {

    super(taskId);

  }

  @Override
  public ExecutionMode executionMode() {

    return ExecutionMode.SYNC;

  }

}
