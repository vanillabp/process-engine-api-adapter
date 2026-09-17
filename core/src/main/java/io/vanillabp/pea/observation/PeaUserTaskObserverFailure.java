package io.vanillabp.pea.observation;

/**
 * What an observer of a user task cost the delivery it watched.
 * <p>
 * It leaves the subscription's handler, so the engine behind the Process-Engine-API learns
 * that this delivery did not work out. The message names the observer, the task and the
 * workflow module, because that is what somebody needs to find the observer which broke.
 * Where several observers failed on the same task, this is the first of them and the others
 * are attached as suppressed exceptions. See decision 12 in the repository's DECISIONS.md.
 */
public class PeaUserTaskObserverFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Built by {@link PeaUserTaskObservers} alone - it is the one place which knows that an
   * observer was asked and what it was asked about.
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
