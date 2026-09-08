package io.vanillabp.pea.observation;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PeaUserTaskObserver}s of one adapter id and the one place they are called from,
 * so that what an observer may cost the task it watches is decided once for both platforms.
 * <p>
 * Two properties this class is here for. An observer which throws is caught, logged with its
 * class and its task, and the next one is called anyway - the user task itself is the
 * application's business and must not depend on who watches it. And an application which
 * registered no observer never builds an observation at all: the callers hand over a
 * {@link Supplier}, which stays unread while the list is empty.
 * <p>
 * The order is the one the platform resolved - on Spring Boot the ordered bean stream, on
 * Quarkus the order ArC lists the beans in. Observers are told about the same task
 * independently of each other, so an observer must not rely on another having run first.
 */
public final class PeaUserTaskObservers {

  private static final Logger log = LoggerFactory.getLogger(PeaUserTaskObservers.class);

  private static final PeaUserTaskObservers NOBODY = new PeaUserTaskObservers(List.of());

  private final List<PeaUserTaskObserver> observers;

  private PeaUserTaskObservers(
      final List<PeaUserTaskObserver> observers) {

    this.observers = observers;

  }

  /**
   * @param observers What the platform module collected, possibly <code>null</code> or empty
   * @return The observers of one adapter id, never <code>null</code>
   */
  public static PeaUserTaskObservers of(
      final Collection<PeaUserTaskObserver> observers) {

    if ((observers == null) || observers.isEmpty()) {
      return NOBODY;
    }
    return new PeaUserTaskObservers(List.copyOf(observers));

  }

  /**
   * @return Whether nobody watches - the state an application which knows nothing about this
   *         seam is in
   */
  public boolean isEmpty() {

    return observers.isEmpty();

  }

  /**
   * @return The observers by class name, for the line said once at startup
   */
  public List<String> names() {

    return observers
        .stream()
        .map(observer -> observer.getClass().getName())
        .toList();

  }

  /**
   * @param observation What the engine delivered, built only if somebody watches
   */
  public void delivered(
      final Supplier<PeaUserTaskObservation> observation) {

    notifyEach("delivery", observation, PeaUserTaskObserver::userTaskDelivered);

  }

  /**
   * @param observation What the engine withdrew, built only if somebody watches
   */
  public void terminated(
      final Supplier<PeaUserTaskObservation> observation) {

    notifyEach("termination", observation, PeaUserTaskObserver::userTaskTerminated);

  }

  private void notifyEach(
      final String what,
      final Supplier<PeaUserTaskObservation> supplier,
      final BiConsumer<PeaUserTaskObserver, PeaUserTaskObservation> call) {

    if (observers.isEmpty()) {
      return;
    }
    final var observation = supplier.get();
    for (final var observer : observers) {
      try {
        call.accept(observer, observation);
      } catch (final Exception e) {
        log.error(
            "Process-Engine-API adapter '{}': the user-task observer '{}' failed on the {} of "
                + "task '{}' (task definition '{}' of workflow module '{}')! The task itself is "
                + "unaffected and the remaining observers are called.",
            observation.adapterId(),
            observer.getClass().getName(),
            what,
            observation.taskId(),
            observation.taskDefinition(),
            observation.workflowModuleId(),
            e);
      }
    }

  }

}
