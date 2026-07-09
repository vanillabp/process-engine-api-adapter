package io.vanillabp.pea.processservice;

import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
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
 * Phase one maps to the Process-Engine-API's {@code ExecutionMode.PREFLIGHT_CHECK} (validate
 * only, inside the caller's transaction) and phase two to {@code ExecutionMode.SYNC} (create
 * the instance, after commit, dispatched via the outbox). The workflow-aggregate id travels
 * as the {@link #AGGREGATE_ID_VARIABLE} payload variable.
 * <p>
 * The Process-Engine-API interfaces are constructor parameters so the platform modules
 * inject an implementation - by default the in-memory mock, later a real
 * Process-Engine-API implementation contributed by the application.
 *
 * @param <A> The workflow aggregate type
 */
public class PeaProcessService<A> implements MigratableProcessService<A> {

  /**
   * Name of the payload variable the workflow-aggregate id is passed as when starting a
   * process instance. The Process-Engine-API start command has no dedicated
   * business-key/correlation slot (see {@code GAPS.md}), so the aggregate id travels as an
   * ordinary process variable. Kept in sync (by convention, not by a shared dependency)
   * with the mock's {@code InMemoryProcessEngine.AGGREGATE_ID_VARIABLE}.
   */
  public static final String AGGREGATE_ID_VARIABLE = "aggregateId";

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
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    // Phase one runs inside the caller's local transaction: only validate optimistically
    // (PREFLIGHT_CHECK). The Process-Engine-API is remote, so the instance itself must not
    // be created here - that happens in phase two after the transaction committed, otherwise
    // a rolled-back transaction would leave a ghost workflow behind.
    final var aggregateId = aggregatePersistence.getAggregateId(workflowAggregate);
    startProcessApi.startProcess(
        new PeaStartProcessCommand(
            bpmnProcessId, Map.of(AGGREGATE_ID_VARIABLE, aggregateId), ExecutionMode.PREFLIGHT_CHECK));

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    // Phase two runs after the local transaction was committed, dispatched via the outbox:
    // actually create the process instance (SYNC). The aggregate id travels as a payload
    // variable. Idempotency (moduleId+bpmnProcessId+aggregateId) relies on the core-side
    // WorkflowInstanceRegistry - a separate story; until then a crash between a successful
    // create and the outbox entry removal may duplicate the instance (at-least-once).
    startProcessApi.startProcess(
        new PeaStartProcessCommand(
            bpmnProcessId, Map.of(AGGREGATE_ID_VARIABLE, workflowAggregateId), ExecutionMode.SYNC));

  }

}
