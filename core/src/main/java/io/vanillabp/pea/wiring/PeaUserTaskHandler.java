package io.vanillabp.pea.wiring;

import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * The handler of one USER-task subscription (story 24): a delivered user task is a
 * NOTIFICATION - the optional <code>&#64;WorkflowTask</code> method receives
 * {@link TaskEvent.Event#CREATED} with the user task's ID (as
 * <code>&#64;TaskId</code>) and never completes the task on return; completion
 * arrives via <code>ProcessService#completeUserTask</code>. Without a matching
 * method the delivery is skipped silently (user tasks are processed through
 * forms/task lists). CANCELED cannot be delivered - the subscription's
 * termination callback carries only the task ID, no aggregate reference (see
 * {@code GAPS.md}).
 */
@Slf4j
public class PeaUserTaskHandler implements TaskHandler {

  /**
   * Story 35: translates the scoped task definition of this subscription back into
   * the plain one. May be <code>null</code>.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  private final String adapterId;

  private final String workflowModuleId;

  private final String externalFormReference;

  private final List<String> bpmnProcessIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * What this subscription asked the engine for (story 99) - a user-task notification
   * carries a payload like every other delivery, and it is narrowed the same way.
   */
  private final PeaFetchVariables.Selection fetchVariables;

  public PeaUserTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String externalFormReference,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this(adapterId, workflowModuleId, externalFormReference, bpmnProcessIds, workflowTaskInvoker, null);

  }

  /**
   * Story 35: translates the identifiers the engine knows back into the plain ones.
   */
  public PeaUserTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String externalFormReference,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this(adapterId, workflowModuleId, externalFormReference, bpmnProcessIds, workflowTaskInvoker, scoping, null);

  }

  public PeaUserTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String externalFormReference,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final PeaFetchVariables.Selection fetchVariables) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.externalFormReference = externalFormReference;
    this.bpmnProcessIds = bpmnProcessIds;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.scoping = scoping;
    this.fetchVariables = fetchVariables == null
        ? PeaFetchVariables.Selection.everything()
        : fetchVariables;

  }

  @Override
  public void accept(
      final TaskInformation taskInformation,
      final Map<String, ?> payload) {

    final var taskId = taskInformation.getTaskId();

    try {
      final var bpmnProcessId = determineBpmnProcessId(taskInformation);
      if (!workflowTaskInvoker.workflowTaskHandlerExists(
          workflowModuleId, bpmnProcessId, externalFormReference)) {
        log.trace(
            "Process-Engine-API adapter '{}': no @WorkflowTask handler for user task '{}' of BPMN "
                + "process '{}' - skipping the notification",
            adapterId,
            externalFormReference,
            bpmnProcessId);
        return;
      }
      final var aggregateIdName = workflowTaskInvoker.resolveWorkflowAggregateIdName(
          workflowModuleId, bpmnProcessId);
      final var aggregateId = payload.get(aggregateIdName);
      if (aggregateId == null) {
        throw new IllegalStateException(
            PeaFetchVariables.missingAggregateId(
                "User task", taskId, externalFormReference, bpmnProcessId, aggregateIdName, adapterId, fetchVariables));
      }
      final var outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaUserTaskInvocationContext(
              adapterId, externalFormReference, String
                  .valueOf(aggregateId), taskId, payload, taskInformation
                      .getMeta()
                      .get(PeaTaskHandler.META_VERSION_TAG), fetchVariables));
      if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
        throw new IllegalStateException(
            ("The @WorkflowTask method notified about user task '%s' (BPMN process '%s' of "
                + "workflow module '%s') threw a TaskException! User-task notification handlers "
                + "must not raise BPMN errors - route errors via ProcessService#cancelUserTask "
                + "instead.")
                .formatted(externalFormReference, bpmnProcessId, workflowModuleId));
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

  private String determineBpmnProcessId(
      final TaskInformation taskInformation) {

    final var fromMeta = taskInformation.getMeta().get(PeaTaskHandler.META_BPMN_PROCESS_ID);
    if (fromMeta != null) {
      return fromMeta;
    }
    final var distinct = bpmnProcessIds
        .stream()
        .distinct()
        .toList();
    if (distinct.size() == 1) {
      return distinct.getFirst();
    }
    throw new IllegalStateException(
        ("User task '%s' (form reference '%s') carries no meta entry '%s' and the form reference "
            + "is used by several BPMN processes of workflow module '%s' (%s) - the notification "
            + "cannot be routed!")
            .formatted(
                taskInformation.getTaskId(),
                externalFormReference,
                PeaTaskHandler.META_BPMN_PROCESS_ID,
                workflowModuleId,
                distinct));

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
     * Process-Engine-API knows no version number (story 48, GAPS.md).
     */
    private final String processVersion;

    /**
     * The adapter delivering this notification (story 54).
     */
    private final String adapterId;

    /**
     * What the subscription asked for (story 99) - see
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
      // notification, different for the next user task (story 51)
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
