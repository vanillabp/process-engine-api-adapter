package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;

/**
 * A {@code FailTaskCmd} carrying {@link ExecutionMode#SYNC} - see
 * {@link PeaCompleteTaskCmd} for the reasoning. Retries and retry timeout are left
 * to the engine's defaults (<code>null</code>).
 */
public class PeaFailTaskCmd extends FailTaskCmd {

  public PeaFailTaskCmd(
      final String taskId,
      final String reason,
      final String errorDetails) {

    super(taskId, reason, errorDetails, null, null);

  }

  @Override
  public ExecutionMode executionMode() {

    return ExecutionMode.SYNC;

  }

}
