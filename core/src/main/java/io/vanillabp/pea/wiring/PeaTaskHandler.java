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
 */
@Slf4j
public class PeaTaskHandler implements TaskHandler {

  /**
   * The {@link TaskInformation} meta key carrying the BPMN process ID (adapter
   * convention).
   */
  public static final String META_BPMN_PROCESS_ID = "bpmnProcessId";

  private final String adapterId;

  private final String workflowModuleId;

  private final String taskDefinition;

  private final List<String> bpmnProcessIds;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  public PeaTaskHandler(
      final String adapterId,
      final String workflowModuleId,
      final String taskDefinition,
      final List<String> bpmnProcessIds,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.taskDefinition = taskDefinition;
    this.bpmnProcessIds = bpmnProcessIds;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;

  }

  @Override
  public void accept(
      final TaskInformation taskInformation,
      final Map<String, ?> payload) {

    final var taskId = taskInformation.getTaskId();

    final WorkflowTaskOutcome outcome;
    try {
      final var bpmnProcessId = determineBpmnProcessId(taskInformation);
      final var aggregateIdName = workflowTaskInvoker.resolveWorkflowAggregateIdName(
          workflowModuleId,
          bpmnProcessId);
      final var aggregateId = payload.get(aggregateIdName);
      if (aggregateId == null) {
        throw new IllegalStateException(
            """
                Task '%s' (definition '%s') of BPMN process '%s' carries no payload variable '%s' \
                holding the workflow aggregate's ID! Workflows processed by VanillaBP have to be \
                started through VanillaBP (the variable is written on start)."""
                .formatted(taskId, taskDefinition, bpmnProcessId, aggregateIdName));
      }
      outcome = workflowTaskInvoker.invokeWorkflowTask(
          workflowModuleId,
          bpmnProcessId,
          new PeaTaskInvocationContext(taskDefinition, String.valueOf(aggregateId), taskId, payload));
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

    switch (outcome.kind()) {
      case COMPLETED -> completion(
          () -> serviceTaskCompletionApi
              .completeTask(new PeaCompleteTaskCmd(taskId))
              .get(),
          taskId,
          "complete");
      case BPMN_ERROR -> completion(
          () -> serviceTaskCompletionApi
              .completeTaskByError(new PeaCompleteTaskByErrorCmd(
                  taskId, outcome.errorCode(), String.valueOf(outcome.errorName())))
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
    } catch (final ExecutionException e) {
      // the local transaction is committed - a failing completion is the
      // documented at-least-once residual (the engine redelivers, the handler
      // converges idempotently)
      log.warn(
          "Process-Engine-API adapter '{}': could not {} task '{}' - if the engine redelivers the "
              + "task, the (idempotent) business method will run again",
          adapterId,
          description,
          taskId,
          e.getCause());
    }

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

    PeaTaskInvocationContext(
        final String taskDefinition,
        final String workflowAggregateId,
        final String taskId,
        final Map<String, ?> payload) {

      this.taskDefinition = taskDefinition;
      this.workflowAggregateId = workflowAggregateId;
      this.taskId = taskId;
      this.payload = payload;

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
    public Object getTaskParameter(
        final String name) {

      return payload.get(name);

    }

    // runInCurrentTransaction stays false: task handlers run on subscription
    // threads without a transaction - the core opens a NEW one which commits
    // BEFORE the task is completed (at-least-once ordering)

  }

}
