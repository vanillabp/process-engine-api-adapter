package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;

/**
 * A {@code FailTaskCmd} carrying {@link ExecutionMode#SYNC} - see
 * {@link PeaCompleteTaskCmd} for the reasoning. Retries and retry timeout are left
 * to the engine's defaults (<code>null</code>).
 * <p>
 * Why a subclass is needed to carry the execution mode is decision 4 in the repository's
 * DECISIONS.md.
 */
public class PeaFailTaskCmd extends FailTaskCmd {

  /**
   * Reports that the handler of a task threw. The number of retries and their timeout stay
   * empty, so the engine behind the API applies whatever it does by default.
   *
   * @param taskId The task which could not be handled
   * @param reason The short reason, which is what a task list or an incident shows
   * @param errorDetails The long form, usually the stack trace
   */
  public PeaFailTaskCmd(
      final String taskId,
      final String reason,
      final String errorDetails) {

    super(taskId, reason, errorDetails, null, null);

  }

  @Override
  // javac warns that the overridden method is a bridge: 'executionMode()' is a default
  // method of the Kotlin interface 'ExecutionModeAware', materialized as a bridge in the
  // superclass. The warning is unavoidable - @SuppressWarnings does not cover it - and the
  // API offers no constructor parameter to set the mode instead.
  public ExecutionMode executionMode() {

    return ExecutionMode.SYNC;

  }

}
