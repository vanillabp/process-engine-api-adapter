package io.vanillabp.pea.observation;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PeaUserTaskObserver}s of one adapter id, and the one place they are called from.
 * So what a failing observer means for the task it watches is decided once, for both
 * platforms.
 * <p>
 * An observer which throws makes the delivery fail. Every other observer is told first. So
 * who hears about a task does not depend on the order of the list. Then the first failure
 * leaves this class, and the failures of the others are attached as suppressed exceptions. A
 * failure while DESCRIBING the task is the same case: an observer which gets nothing to see
 * could do nothing either. Decision 12 in the repository's DECISIONS.md says why a swallowed
 * failure was worse than a disturbed delivery.
 * <p>
 * If an application registered no observer, no observation is ever built. The callers hand
 * over a {@link Supplier}, and nobody calls it while the list is empty.
 * <p>
 * The order is the one the platform worked out: on Spring Boot the ordered bean stream, on
 * Quarkus the order in which ArC lists the beans. Each observer is told about a task on its
 * own, so an observer must not rely on another one having run first.
 */
public final class PeaUserTaskObservers {

  private static final Logger log = LoggerFactory.getLogger(PeaUserTaskObservers.class);

  private final String adapterId;

  private final List<PeaUserTaskObserver> observers;

  private PeaUserTaskObservers(
      final String adapterId,
      final List<PeaUserTaskObserver> observers) {

    this.adapterId = adapterId;
    this.observers = observers;

  }

  /**
   * The observers of one adapter id, as a value which can be handed around. A missing
   * collection means the same as an empty one here: an application which does not watch.
   *
   * @param adapterId The adapter id whose deliveries these observers are told about. The
   *          message of a failure names it, to help find the observer which broke
   * @param observers What the platform module collected, possibly <code>null</code> or empty
   * @return The observers of one adapter id, never <code>null</code>
   */
  public static PeaUserTaskObservers of(
      final String adapterId,
      final Collection<PeaUserTaskObserver> observers) {

    return new PeaUserTaskObservers(
        adapterId, observers == null
            ? List.of()
            : List.copyOf(observers));

  }

  /**
   * Asked before an observation is built. Building one loads the values the engine
   * delivered, and nobody should pay for that if nobody reads them.
   *
   * @return Whether nobody watches. That is the case for an application which knows nothing
   *         about observers
   */
  public boolean isEmpty() {

    return observers.isEmpty();

  }

  /**
   * Names the observers, so the startup line says who watches. If the log does not show
   * the observers, somebody will look for them in a debugger.
   *
   * @return The observers by class name, for the line said once at startup
   */
  public List<String> names() {

    return observers
        .stream()
        .map(observer -> observer.getClass().getName())
        .toList();

  }

  /**
   * Tells every observer that a user task was delivered. The observation is built once, and
   * only if somebody watches. That is why it arrives as a supplier.
   *
   * @param workflowModuleId The workflow module the task belongs to, for the message of a
   *          failure
   * @param taskId The delivered task, for the message of a failure
   * @param observation What the engine delivered, built only if somebody watches
   * @throws PeaUserTaskObserverFailure If an observer failed or the task could not be
   *           described
   */
  public void delivered(
      final String workflowModuleId,
      final String taskId,
      final Supplier<PeaUserTaskObservation> observation) {

    notifyEach(
        "delivery", workflowModuleId, taskId, observation, PeaUserTaskObserver::userTaskDelivered);

  }

  /**
   * Tells every observer that a user task the engine had delivered is gone. The reason, in
   * the engine's own words, travels with the observation and is passed on as it is. What
   * the words mean differs from engine to engine behind this API.
   *
   * @param workflowModuleId The workflow module the task belongs to, for the message of a
   *          failure
   * @param taskId The terminated task, for the message of a failure
   * @param observation What the engine withdrew, built only if somebody watches
   * @throws PeaUserTaskObserverFailure If an observer failed or the task could not be
   *           described
   */
  public void terminated(
      final String workflowModuleId,
      final String taskId,
      final Supplier<PeaUserTaskObservation> observation) {

    notifyEach(
        "termination", workflowModuleId, taskId, observation, PeaUserTaskObserver::userTaskTerminated);

  }

  private void notifyEach(
      final String what,
      final String workflowModuleId,
      final String taskId,
      final Supplier<PeaUserTaskObservation> supplier,
      final BiConsumer<PeaUserTaskObserver, PeaUserTaskObservation> call) {

    if (observers.isEmpty()) {
      return;
    }
    final PeaUserTaskObservation observation;
    try {
      observation = supplier.get();
    } catch (final Exception e) {
      throw reported(
          new PeaUserTaskObserverFailure(
              ("Process-Engine-API adapter '%s': the %s of user task '%s' of workflow module '%s' "
                  + "could not be described for its observers (%s)! None of them was told, so this "
                  + "%s is reported as failed.")
                  .formatted(adapterId, what, taskId, workflowModuleId, String.join(", ", names()), what), e));
    }
    PeaUserTaskObserverFailure firstFailure = null;
    for (final var observer : observers) {
      try {
        call.accept(observer, observation);
      } catch (final Exception e) {
        final var failure = failedOn(what, observer, observation, e);
        if (firstFailure == null) {
          firstFailure = failure;
        } else {
          firstFailure.addSuppressed(failure);
        }
      }
    }
    if (firstFailure != null) {
      throw reported(firstFailure);
    }

  }

  private PeaUserTaskObserverFailure failedOn(
      final String what,
      final PeaUserTaskObserver observer,
      final PeaUserTaskObservation observation,
      final Exception cause) {

    return new PeaUserTaskObserverFailure(
        ("Process-Engine-API adapter '%s': the user-task observer '%s' failed on the %s of task "
            + "'%s' (task definition '%s' of workflow module '%s')! The other observers were told, "
            + "and this %s is reported as failed rather than passed off as done.")
            .formatted(
                observation.adapterId(),
                observer.getClass().getName(),
                what,
                observation.taskId(),
                observation.taskDefinition(),
                observation.workflowModuleId(),
                what), cause);

  }

  /**
   * Logs the failure here, as well as throwing it. What an engine behind this API does with
   * a handler which throws is up to that engine. The reference implementation of the API for
   * an embedded Camunda 7 logs only the message of the failure. It leaves out the stack trace
   * and the suppressed failures of the other observers. That is too little to find the
   * observer which broke. So the whole failure is logged where it was built.
   *
   * @param failure What the delivery or termination fails with
   * @return The same failure, to be thrown by the caller
   */
  private static PeaUserTaskObserverFailure reported(
      final PeaUserTaskObserverFailure failure) {

    log.error(failure.getMessage(), failure);
    return failure;

  }

}
