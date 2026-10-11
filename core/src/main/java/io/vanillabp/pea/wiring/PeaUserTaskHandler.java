package io.vanillabp.pea.wiring;

import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObserverFailure;
import io.vanillabp.pea.observation.PeaUserTaskObservers;
import io.vanillabp.spi.service.TaskEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The handler of one USER-task subscription. A delivered user task is a NOTIFICATION. The
 * <code>&#64;WorkflowTask</code> method, if there is one, receives
 * {@link TaskEvent.Event#CREATED} with the ID of the user task (as <code>&#64;TaskId</code>).
 * Returning from the method never completes the task. The completion comes through
 * <code>ProcessService#completeUserTask</code>. If no method matches, the delivery is
 * skipped without a word, because user tasks are worked on through forms and task lists.
 * <p>
 * CANCELED cannot reach a <code>&#64;WorkflowTask</code> method. The termination callback
 * of the subscription carries the {@link TaskInformation} of the engine but no payload. So
 * there is no way to tell which workflow aggregate the notification belongs to (see
 * {@code GAPS.md}). What the termination does carry is the task, the reason of the engine
 * and the rest of its meta map. That reaches the {@link PeaUserTaskObserver}s of the
 * application through {@link #terminated(TaskInformation)}.
 */
@Slf4j
public class PeaUserTaskHandler implements TaskHandler {

  /**
   * Translates the prefixed task definition of this subscription back into the plain one.
   * May be <code>null</code>.
   */
  private final NameClashAvoidanceSupport scoping;

  private final String adapterId;

  private final String workflowModuleId;

  private final String externalFormReference;

  private final List<String> bpmnProcessIds;

  /**
   * The BPMN process ids the workflow module declares without deploying a model under them,
   * and whose task definitions make up the same name as this subscription. A notification is
   * never routed to them. But a notification which cannot be routed names them as one more
   * possible reason. Never <code>null</code>.
   */
  private final List<String> declaredBpmnProcessIds;

  /**
   * The BPMN element each process of this subscription delivers from, keyed by the plain BPMN
   * process id. It is read when the engine names no element itself, see
   * {@link #bpmnElementId(String, TaskInformation)}. Never <code>null</code>.
   */
  private final Map<String, String> bpmnElementIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * What this subscription asked the engine for. A user-task notification carries a payload
   * like every other delivery, and the payload is narrowed in the same way.
   */
  private final PeaFetchVariables.Selection fetchVariables;

  /**
   * Who watches the user tasks delivered to this subscription. Never <code>null</code>. If
   * the application registered no observer, this list is empty.
   */
  private final PeaUserTaskObservers observers;

  /**
   * The user-task subscription this handler serves. Use the generated
   * <code>PeaUserTaskHandler.builder()</code>. Some of these values may be left out, and in a
   * list of arguments this long nobody can tell which is which.
   *
   * @param adapterId The adapter whose subscription delivers here
   * @param workflowModuleId The workflow module the subscribed user tasks belong to
   * @param externalFormReference The form reference the engine delivers under
   * @param bpmnProcessIds The BPMN processes this subscription may deliver from
   * @param declaredBpmnProcessIds The ids the module declares without a model which share
   *          this subscription's name, or <code>null</code> for none
   * @param bpmnElementIds What the deployed models say a delivery of this subscription belongs
   *          to, per BPMN process, or <code>null</code> where no model says it
   * @param workflowTaskInvoker The core's runtime entry point
   * @param scoping Translates the engine's identifiers back, or <code>null</code>
   * @param fetchVariables What the subscription asked for, or <code>null</code> for everything
   * @param observers Who watches the deliveries, or <code>null</code> for nobody
   */
  @Builder
  public PeaUserTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String externalFormReference,
      final List<String> bpmnProcessIds,
      final List<String> declaredBpmnProcessIds,
      final Map<String, String> bpmnElementIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final NameClashAvoidanceSupport scoping,
      final PeaFetchVariables.Selection fetchVariables,
      final PeaUserTaskObservers observers) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.externalFormReference = externalFormReference;
    this.bpmnProcessIds = bpmnProcessIds;
    this.declaredBpmnProcessIds = declaredBpmnProcessIds == null
        ? List.of()
        : List.copyOf(declaredBpmnProcessIds);
    this.bpmnElementIds = bpmnElementIds == null
        ? Map.of()
        : Map.copyOf(bpmnElementIds);
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.scoping = scoping;
    this.fetchVariables = fetchVariables == null
        ? PeaFetchVariables.Selection.everything()
        : fetchVariables;
    this.observers = observers == null
        ? PeaUserTaskObservers.of(adapterId, List.of())
        : observers;

  }

  @Override
  public void accept(
      final TaskInformation taskInformation,
      final Map<String, ?> payload) {

    final var taskId = taskInformation.getTaskId();

    // a failed observer makes this delivery fail, but only after the application got its
    // notification. The engine repeats a failed delivery, and the observer needs that
    // repetition. The @WorkflowTask method of the application still runs only once per
    // task, because the core recognizes the repeated delivery by its task id
    PeaUserTaskObserverFailure observerFailure = null;
    try {
      final var bpmnProcessId = bpmnProcessIdOrNull(taskInformation);
      // the name under which the payload holds the id of the workflow aggregate. It is
      // asked for once, and a failure is not thrown yet. An observer is also told about a
      // delivery this application cannot place. The failure is kept for the strict path
      // below, because only there is it a defect
      String aggregateIdName = null;
      RuntimeException noAggregateIdName = null;
      if (bpmnProcessId != null) {
        try {
          aggregateIdName = workflowTaskInvoker.resolveWorkflowAggregateIdName(
              workflowModuleId, bpmnProcessId);
        } catch (final RuntimeException e) {
          noAggregateIdName = e;
        }
      }
      // whoever watches the user tasks of this application sees every one of them. The
      // checks below drop a delivery which cannot be routed, and one which no @WorkflowTask
      // method claims. A task list shows the user task in both cases
      final var observedAggregateIdName = aggregateIdName;
      try {
        observers.delivered(
            workflowModuleId, taskId,
            () -> observationOf(bpmnProcessId, observedAggregateIdName, taskInformation, payload));
      } catch (final PeaUserTaskObserverFailure failure) {
        observerFailure = failure;
      }
      if (bpmnProcessId == null) {
        throw ambiguousRouting(taskInformation);
      }
      // the registries of the core use the plain identifiers. So the key of this
      // subscription is translated back before the core is asked anything
      final var taskDefinition = plainTaskDefinition(bpmnProcessId);
      final var elementId = bpmnElementId(bpmnProcessId, taskInformation);
      if (!aMethodServesThisUserTask(bpmnProcessId, taskDefinition, elementId)) {
        log.trace(
            "Process-Engine-API adapter '{}': no @WorkflowTask handler for user task '{}' of BPMN "
                + "process '{}' - skipping the notification",
            adapterId,
            taskDefinition,
            bpmnProcessId);
        return;
      }
      if (noAggregateIdName != null) {
        throw noAggregateIdName;
      }
      final var aggregateId = payload.get(aggregateIdName);
      if (aggregateId == null) {
        throw new IllegalStateException(
            PeaFetchVariables.missingAggregateId(
                "User task", taskId, taskDefinition, bpmnProcessId, aggregateIdName, fetchVariables));
      }
      final var outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaUserTaskInvocationContext(
              adapterId, taskDefinition, elementId, String
                  .valueOf(aggregateId), taskId, payload, taskInformation, fetchVariables));
      if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
        throw new IllegalStateException(
            ("The @WorkflowTask method notified about user task '%s' (BPMN process '%s' of "
                + "workflow module '%s') threw a TaskException! User-task notification handlers "
                + "must not raise BPMN errors - route errors via ProcessService#cancelUserTask "
                + "instead.")
                .formatted(taskDefinition, bpmnProcessId, workflowModuleId));
      }
    } catch (final DeliveryOfAnUnknownWorkflowException refusal) {
      sayTheUserTaskBelongsSomewhereElse(taskId, refusal);
    } catch (final Exception e) {
      // a failing NOTIFICATION must not break the user task itself. The task stays
      // available through forms and task lists. The defect is logged as an error
      log.error(
          "Process-Engine-API adapter '{}': the CREATED notification for user task '{}' (form "
              + "reference '{}') failed! The user task itself stays available.",
          adapterId,
          taskId,
          externalFormReference,
          e);
    }
    if (observerFailure != null) {
      throw observerFailure;
    }

  }

  /**
   * Says that a delivered user task is about a workflow this application does not own, and
   * that nothing more can be done about it here.
   * <p>
   * A task subscription of this API matches a task type across the whole engine, and there
   * are no tenants. So two applications which deploy a BPMN process of the same name get the
   * user tasks of each other. The service-task side answers such a delivery at the engine,
   * with a failure which asks for no further attempt. This side cannot do that.
   * {@code UserTaskCompletionApi} offers completion and completion by error and nothing else,
   * and both would move the workflow of the other application on. So the notification ends
   * here, the user task stays where it is, and the log line is the whole answer.
   * <p>
   * Ending it here also keeps the delivery from coming back again and again. A notification
   * which throws goes back into the pull cycle of the engine. The engine then drops the
   * subscription for the task and offers it again at the next turn, for as long as both
   * applications run. Returning normally leaves the task delivered. So the message is
   * written once per activation and not once per cycle.
   * <p>
   * The stack trace is left out on purpose. The message of the refusal names the workflow,
   * the two situations it may be, and what to do in each. Nothing in the code of this
   * application is at fault.
   *
   * @param taskId The user task which belongs somewhere else
   * @param refusal What the core said about it
   */
  private void sayTheUserTaskBelongsSomewhereElse(
      final String taskId,
      final DeliveryOfAnUnknownWorkflowException refusal) {

    log.warn(
        "Process-Engine-API adapter '{}': user task '{}' (form reference '{}') is about a workflow "
            + "this application does not own - not notified. This API has no way to refuse a user "
            + "task, so the task stays untouched in the engine. The reason reads: {}",
        adapterId,
        taskId,
        externalFormReference,
        refusal.getMessage());

  }

  /**
   * Whether a <code>&#64;WorkflowTask</code> method serves this user task. It asks with BOTH
   * keys a task is wired by: the task definition and the element id. A method names one of
   * the two, <code>&#64;WorkflowTask(taskDefinition = ...)</code> or
   * <code>&#64;WorkflowTask(id = ...)</code>. Here the task definition of a user task is its
   * external FORM REFERENCE, so the two names differ more often than anywhere else.
   * <p>
   * When only the form reference was asked, a user task whose method names the element id
   * got the answer "nobody serves this", and the notification was skipped without a word.
   * That is worse than a notification which fails, because nothing happens at all.
   *
   * @param bpmnProcessId The plain BPMN process the delivery was routed to
   * @param taskDefinition The plain external form reference
   * @param elementId The BPMN element the delivery belongs to, or <code>null</code>
   * @return Whether a method serves it
   */
  private boolean aMethodServesThisUserTask(
      final String bpmnProcessId,
      final String taskDefinition,
      final String elementId) {

    if (workflowTaskInvoker.workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, taskDefinition)) {
      return true;
    }
    return (elementId != null) && workflowTaskInvoker
        .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, elementId);

  }

  /**
   * The BPMN element this notification belongs to. It is the second key a
   * <code>&#64;WorkflowTask</code> method is wired by. A notification which names no element
   * does not reach a method with <code>&#64;WorkflowTask(id = ...)</code>.
   * <p>
   * It is read the same way as on the service-task side, for the same reasons, see
   * {@link PeaTaskHandler}. If the engine fills the meta entry, the engine answers.
   * Otherwise the deployed model answers.
   *
   * @param bpmnProcessId The plain BPMN process the delivery was routed to
   * @param taskInformation What the engine said about the user task
   * @return The BPMN element id, or <code>null</code> where neither source names one
   */
  private String bpmnElementId(
      final String bpmnProcessId,
      final TaskInformation taskInformation) {

    final var named = PeaTaskMeta.text(taskInformation, PeaTaskMeta.BPMN_TASK_ID);
    return named != null
        ? named
        : bpmnElementIds.get(bpmnProcessId);

  }

  /**
   * The engine withdrew a user task it had delivered to this subscription. This method is
   * registered as the {@link TaskTerminationHandler} of the subscription. That is the
   * overload which gets the {@link TaskInformation} of the engine and not just the task id.
   * <p>
   * The adapter itself has nothing to do here. A CANCELED notification would need the
   * workflow aggregate, and a termination carries no payload. So the adapter only logs the
   * termination and hands it to the observers, with the reason.
   *
   * @param taskInformation What the engine says about the terminated task
   * @throws PeaUserTaskObserverFailure If an observer failed on this termination
   */
  public void terminated(
      final TaskInformation taskInformation) {

    final var taskId = taskInformation.getTaskId();
    log.debug(
        "Process-Engine-API adapter '{}': user task '{}' terminated ({})",
        adapterId,
        taskId,
        taskInformation
            .getMeta()
            .getOrDefault(TaskInformation.REASON, "no reason given"));
    try {
      final var bpmnProcessId = bpmnProcessIdOrNull(taskInformation);
      observers.terminated(workflowModuleId, taskId, () -> new PeaUserTaskObservation(
          adapterId, workflowModuleId, bpmnProcessId, plainTaskDefinition(bpmnProcessId),
          // a termination carries no payload, so the engine hands over no variable with
          // the aggregate id
          null, taskInformation, Map.of()));
    } catch (final PeaUserTaskObserverFailure failure) {
      // the observers are the only reason this callback exists. So if one of them fails,
      // the whole callback fails. It is thrown for the same reason as on a delivery
      throw failure;
    } catch (final Exception e) {
      // the same as for a failing notification: a termination callback which throws back
      // into the delivery thread of the engine helps nobody
      log.error(
          "Process-Engine-API adapter '{}': reporting the termination of user task '{}' (form "
              + "reference '{}') failed!",
          adapterId,
          taskId,
          externalFormReference,
          e);
    }

  }

  /**
   * What an observer gets. The identifiers are the PLAIN ones, as the BPMN and the
   * configuration of the application use them. The subscription uses the PREFIXED task
   * definition, and the engine reports the prefixed BPMN process id. Both are translated
   * back before anybody outside the adapter sees them (decision 2 in the repository's
   * DECISIONS.md).
   * <p>
   * <code>aggregateIdName</code> is what the caller resolved for this BPMN process. It is
   * <code>null</code> if the application declares no workflow aggregate for the process.
   * Two cases leave the observation without an aggregate id: this one, and a variable the
   * subscription did not ask the engine for. The observer then sees a task it cannot place
   * under a business case, which is better than no task at all. The path of the application
   * itself is strict about the same value. There, a delivery which a
   * <code>&#64;WorkflowTask</code> method claims without an aggregate id is a defect.
   *
   * @param bpmnProcessId The plain BPMN process the delivery was routed to, or
   *          <code>null</code> where it cannot be told
   * @param aggregateIdName The payload variable holding the aggregate's id, or
   *          <code>null</code>
   * @param taskInformation What the engine says about the task
   * @param payload What the engine delivered
   * @return The observation
   */
  private PeaUserTaskObservation observationOf(
      final String bpmnProcessId,
      final String aggregateIdName,
      final TaskInformation taskInformation,
      final Map<String, ?> payload) {

    final var aggregateId = aggregateIdName == null
        ? null
        : payload.get(aggregateIdName);
    return new PeaUserTaskObservation(
        adapterId, workflowModuleId, bpmnProcessId, plainTaskDefinition(bpmnProcessId), aggregateId == null
            ? null
            : String.valueOf(aggregateId), taskInformation,
        // the record makes the only defensive copy. This map is only read
        payloadAsDelivered(payload));

  }

  /**
   * The delivered payload, typed the way the observation declares it. The engine hands over a
   * map with an unknown value type, the record wants values of type <code>Object</code>. It is
   * the same map, only read through a wider type, and nobody writes to it through either.
   *
   * @param payload What the engine delivered
   * @return The same map
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadAsDelivered(
      final Map<String, ?> payload) {

    return (Map<String, Object>) payload;

  }

  /**
   * The PLAIN task definition of this subscription. Under {@code use-prefix}, the key of the
   * subscription is the prefixed one, and the prefix depends on the BPMN process.
   * <p>
   * Without a BPMN process, the prefix cannot be removed. But then there is no prefix to
   * remove either. Under {@code use-prefix}, two processes which share a form reference get
   * one subscription each, because their prefixed keys differ. So a subscription which
   * cannot tell its process belongs to a mode which adds no prefix.
   *
   * @param bpmnProcessId The plain BPMN process id, or <code>null</code>
   * @return The plain task definition
   */
  private String plainTaskDefinition(
      final String bpmnProcessId) {

    if ((scoping == null) || (bpmnProcessId == null)) {
      return externalFormReference;
    }
    return scoping.plainTaskDefinition(
        workflowModuleId, bpmnProcessId, externalFormReference, adapterId);

  }

  /**
   * The delivery cannot be routed. The engine named no BPMN process, and this subscription
   * serves several. The observers have been told before this is thrown. Whether a cockpit
   * shows a task is a different question from whether this application can dispatch it.
   *
   * @param taskInformation What the engine says about the task
   * @return The failure the notification ends with
   */
  private IllegalStateException ambiguousRouting(
      final TaskInformation taskInformation) {

    return new IllegalStateException(
        ("User task '%s' (form reference '%s') carries no meta entry '%s' and the form reference "
            + "is used by several BPMN processes of workflow module '%s' (%s)%s - the notification "
            + "cannot be routed! Either the Process-Engine-API implementation supplies the meta "
            + "entry or the form reference has to be unique across the module's processes%s.")
            .formatted(
                taskInformation.getTaskId(),
                externalFormReference,
                PeaTaskMeta.BPMN_PROCESS_ID,
                workflowModuleId,
                bpmnProcessIds
                    .stream()
                    .distinct()
                    .toList(),
                PeaRenamedProcesses.andTheIdsTheModuleOnlyDeclares(declaredBpmnProcessIds),
                PeaRenamedProcesses.orKeepDeployingTheOldModel(declaredBpmnProcessIds)));

  }

  /**
   * The BPMN process a delivery belongs to, as far as it can be told. The meta entry of the
   * engine names it. If the engine fills none, and this subscription has a single process,
   * that process is the answer. With several processes behind one form reference and no
   * meta entry, the question stays open. A delivery then ends in {@link #ambiguousRouting}.
   * A termination just has to live with it, see {@link #terminated(TaskInformation)}.
   * <p>
   * The meta entry holds the id the ENGINE knows, so
   * {@link PeaTaskMeta#plainBpmnProcessId} translates it back. The registries of the core
   * use the plain id, and so does everything this adapter hands out (decision 2 in the
   * repository's DECISIONS.md). The single process of a subscription is plain already,
   * because it is the one this adapter subscribed for.
   *
   * @param taskInformation What the engine says about the task
   * @return The plain BPMN process id, or <code>null</code>
   */
  private String bpmnProcessIdOrNull(
      final TaskInformation taskInformation) {

    final var fromMeta = PeaTaskMeta
        .plainBpmnProcessId(taskInformation, scoping, workflowModuleId, adapterId);
    if (fromMeta != null) {
      return fromMeta;
    }
    final var distinct = bpmnProcessIds
        .stream()
        .distinct()
        .toList();
    return distinct.size() == 1
        ? distinct.getFirst()
        : null;

  }

  /**
   * The neutral invocation context built from a delivered user task.
   */
  static class PeaUserTaskInvocationContext implements TaskInvocationContext {

    private final String externalFormReference;

    /**
     * The BPMN element this notification belongs to, or <code>null</code> - see
     * {@link PeaUserTaskHandler#bpmnElementId(String, TaskInformation)}.
     */
    private final String bpmnElementId;

    private final String workflowAggregateId;

    private final String taskId;

    private final Map<String, ?> payload;

    /**
     * What the engine said about this user task. Everything the context answers out of
     * the engine's own words is read from here, which keeps the spelling of those words
     * in {@link PeaTaskMeta} and nowhere else.
     */
    private final TaskInformation taskInformation;

    /**
     * The adapter delivering this notification.
     */
    private final String adapterId;

    /**
     * What the subscription asked for - see
     * {@link #getTaskParameter(String)}.
     */
    private final PeaFetchVariables.Selection fetchVariables;

    PeaUserTaskInvocationContext(
        final String adapterId,
        final String externalFormReference,
        final String bpmnElementId,
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload,
        final TaskInformation taskInformation,
        final PeaFetchVariables.Selection fetchVariables) {

      this.adapterId = adapterId;
      this.taskInformation = taskInformation;
      this.externalFormReference = externalFormReference;
      this.bpmnElementId = bpmnElementId;
      this.workflowAggregateId = workflowAggregateId;
      this.taskId = taskId;
      this.payload = payload;
      this.fetchVariables = fetchVariables;

    }

    @Override
    public String getAdapterId() {

      return adapterId;

    }

    @Override
    public String getProcessVersion() {

      // the version tag of the deployed process definition, or null. The
      // Process-Engine-API has no version number (GAPS.md)
      return PeaTaskMeta.text(taskInformation, PeaTaskMeta.PROCESS_VERSION_TAG);

    }

    @Override
    public String getBpmnElementId() {

      return bpmnElementId;

    }

    @Override
    public String getWorkflowId() {

      // the engine's own id of the running instance. If an engine names none, the delivery
      // record has no workflow id. That is not a defect, because the API does not say what
      // a delivered task carries
      return PeaTaskMeta.text(taskInformation, PeaTaskMeta.WORKFLOW_ID);

    }

    @Override
    public String getTaskDefinition() {

      return externalFormReference;

    }

    @Override
    public String getWorkflowAggregateId() {

      return workflowAggregateId;

    }

    @Override
    public String getTaskId() {

      return taskId;

    }

    @Override
    public TaskKind getTaskKind() {

      // this handler serves user-task subscriptions and nothing else, so the id it reports
      // is the id of a user task. The engine takes it back through its USER-task completion
      // API. The service-task API of the same engine finds nothing under that id. The
      // record is read to explain exactly that
      return TaskKind.USER_TASK;

    }

    @Override
    public TaskEvent.Event getTaskEvent() {

      return TaskEvent.Event.CREATED;

    }

    @Override
    public String getDeliveryId() {

      // the ID of the user task as the engine reports it. It stays the same when the
      // notification is delivered again, and the next user task has a different one
      return taskId;

    }

    @Override
    public String getActivationId() {

      // there is one user task per activation of its element, and it is delivered again
      // under the same id. So one value meets both contracts here, as on the side of the
      // service tasks
      return taskId;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            PeaFetchVariables.unfetchedTaskParameter(name, externalFormReference, fetchVariables));
      }
      return payload.get(name);

    }

  }

}
