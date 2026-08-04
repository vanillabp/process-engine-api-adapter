package io.vanillabp.pea.processservice;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.process.ProcessInformation;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.pea.PeaAdapter;

/**
 * The Process-Engine-API adapter's per-adapter runtime the migration adapter delegates
 * to - one instance per configured adapter id.
 * <p>
 * The Process-Engine-API is treated as a remote BPMS: it cannot join the application's
 * local transaction, therefore {@link #needsTwoPhaseCommitForStartingWorkflows()}
 * returns {@code true} and workflow starts are routed through the generic outbox path.
 * Phase one maps to the Process-Engine-API's {@code ExecutionMode.PREFLIGHT_CHECK} (validate
 * only, inside the caller's transaction) and phase two to {@code ExecutionMode.SYNC} (create
 * the instance, after commit, dispatched via the outbox). The workflow-aggregate id travels
 * as a payload variable named after the aggregate's ID property (see
 * {@link AggregatePersistenceAware#getAggregateIdName()}) - how the aggregate's ID is
 * stored in the BPMS is the adapter's decision, and the Process-Engine-API (like Camunda 8)
 * stores it as a payload variable.
 * <p>
 * The {@link CompletableFuture}s returned by the Process-Engine-API are joined: a failed
 * {@code PREFLIGHT_CHECK} has to fail the caller's transaction (phase one) and a failed
 * {@code SYNC} has to throw so the outbox retries the dispatch (phase two) - discarding
 * the future would consume the outbox entry and the workflow would silently never start.
 * <p>
 * Only the Process-Engine-APIs actually used are constructor parameters (currently
 * {@link StartProcessApi}); upcoming stories add theirs when they consume them. The
 * platform modules inject an implementation - by default the in-memory mock, later a
 * real Process-Engine-API implementation contributed by the application.
 *
 * @param <A> The workflow aggregate type
 */
