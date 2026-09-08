package io.vanillabp.pea.wiring;

import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObservers;
import io.vanillabp.spi.service.TaskEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The handler of one USER-task subscription: a delivered user task is a
 * NOTIFICATION - the optional <code>&#64;WorkflowTask</code> method receives
 * {@link TaskEvent.Event#CREATED} with the user task's ID (as
 * <code>&#64;TaskId</code>) and never completes the task on return; completion
 * arrives via <code>ProcessService#completeUserTask</code>. Without a matching
 * method the delivery is skipped silently (user tasks are processed through
 * forms/task lists). CANCELED cannot be delivered to a
 * <code>&#64;WorkflowTask</code> method: the subscription's termination callback carries the
 * engine's {@link TaskInformation} but no payload, so the workflow aggregate the notification
 * would have to be dispatched for cannot be told (see {@code GAPS.md}). What the termination
 * does carry - the task, the engine's reason and the rest of its meta map - reaches the
 * {@link PeaUserTaskObserver}s of the application through {@link #terminated(TaskInformation)}.
 */
@Slf4j
public class PeaUserTaskHandler implements TaskHandler {

  /**
   * Translates the scoped task definition of this subscription back into
   * the plain one. May be <code>null</code>.
   */
  private final NameClashAvoidanceSupport scoping;

  private final String adapterId;

  private final String workflowModuleId;

  private final String externalFormReference;

  private final List<String> bpmnProcessIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * What this subscription asked the engine for - a user-task notification
   * carries a payload like every other delivery, and it is narrowed the same way.
   */
  private final PeaFetchVariables.Selection fetchVariables;

  /**
   * Who watches the user tasks this subscription is delivered. Never
   * <code>null</code> - an application which registered no observer holds an empty list.
   */
  private final PeaUserTaskObservers observers;

  /**
   * The user-task subscription this handler serves. Built through the generated
   * <code>PeaUserTaskHandler.builder()</code>: three of these seven values may be left out and
   * a positional list of that length no longer says which is which.
   *
   * @param adapterId The adapter whose subscription delivers here
   * @param workflowModuleId The workflow module the subscribed user tasks belong to
   * @param externalFormReference The form reference the engine delivers under
   * @param bpmnProcessIds The BPMN processes this subscription may deliver from
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
      final WorkflowTaskInvoker workflowTaskInvoker,
      final NameClashAvoidanceSupport scoping,
      final PeaFetchVariables.Selection fetchVariables,
      final PeaUserTaskObservers observers) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.externalFormReference = externalFormReference;
    this.bpmnProcessIds = bpmnProcessIds;
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

    try {
      final var bpmnProcessId = bpmnProcessIdOrNull(taskInformation);
      // what the payload holds the workflow aggregate's id under, asked once and answered
      // leniently: an observer is told about a delivery this application cannot place, and
      // the failure is kept for the strict path below, which is where it is a defect
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
      // whoever watches this application's user tasks sees every one of them: the two
      // failures below drop a delivery which cannot be routed and one no @WorkflowTask
      // method claims, and a task list shows a user task in both cases
      final var observedAggregateIdName = aggregateIdName;
      observers.delivered(
          taskId, () -> observationOf(bpmnProcessId, observedAggregateIdName, taskInformation, payload));
      if (bpmnProcessId == null) {
        throw ambiguousRouting(taskInformation);
      }
      // the core's registries are keyed by the plain identifiers, so what this subscription
      // is keyed by is translated back before the core is asked anything
      final var taskDefinition = plainTaskDefinition(bpmnProcessId);
      if (!workflowTaskInvoker.workflowTaskHandlerExists(
          workflowModuleId, bpmnProcessId, taskDefinition)) {
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
                "User task", taskId, taskDefinition, bpmnProcessId, aggregateIdName, adapterId, fetchVariables));
      }
      final var outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaUserTaskInvocationContext(
              adapterId, taskDefinition, String
                  .valueOf(aggregateId), taskId, payload, taskInformation
                      .getMeta()
                      .get(PeaTaskHandler.META_VERSION_TAG), fetchVariables));
      if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
        throw new IllegalStateException(
            ("The @WorkflowTask method notified about user task '%s' (BPMN process '%s' of "
                + "workflow module '%s') threw a TaskException! User-task notification handlers "
                + "must not raise BPMN errors - route errors via ProcessService#cancelUserTask "
                + "instead.")
                .formatted(taskDefinition, bpmnProcessId, workflowModuleId));
      }
    } catch (final Exception e) {
      // a failing NOTIFICATION must not break the user task itself - the task
      // stays available through forms/task lists; the defect is logged loudly
      log.error(
          "Process-Engine-API adapter '{}': the CREATED notification for user task '{}' (form "
              + "reference '{}') failed! The user task itself stays available.",
          adapterId,
          taskId,
          externalFormReference,
          e);
    }

  }

  /**
   * The engine withdrew a user task it had delivered to this subscription - registered as the
   * {@link TaskTerminationHandler} of the subscription, which is the overload carrying the
   * engine's {@link TaskInformation} instead of the task id alone.
   * <p>
   * The adapter itself has nothing to do here, because a CANCELED notification would need the
   * workflow aggregate and a termination carries no payload. What it does is say so and hand
   * the termination to the observers, reason included.
   *
   * @param taskInformation What the engine says about the terminated task
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
      observers.terminated(taskId, () -> new PeaUserTaskObservation(
          adapterId, workflowModuleId, bpmnProcessId, plainTaskDefinition(bpmnProcessId),
          // a termination carries no payload, so the aggregate-id variable is not among
          // the things the engine hands over
          null, taskInformation, Map.of()));
    } catch (final Exception e) {
      // like a failing notification: nobody is served by a termination callback which
      // throws back into the engine's delivery thread
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
   * What an observer is handed. The identifiers are the PLAIN ones the application's BPMN and
   * its configuration use: the subscription is keyed by the SCOPED task definition and the
   * engine reports the scoped BPMN process id, and both are translated back before anybody
   * outside the adapter sees them (decision 2 in the repository's DECISIONS.md).
   * <p>
   * <code>aggregateIdName</code> is what the caller resolved for this BPMN process, or
   * <code>null</code> where the application declares no workflow aggregate for it. That case
   * and a variable the subscription did not ask the engine for are the two which leave the
   * observation without an aggregate id: an observer then sees a task it cannot place under a
   * business case rather than no task at all. The application's own path is strict about the
   * same value, because a delivery a <code>&#64;WorkflowTask</code> method claims without an
   * aggregate id is a defect there.
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
        // the record makes the one defensive copy there is - this map is only read
        payloadAsDelivered(payload));

  }

  /**
   * The delivered payload as the observation declares it. The engine hands over a map of
   * unknown value type and the record one of <code>Object</code>: the same map, read through
   * a wider door, and never written through either.
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
   * The PLAIN task definition of this subscription - its key is the scoped one under
   * {@code use-prefix}, where a task definition is scoped per BPMN process.
   * <p>
   * Without a BPMN process there is nothing to unscope by, and then there is nothing to
   * unscope either: under {@code use-prefix} two processes sharing a form reference get one
   * subscription each, because their scoped keys differ, so a subscription which cannot tell
   * its process apart belongs to a mode which prefixes nothing.
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
   * The delivery cannot be routed: the engine named no BPMN process and this subscription
   * serves several. The observers have been told by the time this is thrown - a task a cockpit
   * shows is not the same question as a task this application can dispatch.
   *
   * @param taskInformation What the engine says about the task
   * @return The failure the notification ends with
   */
  private IllegalStateException ambiguousRouting(
      final TaskInformation taskInformation) {

    return new IllegalStateException(
        ("User task '%s' (form reference '%s') carries no meta entry '%s' and the form reference "
            + "is used by several BPMN processes of workflow module '%s' (%s) - the notification "
            + "cannot be routed!")
            .formatted(
                taskInformation.getTaskId(),
                externalFormReference,
                PeaTaskHandler.META_BPMN_PROCESS_ID,
                workflowModuleId,
                bpmnProcessIds
                    .stream()
                    .distinct()
                    .toList()));

  }

  /**
   * Which BPMN process a delivery belongs to, as far as it can be told: the engine's meta
   * entry names it, and where the engine fills none this subscription's single process is the
   * answer. Several processes behind one form reference and no meta entry leave it open,
   * which ends a delivery in {@link #ambiguousRouting} and is a fact of life for a termination
   * - see {@link #terminated(TaskInformation)}.
   * <p>
   * The meta entry is what the ENGINE knows the process as, so it is unscoped: the core's
   * registries are keyed by the plain id and so is everything this adapter hands out
   * (decision 2 in the repository's DECISIONS.md). The single process of a subscription is
   * plain already, being the one this adapter subscribed for.
   *
   * @param taskInformation What the engine says about the task
   * @return The plain BPMN process id, or <code>null</code>
   */
  private String bpmnProcessIdOrNull(
      final TaskInformation taskInformation) {

    final var fromMeta = taskInformation.getMeta().get(PeaTaskHandler.META_BPMN_PROCESS_ID);
    if (fromMeta != null) {
      return NameClashAvoidanceSupport.plainProcessId(scoping, workflowModuleId, fromMeta, adapterId);
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

    private final String workflowAggregateId;

    private final String taskId;

    private final Map<String, ?> payload;

    /**
     * The version tag of the deployed process definition or <code>null</code> - the
     * Process-Engine-API knows no version number (GAPS.md).
     */
    private final String processVersion;

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
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload,
        final String processVersion,
        final PeaFetchVariables.Selection fetchVariables) {

      this.adapterId = adapterId;
      this.processVersion = processVersion;
      this.externalFormReference = externalFormReference;
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

      return processVersion;

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
    public TaskEvent.Event getTaskEvent() {

      return TaskEvent.Event.CREATED;

    }

    @Override
    public String getDeliveryId() {

      // the user task's ID as the engine reports it - the same on a redelivery of the
      // notification, different for the next user task
      return taskId;

    }

    @Override
    public String getActivationId() {

      // one user task per activation of its element, redelivered under that id: both
      // contracts meet in one value here, as they do on the asynchronous-task side
      return taskId;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            PeaFetchVariables.unfetchedTaskParameter(name, externalFormReference, adapterId, fetchVariables));
      }
      return payload.get(name);

    }

  }

}
