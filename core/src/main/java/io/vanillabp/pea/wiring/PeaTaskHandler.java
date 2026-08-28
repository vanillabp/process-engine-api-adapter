package io.vanillabp.pea.wiring;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import lombok.extern.slf4j.Slf4j;

/**
 * The task handler of one Process-Engine-API task subscription (one per adapter ID
 * and task definition): builds the neutral invocation context from the delivered
 * task and dispatches through the core's {@link WorkflowTaskInvoker}. Deliveries
 * may arrive AT LEAST ONCE (depending on the underlying engine) - the business
 * method runs in a NEW local transaction which commits BEFORE the task is
 * completed, so a duplicate delivery converges idempotently.
 * <p>
 * Outcome mapping (completion commands carry {@code ExecutionMode.SYNC} - they
 * advance the process AFTER the local commit, the phase-two shape):
 * <ul>
 * <li>COMPLETED - {@code ServiceTaskCompletionApi.completeTask};</li>
 * <li>BPMN_ERROR ({@code TaskException}) -
 * {@code ServiceTaskCompletionApi.completeTaskByError} with the error code
 * (aggregate changes committed);</li>
 * <li>COMPLETION_PENDING ({@code @TaskId} methods) - nothing: the task stays open
 * for {@code ProcessService#completeTask};</li>
 * <li>any other exception - {@code ServiceTaskCompletionApi.failTask} (the local
 * transaction was already rolled back by the core; retry semantics are the
 * engine's).</li>
 * </ul>
 * The BPMN process a task belongs to is read from {@link TaskInformation}
 * meta key <code>bpmnProcessId</code> (adapter convention - the API does not
 * define it, see {@code GAPS.md}); without it the task definition has to be
 * unique across the module's processes.
 * <p>
 * Why this path swallows nothing but also needs no outbox, unlike the phase-two operations of the
 * process service, is decision 5 in the repository's DECISIONS.md.
 */
@Slf4j
public class PeaTaskHandler implements TaskHandler {

  /**
   * The {@link TaskInformation} meta key carrying the BPMN process ID (adapter
   * convention).
   */
  public static final String META_BPMN_PROCESS_ID = "bpmnProcessId";

  /**
   * The {@link TaskInformation} meta key carrying the version tag of the deployed
   * process definition, named by the Process-Engine-API
   * ({@code CommonRestrictions.PROCESS_DEFINITION_VERSION_TAG}). The API has no
   * numeric version, so this tag is all VanillaBP can match
   * <code>&#64;WorkflowTask(version = ...)</code> against here - and only where the
   * underlying engine supplies it (see GAPS.md).
   */
  public static final String META_VERSION_TAG = "processDefinitionVersionTag";

  private final String adapterId;

  private final String workflowModuleId;

  private final String taskDefinition;

  private final List<String> bpmnProcessIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  /**
   * Translates the identifiers the engine knows back into the plain ones
   * - a no-op unless the module uses prefixes. May be <code>null</code>.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * What this subscription asked the engine for - the payload of a delivery
   * carries exactly that, so a <code>&#64;TaskParam</code> naming anything else is
   * answered with a guiding failure instead of a <code>null</code>.
   */
  private final PeaFetchVariables.Selection fetchVariables;

