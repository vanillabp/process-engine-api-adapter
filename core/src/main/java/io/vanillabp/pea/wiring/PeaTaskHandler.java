package io.vanillabp.pea.wiring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.pea.processservice.PeaProcessService;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The task handler of one Process-Engine-API task subscription. There is one per adapter ID
 * and task definition. It builds the neutral invocation context from the delivered task and
 * passes it to the core through {@link WorkflowTaskInvoker}. Depending on the engine, a task
 * may be delivered more than once. The business method runs in a NEW local transaction,
 * and that transaction commits BEFORE the task is completed. So a second delivery of the
 * same task does no harm, as long as the method is idempotent.
 * <p>
 * What each outcome turns into. The completion commands carry {@code ExecutionMode.SYNC},
 * because they move the process on AFTER the local commit, which is phase two.
 * <ul>
 * <li>COMPLETED: {@code ServiceTaskCompletionApi.completeTask}.</li>
 * <li>BPMN_ERROR ({@code TaskException}):
 * {@code ServiceTaskCompletionApi.completeTaskByError}, with the error code as the deployed
 * model spells it (see {@link #scopedErrorCode(String)}). The changes to the aggregate are
 * committed.</li>
 * <li>COMPLETION_PENDING ({@code @TaskId} methods): nothing. The task stays open until
 * {@code ProcessService#completeTask} is called.</li>
 * <li>A task of a workflow this application does not own
 * ({@code DeliveryOfAnUnknownWorkflowException}): {@code failTask} with a retry count of
 * zero, see {@link #refuseTheStrangersTask(String, DeliveryOfAnUnknownWorkflowException)}.</li>
 * <li>Any other exception: {@code ServiceTaskCompletionApi.failTask}. The core has already
 * rolled the local transaction back. The engine decides about retries.</li>
 * </ul>
 * The BPMN process of a task is read from the meta key {@link PeaTaskMeta#BPMN_PROCESS_ID}
 * of the {@link TaskInformation}. That key is a convention of this adapter, the API does not
 * define it (see {@code GAPS.md}). The value is translated back to the plain id the core
 * uses. Without the entry, the task definition has to be unique across the processes of the
 * module.
 * <p>
 * Why this path swallows nothing and still needs no outbox, unlike the phase-two operations
 * of the process service, is decision 5 in the repository's DECISIONS.md.
 */
@Slf4j
public class PeaTaskHandler implements TaskHandler {

  private final String adapterId;

  private final String workflowModuleId;

  private final String taskDefinition;

  private final List<String> bpmnProcessIds;

  /**
   * The BPMN process ids the workflow module declares without deploying a model under them,
   * and whose task definitions make up the same name as this subscription. A delivery is
   * never routed to them: a delivery over a shared name goes to a deployed process. But a
   * delivery which cannot be routed has to name them. One possible reason is a workflow
   * which still runs under the old id of a renamed process. Never <code>null</code>.
   */
  private final List<String> declaredBpmnProcessIds;

  /**
   * The BPMN element each process of this subscription delivers from, keyed by the plain BPMN
   * process id. It is read when the engine names no element itself, see
   * {@link #bpmnElementId(String, TaskInformation)}. Never <code>null</code>.
   */
  private final Map<String, String> bpmnElementIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  /**
   * Translates the identifiers the engine knows back into the plain ones. It changes nothing
   * unless the module uses prefixes. May be <code>null</code>.
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * What this subscription asked the engine for. The payload of a delivery carries exactly
   * that. So a <code>&#64;TaskParam</code> which names anything else gets a failure that says
   * what to do, not a <code>null</code>.
   */
  private final PeaFetchVariables.Selection fetchVariables;

  /**
   * The subscription this handler serves. Use the generated
   * <code>PeaTaskHandler.builder()</code>. Some of these values may be left out, and in a
   * list of arguments this long nobody can tell which is which.
   *
   * @param adapterId The adapter whose subscription delivers here
   * @param workflowModuleId The workflow module the subscribed tasks belong to
   * @param taskDefinition The task definition as the engine knows it
   * @param bpmnProcessIds The BPMN processes this subscription may deliver from
   * @param declaredBpmnProcessIds The ids the module declares without a model which share
   *          this subscription's name, or <code>null</code> for none
   * @param bpmnElementIds What the deployed models say a delivery of this subscription belongs
   *          to, per BPMN process, or <code>null</code> where no model says it
   * @param workflowTaskInvoker The core's runtime entry point
   * @param serviceTaskCompletionApi Where the outcome of a delivery is reported
   * @param scoping Translates the engine's identifiers back, or <code>null</code>
   * @param fetchVariables What the subscription asked for, or <code>null</code> for everything
   */
  @Builder
  public PeaTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String taskDefinition,
      final List<String> bpmnProcessIds,
      final List<String> declaredBpmnProcessIds,
      final Map<String, String> bpmnElementIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final NameClashAvoidanceSupport scoping,
      final PeaFetchVariables.Selection fetchVariables) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.taskDefinition = taskDefinition;
    this.bpmnProcessIds = bpmnProcessIds;
    this.declaredBpmnProcessIds = declaredBpmnProcessIds == null
        ? List.of()
        : List.copyOf(declaredBpmnProcessIds);
    this.bpmnElementIds = bpmnElementIds == null
        ? Map.of()
        : Map.copyOf(bpmnElementIds);
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;
    this.scoping = scoping;
    this.fetchVariables = fetchVariables == null
        ? PeaFetchVariables.Selection.everything()
        : fetchVariables;

  }

  /**
   * The PLAIN task definition of this subscription. Under {@code use-prefix}, the
   * subscription itself uses the prefixed one.
   */
  private String plainTaskDefinition(
      final String bpmnProcessId) {

    return scoping == null
        ? taskDefinition
        : scoping.plainTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * The error code as the deployed model spells it. Under {@code use-prefix}, the
   * {@code errorCode} of a {@code bpmn:error} gets the prefix like every other identifier of
   * the module. So the code a {@code TaskException} names has to be translated on its way to
   * the engine. Otherwise the engine throws a code which no boundary event in the model
   * catches.
   *
   * @param errorCode The code the business method raised
   * @return The code the engine knows it by
   */
  private String scopedErrorCode(
      final String errorCode) {

    return NameClashAvoidanceSupport.scopedIdentifier(scoping, workflowModuleId, errorCode, adapterId);

  }

  /**
   * The BPMN element this delivery belongs to. It is the second key a
   * <code>&#64;WorkflowTask</code> method is wired by. A delivery which names no element does
   * not reach a method with <code>&#64;WorkflowTask(id = ...)</code>.
   * <p>
   * If the engine fills the meta entry, its answer wins. It is the better answer, because it
   * is about THIS delivery and about the model the engine runs it under. But the API does not
   * promise this entry (see {@code GAPS.md}, entry 26). So if the engine names no element,
   * the deployed model answers. This subscription asks for one task definition. The element
   * with that name, in the process the delivery was routed to, is the element it belongs to.
   * If the model has that name on several elements, there is no answer, and the startup
   * already said so.
   *
   * @param bpmnProcessId The plain BPMN process the delivery was routed to
   * @param taskInformation What the engine said about the task
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
                "Task", taskId, taskDefinition, bpmnProcessId, aggregateIdName, fetchVariables));
      }
      outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaTaskInvocationContext(
              adapterId, plainTaskDefinition(bpmnProcessId), bpmnElementId(bpmnProcessId, taskInformation), String
                  .valueOf(aggregateId), taskId, payload, taskInformation, fetchVariables));
    } catch (final DeliveryOfAnUnknownWorkflowException refusal) {
      refuseTheStrangersTask(taskId, refusal);
      return;
    } catch (final Exception e) {
      // the core has rolled the local transaction back. Failing the task lets the engine
      // apply its own retry rules
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
                  taskId, scopedErrorCode(outcome.errorCode()), String.valueOf(outcome.errorName()), completionPayload))
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
   * Hands a task of a workflow this application does not own back to the engine, and asks
   * for no further attempt.
   * <p>
   * A task subscription of this API matches a task type across the whole engine, and there
   * are no tenants. So if two applications deploy a BPMN process of the same name, each one
   * gets the tasks of the other. The core answers such a delivery with
   * {@link DeliveryOfAnUnknownWorkflowException}. Nothing here can make the task run, because
   * the workflow aggregate it names lives in the other application. A new delivery would
   * only meet the same refusal, so the retry count is zero and not the engine's default. But
   * the task is not completed quietly either. It is the work of the other application and
   * stays in the engine. An application which quietly completes a task of another one takes
   * it away for good.
   * <p>
   * The log line has no stack trace. The message of the refusal names the workflow, the two
   * situations it may be, and what to do in each. A stack trace would only add which line of
   * the core read the database.
   *
   * @param taskId The task which belongs somewhere else
   * @param refusal What the core said about it
   */
  private void refuseTheStrangersTask(
      final String taskId,
      final DeliveryOfAnUnknownWorkflowException refusal) {

    log.warn(
        "Process-Engine-API adapter '{}': task '{}' (definition '{}') is about a workflow this "
            + "application does not own - failing it with no further attempt. The reason reads: {}",
        adapterId,
        taskId,
        taskDefinition,
        refusal.getMessage());
    completion(
        () -> serviceTaskCompletionApi
            .failTask(PeaFailTaskCmd.withoutFurtherAttempts(taskId, refusal.getMessage()))
            .get(),
        taskId,
        "fail");

  }

  /**
   * The payload a completion command carries. It holds the values the workflow aggregate
   * shares with the engine. The {@code @WorkflowTask} method has just changed the aggregate,
   * and a gateway right after this task has to see the NEW values. The payload always holds
   * the variable with the ID of the aggregate too, because the Process-Engine-API has no
   * slot for a business key.
   * <p>
   * The core loads the aggregate in a transaction of its OWN, because the one of the task
   * has committed by now. It never throws. If the read fails, the payload holds only the ID
   * variable, and the task is still completed.
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

    final var completionPayload = new LinkedHashMap<String, Object>(
        workflowTaskInvoker.syncedWorkflowAggregateValues(
            workflowModuleId,
            bpmnProcessId,
            String.valueOf(aggregateId),
            PeaProcessService.SYNC_MODE));
    completionPayload.put(aggregateIdName, aggregateId);
    return completionPayload;

  }

  private String determineBpmnProcessId(
      final TaskInformation taskInformation) {

    // the entry holds the id the ENGINE knows, which has the prefix under 'use-prefix'.
    // PeaTaskMeta translates it back, because the core uses the plain id
    final var fromMeta = PeaTaskMeta
        .plainBpmnProcessId(taskInformation, scoping, workflowModuleId, adapterId);
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
            by several BPMN processes of workflow module '%s' (%s)%s - the task cannot be routed! \
            Either the Process-Engine-API implementation supplies the meta entry or the task \
            definition has to be unique across the module's processes%s."""
            .formatted(
                taskInformation.getTaskId(),
                taskDefinition,
                PeaTaskMeta.BPMN_PROCESS_ID,
                workflowModuleId,
                distinct,
                PeaRenamedProcesses.andTheIdsTheModuleOnlyDeclares(declaredBpmnProcessIds),
                PeaRenamedProcesses.orKeepDeployingTheOldModel(declaredBpmnProcessIds)));

  }

  private void completion(
      final CompletionCall call,
      final String taskId,
      final String description) {

    try {
      call.run();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      // the caller is a subscription thread of the engine, usually one which is shutting
      // down. Nobody further up acts on the flag. So an interrupt gets the same log line as
      // a failure. Otherwise the task would be neither reported nor logged
      couldNotReportBack(taskId, description, e);
    } catch (final ExecutionException e) {
      couldNotReportBack(taskId, description, e.getCause());
    }

  }

  /**
   * Says that the engine did not learn the outcome of a task.
   * <p>
   * The local transaction has committed by now. So this is the known gap of at-least-once
   * delivery: the engine delivers the task again, and the idempotent business method runs
   * once more without harm.
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

    /**
     * The BPMN element this delivery belongs to, or <code>null</code> - see
     * {@link PeaTaskHandler#bpmnElementId(String, TaskInformation)}.
     */
    private final String bpmnElementId;

    private final String workflowAggregateId;

    private final String taskId;

    private final Map<String, ?> payload;

    /**
     * What the engine said about this task. Everything the context answers out of the
     * engine's own words is read from here, which keeps the spelling of those words in
     * {@link PeaTaskMeta} and nowhere else.
     */
    private final TaskInformation taskInformation;

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
        final String bpmnElementId,
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload,
        final TaskInformation taskInformation,
        final PeaFetchVariables.Selection fetchVariables) {

      this.adapterId = adapterId;
      this.taskDefinition = taskDefinition;
      this.bpmnElementId = bpmnElementId;
      this.workflowAggregateId = workflowAggregateId;
      this.taskId = taskId;
      this.payload = payload;
      this.taskInformation = taskInformation;
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
    public TaskKind getTaskKind() {

      // this handler serves task subscriptions and nothing else, so the id it reports is the
      // id of a task. The engine takes it back through its SERVICE-task completion API. The
      // user-task API of the same engine finds nothing under that id. The record is read to
      // explain exactly that
      return TaskKind.TASK;

    }

    @Override
    public String getDeliveryId() {

      // the task ID of the engine names this piece of work. If the engine never learned the
      // result and delivers the task again, it comes with the same ID. A new activation of
      // the element comes with a different one. The core remembers a processed delivery by
      // this ID
      return taskId;

    }

    @Override
    public String getActivationId() {

      // the same value as the delivery id, on purpose. This engine creates one task per
      // activation of an element and delivers it again under that id. So one value meets
      // both contracts here. It is not a shortcut. An engine which gave a new id to every
      // new delivery would have to answer the two differently, and the Process-Engine-API
      // does not do that
      return taskId;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            PeaFetchVariables.unfetchedTaskParameter(name, taskDefinition, fetchVariables));
      }
      return payload.get(name);

    }

    // runInCurrentTransaction stays false. Task handlers run on subscription threads which
    // have no transaction. The core opens a NEW one, and it commits BEFORE the task is
    // completed, which is the order at-least-once delivery needs

  }

}