@lombok.extern.slf4j.Slf4j
public class PeaProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  private final StartProcessApi startProcessApi;

  private final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi;

  private final dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi userTaskCompletionApi;

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi userTaskCompletionApi) {

    this.adapterId = adapterId;
    this.startProcessApi = startProcessApi;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;
    this.userTaskCompletionApi = userTaskCompletionApi;

  }

  public String getAdapterType() {

    return PeaAdapter.ADAPTER_TYPE;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    // The Process-Engine-API is treated as a remote BPMS: it cannot join the local
    // transaction, so starting a workflow has to run through the generic outbox path.
    return true;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final Object workflowAggregateId,
      final String taskId) {

    // the probe is a PREFLIGHT_CHECK completion - exactly what the mode is for
    // (validate only, optimistic fast-fail, never advances the process). The
    // Process-Engine-API does not classify failures (no typed exceptions), so a
    // failing preflight cannot be told apart from an unreachable engine - it maps
    // to UNKNOWN_TO_BPMS (see GAPS.md; the BPMS_UNAVAILABLE distinction would
    // need typed errors).
    try {
      serviceTaskCompletionApi
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
              taskId, dev.bpmcrafters.processengineapi.ExecutionMode.PREFLIGHT_CHECK))
          .get();
      return WorkflowAwareness.ACTIVE;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    } catch (final java.util.concurrent.ExecutionException e) {
      log.debug(
          "PEA[{}]: preflight probe of task '{}' failed - reporting UNKNOWN_TO_BPMS",
          adapterId,
          taskId,
          e.getCause());
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    // the non-advancing phase-one check: a PREFLIGHT_CHECK completion validates
    // the task still exists so the local transaction can abort early
    preflight(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
        taskId, dev.bpmcrafters.processengineapi.ExecutionMode.PREFLIGHT_CHECK), taskId, "completing");

  }

  @Override
  public void cancelTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    preflight(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
        taskId, dev.bpmcrafters.processengineapi.ExecutionMode.PREFLIGHT_CHECK), taskId, "canceling");

  }

  private void preflight(
      final dev.bpmcrafters.processengineapi.task.CompleteTaskCmd command,
      final String taskId,
      final String operationDescription) {

    try {
      serviceTaskCompletionApi
          .completeTask(command)
          .get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while %s task '%s'".formatted(operationDescription, taskId), e);
    } catch (final java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(
          ("The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction "
              + "%s it! If this task was completed by a concurrent redelivery, retrying the "
              + "business operation will end in the documented no-op.")
              .formatted(taskId, operationDescription), e.getCause());
    }

  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    try {
      serviceTaskCompletionApi
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(taskId))
          .get();
      log.info(
          "PEA[{}]: completed task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          taskId,
          bpmnProcessId,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      // stale outbox entry - the at-least-once residual, the entry is consumed
      log.warn(
          "PEA[{}]: task '{}' is gone - skipping the redelivered phase-two completion",
          adapterId,
          taskId,
          e.getCause());
    }

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    try {
      serviceTaskCompletionApi
          .completeTaskByError(new io.vanillabp.pea.wiring.PeaCompleteTaskByErrorCmd(
              taskId, bpmnErrorCode, "canceled via ProcessService#cancelTask"))
          .get();
      log.info(
          "PEA[{}]: canceled task '{}' (error code '{}') of BPMN process '{}' of workflow module '{}'",
          adapterId,
          taskId,
          bpmnErrorCode,
          bpmnProcessId,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      log.warn(
          "PEA[{}]: task '{}' is gone - skipping the redelivered phase-two cancellation",
          adapterId,
          taskId,
          e.getCause());
    }

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final Object workflowAggregateId,
      final String taskId) {

    // same probe shape as service tasks: a PREFLIGHT_CHECK completion against the
    // USER-task completion API (untyped failures map to UNKNOWN - GAPS.md)
    try {
      userTaskCompletionApi
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
              taskId, dev.bpmcrafters.processengineapi.ExecutionMode.PREFLIGHT_CHECK))
          .get();
      return WorkflowAwareness.ACTIVE;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    } catch (final java.util.concurrent.ExecutionException e) {
      log.debug(
          "PEA[{}]: preflight probe of user task '{}' failed - reporting UNKNOWN_TO_BPMS",
          adapterId,
          taskId,
          e.getCause());
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  @Override
  public void completeUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    preflightUserTask(taskId, "completing user");

  }

  @Override
  public void cancelUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    preflightUserTask(taskId, "canceling user");

  }

  private void preflightUserTask(
      final String taskId,
      final String operationDescription) {

    try {
      userTaskCompletionApi
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
              taskId, dev.bpmcrafters.processengineapi.ExecutionMode.PREFLIGHT_CHECK))
          .get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while %s task '%s'".formatted(operationDescription, taskId), e);
    } catch (final java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(
          ("The user task '%s' is gone (completed or canceled meanwhile) - aborting the "
              + "transaction %s it!")
              .formatted(taskId, operationDescription), e.getCause());
    }

  }

  @Override
  public void completeUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    try {
      userTaskCompletionApi
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(taskId))
          .get();
      log.info(
          "PEA[{}]: completed user task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          taskId,
          bpmnProcessId,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      log.warn(
          "PEA[{}]: user task '{}' is gone - skipping the redelivered phase-two completion",
          adapterId,
          taskId,
          e.getCause());
    }

  }

  @Override
  public void cancelUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    try {
      userTaskCompletionApi
          .completeTaskByError(new io.vanillabp.pea.wiring.PeaCompleteTaskByErrorCmd(
              taskId, bpmnErrorCode, "canceled via ProcessService#cancelUserTask"))
          .get();
      log.info(
          "PEA[{}]: canceled user task '{}' (error code '{}') of BPMN process '{}' of workflow module '{}'",
          adapterId,
          taskId,
          bpmnErrorCode,
          bpmnProcessId,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      log.warn(
          "PEA[{}]: user task '{}' is gone - skipping the redelivered phase-two cancellation",
          adapterId,
          taskId,
          e.getCause());
    }

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("awarenessOfWorkflow is implemented in a later story");

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    // Phase one runs inside the caller's local transaction: only validate optimistically
    // (PREFLIGHT_CHECK). The Process-Engine-API is remote, so the instance itself must not
    // be created here - that happens in phase two after the transaction committed, otherwise
    // a rolled-back transaction would leave a ghost workflow behind. A failed check throws
    // and thereby rolls the caller's transaction back (fail fast instead of committing an
    // outbox entry which cannot be dispatched).
    final var aggregateId = aggregatePersistence.getAggregateId(workflowAggregate);
    await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                bpmnProcessId, Map
                    .of(aggregatePersistence.getAggregateIdName(), aggregateId), ExecutionMode.PREFLIGHT_CHECK)),
        "Preflight check (phase one)",
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // Phase two runs after the local transaction was committed, dispatched via the outbox:
    // actually create the process instance (SYNC). The aggregate id travels as a payload
    // variable named after the aggregate's ID property. A failed start throws so the
    // outbox retries the dispatch. Idempotency (moduleId+bpmnProcessId+aggregateId)
    // relies on the core-side WorkflowInstanceRegistry - a separate story; until then a
    // crash between a successful create and marking the outbox entry DONE may duplicate
    // the instance (at-least-once).
    await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                bpmnProcessId, Map
                    .of(aggregatePersistence.getAggregateIdName(), workflowAggregateId), ExecutionMode.SYNC)),
        "Starting the workflow (phase two)",
        bpmnProcessId,
        workflowModuleId);

  }

  /**
   * Joins the future of a Process-Engine-API command (same pattern as
   * {@code PeaDeploymentService.deployResources}): the outcome has to reach the
   * caller - in phase one to roll the local transaction back, in phase two to make
   * the outbox retry.
   *
   * @param future The future returned by the Process-Engine-API
   * @param operation Description used in error messages
   * @param bpmnProcessId The BPMN process id (used in error messages)
   * @param workflowModuleId The workflow module id (used in error messages)
   */
  private static void await(
      final CompletableFuture<ProcessInformation> future,
      final String operation,
      final String bpmnProcessId,
      final String workflowModuleId) {

    try {
      future.get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "%s of BPMN process '%s' of workflow module '%s' was interrupted"
              .formatted(operation, bpmnProcessId, workflowModuleId), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          "%s of BPMN process '%s' of workflow module '%s' failed"
              .formatted(operation, bpmnProcessId, workflowModuleId), e.getCause());
    }

  }

}
