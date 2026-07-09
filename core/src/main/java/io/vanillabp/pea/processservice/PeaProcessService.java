package io.vanillabp.pea.processservice;

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
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
 * Once implemented, phase one will map to the Process-Engine-API's
 * {@code ExecutionMode.PREFLIGHT_CHECK} and phase two to {@code ExecutionMode.SYNC}.
 * <p>
 * The Process-Engine-API interfaces are constructor parameters so the platform modules
 * inject an implementation - by default the in-memory mock, later a real
 * Process-Engine-API implementation contributed by the application.
 * <p>
 * Skeleton stage: only {@link #getAdapterId()} and
 * {@link #needsTwoPhaseCommitForStartingWorkflows()} are implemented; every behavior
 * method throws {@link UnsupportedOperationException} until its feature story lands.
 *
 * @param <A> The workflow aggregate type
 */
public class PeaProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  private final StartProcessApi startProcessApi;

  private final CorrelationApi correlationApi;

  private final TaskSubscriptionApi taskSubscriptionApi;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  private final UserTaskCompletionApi userTaskCompletionApi;

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final CorrelationApi correlationApi,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi) {

    this.adapterId = adapterId;
    this.startProcessApi = startProcessApi;
    this.correlationApi = correlationApi;
    this.taskSubscriptionApi = taskSubscriptionApi;
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

    throw new UnsupportedOperationException("awarenessOfTask is implemented in a later story");

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("awarenessOfWorkflow is implemented in a later story");

  }

  @Override
  public void startWorkflowPhaseOne(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    throw new UnsupportedOperationException("startWorkflowPhaseOne is implemented in a later story");

  }

  @Override
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    throw new UnsupportedOperationException("startWorkflowPhaseTwo is implemented in a later story");

  }

}
