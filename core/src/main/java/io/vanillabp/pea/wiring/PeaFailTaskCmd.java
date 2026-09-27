package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;

/**
 * A {@code FailTaskCmd} carrying {@link ExecutionMode#SYNC} - see
 * {@link PeaCompleteTaskCmd} for the reasoning. The retry timeout is always left to the
 * engine's default, and so is the retry count of the ordinary failure: a handler which threw
 * once may well work the next time. The one failure which says otherwise is built by
 * {@link #withoutFurtherAttempts(String, String)}.
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

  /**
   * Reports a failure the same delivery would meet again, so the engine is asked not to
   * repeat it.
   * <p>
   * The retry count of the command is the only thing the Process-Engine-API offers a
   * subscriber for that, and it is optional: an engine which reads it is asked for zero
   * retries, which is what the engines with an incident concept turn into one, and an engine
   * which ignores it falls back to the default it would have applied anyway. So this is never
   * worse than leaving the count empty, and where it is read the task stops circling.
   *
   * @param taskId The task which cannot be handled by this application
   * @param reason Why, in the words a task list or an incident shows
   * @return The command
   */
  public static PeaFailTaskCmd withoutFurtherAttempts(
      final String taskId,
      final String reason) {

    return new PeaFailTaskCmd(taskId, reason, null, 0);

  }

  private PeaFailTaskCmd(
      final String taskId,
      final String reason,
      final String errorDetails,
      final Integer retryCount) {

    super(taskId, reason, errorDetails, retryCount, null);

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
