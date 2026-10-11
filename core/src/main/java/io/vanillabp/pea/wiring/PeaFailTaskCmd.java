package io.vanillabp.pea.wiring;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;

/**
 * A {@code FailTaskCmd} which carries {@link ExecutionMode#SYNC}. The reason is the same as
 * for {@link PeaCompleteTaskCmd}. The engine always picks the retry timeout. For an ordinary
 * failure it picks the retry count too, because a handler which threw once may well work the
 * next time. Only {@link #withoutFurtherAttempts(String, String)} builds a failure which
 * asks for no more attempts.
 * <p>
 * Why the execution mode needs a subclass is decision 4 in the repository's DECISIONS.md.
 */
public class PeaFailTaskCmd extends FailTaskCmd {

  /**
   * Reports that the handler of a task threw. The number of retries and their timeout stay
   * empty, so the engine behind the API uses its own defaults.
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
   * Reports a failure which the same delivery would run into again. So the engine is asked
   * not to repeat it.
   * <p>
   * The retry count of the command is the only way the Process-Engine-API gives a subscriber
   * to ask for that, and an engine may ignore it. An engine which reads it gets zero
   * retries, and an engine with incidents turns that into an incident. An engine which
   * ignores it uses the default it would have used anyway. So this is never worse than an
   * empty count, and where the engine reads it, the task stops coming back.
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
  // javac warns that this overrides a bridge method. 'executionMode()' is a default method
  // of the Kotlin interface 'ExecutionModeAware', and the superclass turns it into a bridge.
  // @SuppressWarnings does not silence this warning. The API has no constructor parameter
  // for the mode, so overriding is the only way.
  public ExecutionMode executionMode() {

    return ExecutionMode.SYNC;

  }

}
