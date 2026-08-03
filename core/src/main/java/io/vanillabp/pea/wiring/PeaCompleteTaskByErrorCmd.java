package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;

/**
 * A {@code CompleteTaskByErrorCmd} carrying {@link ExecutionMode#SYNC} - see
 * {@link PeaCompleteTaskCmd} for the reasoning.
 */
public class PeaCompleteTaskByErrorCmd extends CompleteTaskByErrorCmd {

  public PeaCompleteTaskByErrorCmd(
      final String taskId,
      final String errorCode,
      final String errorMessage) {

    super(taskId, errorCode, errorMessage);

  }

  @Override
  public ExecutionMode executionMode() {

    return ExecutionMode.SYNC;

  }

}
