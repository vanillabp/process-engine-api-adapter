package io.vanillabp.pea.observation;

/**
 * The failure of an observer of a user task, which makes the delivery it watched fail.
 * <p>
 * It is thrown out of the handler of the subscription. So the engine behind the
 * Process-Engine-API learns that this delivery did not work. The message names the observer,
 * the task and the workflow module. That is what somebody needs to find the observer which
 * broke. If several observers failed on the same task, this is the first one, and the others
 * are attached as suppressed exceptions. See decision 12 in the repository's DECISIONS.md.
 */
public class PeaUserTaskObserverFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Only {@link PeaUserTaskObservers} builds it. It is the one place which knows that an
   * observer was asked, and what about.
   *
   * @param message What broke, named the way somebody looking for it needs it
   * @param cause What the observer threw, or what building the observation threw
   */
  PeaUserTaskObserverFailure(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

}
