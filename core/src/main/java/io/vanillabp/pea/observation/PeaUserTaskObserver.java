package io.vanillabp.pea.observation;

/**
 * How something outside this adapter watches the user tasks the Process-Engine-API delivers
 * to it. Mostly that is a VanillaBP extension. This was written for the Business Cockpit.
 * <p>
 * The Process-Engine-API delivers a task to exactly ONE subscription. Its engine adapters
 * pick the first subscription which matches a task and remember it as the one for that
 * task. So a second subscription for the same task definition either sees nothing, or takes
 * the task away from the workflow application. The order in which the two were registered
 * decides which. To watch along, something has to be called by the adapter's own
 * subscription, and only the adapter can arrange that. This is where it happens.
 * <p>
 * <b>It observes.</b> An observer is told what the engine delivered and what the engine took
 * back. It acts on neither the task nor the engine. It cannot claim, complete, cancel or
 * change a task, and it cannot stop a delivery from reaching the application. A user task is
 * completed through {@code ProcessService#completeUserTask}, as everywhere else. The only
 * thing of an observer the engine gets to see is a failure. What that means is said below.
 * <p>
 * <b>Every delivery, not only the served ones.</b> The adapter calls the observers BEFORE it
 * checks whether a {@code @WorkflowTask} method of the application claims the task. A task
 * list shows a user task whether or not the application has code for it.
 * <p>
 * <b>One list, called for every adapter id.</b> An application configures exactly one adapter
 * id of this type. A second one stops the startup, because this API cannot tell two engines
 * apart ({@code GAPS.md}, entry 14). So there is one list. The observation names the adapter
 * it came from, and an observer reports under that name. See decision 9 in the repository's
 * DECISIONS.md.
 * <p>
 * <b>An observer which fails disturbs.</b> If one throws, the log names its class, its task
 * and its workflow module. The other observers are told all the same. Then the delivery
 * fails towards the engine. A failure while the task is described for the observers does the
 * same. What the engine does with a failed delivery is up to the engine. The API says nothing
 * about it ({@code GAPS.md}, entry 25). Its reference implementation for an embedded
 * Camunda 7 fetches the task again on its next cycle. The user task stays where it was, and
 * the observer gets another try. So an observer has to cope with being called again after it
 * failed. What it does must not depend on how often it ran. See decision 12 in the
 * repository's DECISIONS.md.
 * <p>
 * The application's own notification does not suffer from this. The adapter runs the
 * {@code @WorkflowTask} method of a delivery before the failure of an observer leaves the
 * handler. The core knows a repeated delivery by its task id, so the method is not called
 * twice for one task.
 * <p>
 * On Spring Boot an observer is a bean of this type, on Quarkus a CDI bean of it. Both
 * platform modules collect them and hand them to the deployment service. The deployment
 * service passes them on to the handlers of its user-task subscriptions.
 *
 * @see PeaUserTaskObservers
 */
public interface PeaUserTaskObserver {

  /**
   * A user task was delivered, so it exists. {@link PeaUserTaskObservation#reason()} says
   * why the engine reported it, if the engine names a reason at all. It is a first delivery
   * ({@code create}), or a repeated one because something about the task changed
   * ({@code assign}, {@code update}).
   *
   * @param observation What the engine delivered
   */
  void userTaskDelivered(
      PeaUserTaskObservation observation);

  /**
   * A user task the engine had delivered is gone. {@link PeaUserTaskObservation#reason()}
   * gives the reason in the engine's own words. What the words mean is up to the engine. The
   * reference adapter for an embedded Camunda 7 says {@code complete} if the task was
   * finished through the completion API, and {@code delete} for everything else it notices.
   * So a task somebody finished in a task list arrives as {@code delete}, just like a
   * cancelled one. This adapter passes the reason on as it is.
   * <p>
   * A termination carries no payload, so it has no workflow aggregate id. It names the BPMN
   * process only if the meta map of the engine names it or the subscription serves a single
   * process.
   *
   * @param observation What the engine reported about the terminated task
   */
  void userTaskTerminated(
      PeaUserTaskObservation observation);

}
