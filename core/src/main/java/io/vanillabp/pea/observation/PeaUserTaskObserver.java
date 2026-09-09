package io.vanillabp.pea.observation;

/**
 * How something outside this adapter watches the user tasks the Process-Engine-API delivers
 * to it - a VanillaBP extension above all, the Business Cockpit being the one this was
 * written for.
 * <p>
 * The Process-Engine-API delivers a task to exactly ONE subscription: its engine adapters
 * pick the first subscription matching a task and remember that one as the task's. A second
 * subscription for the same task definition therefore either sees nothing or takes the task
 * away from the workflow application, decided by the order the two were registered in. So
 * watching along means being called by the adapter's own subscription, and only the adapter
 * can arrange that. This is where that happens.
 * <p>
 * <b>It observes.</b> An observer is told what the engine delivered and what the engine
 * withdrew, and nothing it does reaches the engine: it cannot claim, complete, cancel or
 * change a task, and it cannot stop a delivery from reaching the application. Completing a
 * user task goes through {@code ProcessService#completeUserTask} like everywhere else.
 * <p>
 * <b>Every delivery, not only the served ones.</b> The adapter calls the observers BEFORE it
 * checks whether a {@code @WorkflowTask} method of the application claims the task, because
 * a task list shows a user task whether or not the application has code for it.
 * <p>
 * <b>One list, called for every adapter id.</b> An application configures exactly one adapter
 * id of this type - a second one ends the boot, because this API cannot tell two engines apart
 * ({@code GAPS.md}, entry 14) - so there is one list and the observation names the adapter it
 * came from, which is what an observer reports under. See decision 9 in the repository's
 * DECISIONS.md.
 * <p>
 * <b>Failing is the observer's own business.</b> An observer which throws is logged with its
 * class and its task; the delivery reaches the application and the remaining observers are
 * called anyway.
 * <p>
 * On Spring Boot an observer is a bean of this type, on Quarkus a CDI bean of it. Both
 * platform modules collect them and hand them to the deployment service, which passes them
 * on to the handlers of its user-task subscriptions.
 *
 * @see PeaUserTaskObservers
 */
public interface PeaUserTaskObserver {

  /**
   * A user task was delivered: it exists, and what the engine says about the reason it was
   * reported for is {@link PeaUserTaskObservation#reason()} - a first delivery
   * ({@code create}) or a repetition because something about it changed ({@code assign},
   * {@code update}), where the engine names one at all.
   *
   * @param observation What the engine delivered
   */
  void userTaskDelivered(
      PeaUserTaskObservation observation);

  /**
   * A user task the engine had delivered is gone, and the engine's own word for why is
   * {@link PeaUserTaskObservation#reason()}. What that word means is the engine's business:
   * the reference adapter for an embedded Camunda 7 says {@code complete} where the task was
   * finished through the completion API and {@code delete} for everything else it notices,
   * so a task somebody finished in a task list arrives as {@code delete} like a cancelled
   * one. This adapter passes the reason on uninterpreted.
   * <p>
   * A termination carries no payload and therefore no workflow aggregate id, and the BPMN
   * process only where the engine's meta names it or the subscription serves a single
   * process.
   *
   * @param observation What the engine reported about the terminated task
   */
  void userTaskTerminated(
      PeaUserTaskObservation observation);

}