  public PeaTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String taskDefinition,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this(adapterId, workflowModuleId, taskDefinition, bpmnProcessIds, workflowTaskInvoker, serviceTaskCompletionApi, null);

  }

  public PeaTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String taskDefinition,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this(adapterId, workflowModuleId, taskDefinition, bpmnProcessIds, workflowTaskInvoker, serviceTaskCompletionApi, scoping, null);

  }

  public PeaTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String taskDefinition,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final PeaFetchVariables.Selection fetchVariables) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.taskDefinition = taskDefinition;
    this.bpmnProcessIds = bpmnProcessIds;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;
    this.scoping = scoping;
    this.fetchVariables = fetchVariables == null
        ? PeaFetchVariables.Selection.everything()
        : fetchVariables;

  }

  /**
   * The PLAIN task definition of this subscription (the subscription key is the
   * scoped one under {@code use-prefix}).
   */
  private String plainTaskDefinition(
      final String bpmnProcessId) {

    return scoping == null
        ? taskDefinition
        : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  @Override
  public void accept(
      final TaskInformation taskInformation,
      final Map<String, ?> payload) {

    final var taskId = taskInformation.getTaskId();

    final WorkflowTaskOutcome outcome;
    final String bpmnProcessId;
    final String aggregateIdName;
    final Object aggregateId;
    try {
      bpmnProcessId = determineBpmnProcessId(taskInformation);
      aggregateIdName = workflowTaskInvoker.resolveWorkflowAggregateIdName(
          workflowModuleId,
          bpmnProcessId);
      aggregateId = payload.get(aggregateIdName);
      if (aggregateId == null) {
        throw new IllegalStateException(
            PeaFetchVariables.missingAggregateId(
                "Task", taskId, taskDefinition, bpmnProcessId, aggregateIdName, adapterId, fetchVariables));
      }
      outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaTaskInvocationContext(
              adapterId, plainTaskDefinition(bpmnProcessId), String
                  .valueOf(aggregateId), taskId, payload, taskInformation
                      .getMeta()
                      .get(META_VERSION_TAG), fetchVariables));
    } catch (final Exception e) {
      // the core rolled the local transaction back - fail the task so the
      // underlying engine applies its retry semantics
      log.warn(
          "Process-Engine-API adapter '{}': processing task '{}' (definition '{}') failed - failing "
              + "the task",
          adapterId,
          taskId,
          taskDefinition,
          e);
      completion(
          () -> serviceTaskCompletionApi
              .failTask(new PeaFailTaskCmd(taskId, String.valueOf(e.getMessage()), null))
              .get(),
          taskId,
          "fail");
      return;
    }

    final var completionPayload = payloadOf(bpmnProcessId, aggregateIdName, aggregateId);
    switch (outcome.kind()) {
      case COMPLETED -> completion(
          () -> serviceTaskCompletionApi
              .completeTask(new PeaCompleteTaskCmd(taskId, completionPayload))
              .get(),
          taskId,
          "complete");
      case BPMN_ERROR -> completion(
          () -> serviceTaskCompletionApi
              .completeTaskByError(new PeaCompleteTaskByErrorCmd(
                  taskId, outcome.errorCode(), String.valueOf(outcome.errorName()), completionPayload))
              .get(),
          taskId,
          "complete-by-error");
      case COMPLETION_PENDING -> log.debug(
          "Process-Engine-API adapter '{}': task '{}' (definition '{}') stays open for asynchronous "
              + "completion",
          adapterId,
          taskId,
          taskDefinition);
    }

  }

  /**
   * The payload a completion command carries: the values the workflow
   * aggregate shares with the engine - the {@code @WorkflowTask} method just
   * changed it and a gateway right behind this task has to see the NEW values -
   * plus, always, the variable holding the aggregate's ID (the
   * Process-Engine-API has no business-key slot).
   * <p>
   * The core loads the aggregate in its OWN transaction (the task's one is
   * committed at this point) and never throws: a failed read yields the ID
   * variable only, so the task is still completed.
   *
   * @param bpmnProcessId The BPMN process ID
   * @param aggregateIdName The name of the aggregate's ID property
   * @param aggregateId The aggregate's ID as it arrived in the delivered payload
   * @return The payload (never <code>null</code>)
   */
  private Map<String, Object> payloadOf(
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object aggregateId) {

    final var completionPayload = new java.util.LinkedHashMap<String, Object>(
        workflowTaskInvoker.syncedWorkflowAggregateValues(
            workflowModuleId,
            bpmnProcessId,
            String.valueOf(aggregateId),
            io.vanillabp.pea.processservice.PeaProcessService.SYNC_MODE));
    completionPayload.put(aggregateIdName, aggregateId);
    return completionPayload;

  }

  private String determineBpmnProcessId(
      final TaskInformation taskInformation) {

    final var fromMeta = taskInformation.getMeta().get(META_BPMN_PROCESS_ID);
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
        """
            Task '%s' (definition '%s') carries no meta entry '%s' and the task definition is used \
            by several BPMN processes of workflow module '%s' (%s) - the task cannot be routed! \
            Either the Process-Engine-API implementation supplies the meta entry or the task \
            definition has to be unique across the module's processes."""
            .formatted(
                taskInformation.getTaskId(),
                taskDefinition,
                META_BPMN_PROCESS_ID,
                workflowModuleId,
                distinct));

  }

  private void completion(
      final CompletionCall call,
      final String taskId,
      final String description) {

    try {
      call.run();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      // the caller is a subscription thread of the engine, usually one being shut
      // down: nobody up the stack acts on the flag, so an interrupt gets the same
      // line a failure gets instead of leaving the task neither reported nor logged
      couldNotReportBack(taskId, description, e);
    } catch (final ExecutionException e) {
      couldNotReportBack(taskId, description, e.getCause());
    }

  }

  /**
   * Says that the engine did not learn the outcome of a task.
   * <p>
   * The local transaction is committed by now, so this is the documented
   * at-least-once residual: the engine redelivers the task and the idempotent
   * handler converges.
   *
   * @param taskId The task which stays open in the engine
   * @param description What was attempted, as a verb ("complete", "fail")
   * @param cause Why it did not reach the engine
   */
  private void couldNotReportBack(
      final String taskId,
      final String description,
      final Throwable cause) {

    log.warn(
        "Process-Engine-API adapter '{}': could not {} task '{}' - if the engine redelivers the "
            + "task, the (idempotent) business method will run again",
        adapterId,
        description,
        taskId,
        cause);

  }

  @FunctionalInterface
  private interface CompletionCall {

    void run() throws InterruptedException, ExecutionException;

  }

  /**
   * The neutral invocation context built from a delivered Process-Engine-API task.
   */
  static class PeaTaskInvocationContext implements TaskInvocationContext {

    private final String taskDefinition;

    private final String workflowAggregateId;

    private final String taskId;

    private final Map<String, ?> payload;

    /**
     * The version tag of the deployed process definition or <code>null</code> - the
     * Process-Engine-API knows no version number (GAPS.md).
     */
    private final String processVersion;

    /**
     * The adapter delivering this task.
     */
    private final String adapterId;

    /**
     * What the subscription asked for - see
     * {@link #getTaskParameter(String)}.
     */
    private final PeaFetchVariables.Selection fetchVariables;

    PeaTaskInvocationContext(
        final String adapterId,
        final String taskDefinition,
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload,
        final String processVersion) {

      this(adapterId, taskDefinition, workflowAggregateId, taskId, payload, processVersion, PeaFetchVariables.Selection
          .everything());

    }

    PeaTaskInvocationContext(
        final String adapterId,
        final String taskDefinition,
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload,
        final String processVersion,
        final PeaFetchVariables.Selection fetchVariables) {

      this.adapterId = adapterId;
      this.taskDefinition = taskDefinition;
      this.workflowAggregateId = workflowAggregateId;
      this.taskId = taskId;
      this.payload = payload;
      this.processVersion = processVersion;
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

      return taskDefinition;

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
    public String getDeliveryId() {

      // the engine's task ID identifies this piece of work: a task redelivered
      // because the engine never learned the result arrives under the same ID, a new
      // activation of the element under a different one - the identity the core
      // remembers a processed delivery by
      return taskId;

    }

    @Override
    public String getActivationId() {

      // the same value as the delivery id, and deliberately: this engine creates one
      // task per activation of an element and redelivers it under that id, so both
      // contracts are satisfied by one value here. It is not a shortcut - an engine
      // whose redelivery gets a new id would have to answer these two differently,
      // and the Process-Engine-API does not
      return taskId;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            PeaFetchVariables.unfetchedTaskParameter(name, taskDefinition, adapterId, fetchVariables));
      }
      return payload.get(name);

    }

    // runInCurrentTransaction stays false: task handlers run on subscription
    // threads without a transaction - the core opens a NEW one which commits
    // BEFORE the task is completed (at-least-once ordering)

  }

}
