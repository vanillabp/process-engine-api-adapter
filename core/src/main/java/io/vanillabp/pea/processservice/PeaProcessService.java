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
 * as the {@link MigratableProcessService#AGGREGATE_ID_VARIABLE} payload variable.
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
public class PeaProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  private final StartProcessApi startProcessApi;

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi) {

    this.adapterId = adapterId;
    this.startProcessApi = startProcessApi;

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

    throw new UnsupportedOperationException("awarenessOfTask is implemented in a later story");

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
                bpmnProcessId, Map.of(AGGREGATE_ID_VARIABLE, aggregateId), ExecutionMode.PREFLIGHT_CHECK)),
        "Preflight check (phase one)",
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    // Phase two runs after the local transaction was committed, dispatched via the outbox:
    // actually create the process instance (SYNC). The aggregate id travels as a payload
    // variable. A failed start throws so the outbox retries the dispatch. Idempotency
    // (moduleId+bpmnProcessId+aggregateId) relies on the core-side
    // WorkflowInstanceRegistry - a separate story; until then a crash between a successful
    // create and marking the outbox entry DONE may duplicate the instance (at-least-once).
    await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                bpmnProcessId, Map.of(AGGREGATE_ID_VARIABLE, workflowAggregateId), ExecutionMode.SYNC)),
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
