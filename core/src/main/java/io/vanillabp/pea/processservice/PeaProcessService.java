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

  private final dev.bpmcrafters.processengineapi.correlation.CorrelationApi correlationApi;

  /**
   * The engine's signal API (story 42). Optional: an engine implementation without
   * it leaves signals unsupported, which {@link #sendSignalPhaseTwo} says.
   */
  private dev.bpmcrafters.processengineapi.correlation.SignalApi signalApi;

  /**
   * Hands over the engine's signal API.
   *
   * @param signalApi The signal API or <code>null</code>
   */
  public void setSignalApi(
      final dev.bpmcrafters.processengineapi.correlation.SignalApi signalApi) {

    this.signalApi = signalApi;

  }

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi userTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.correlation.CorrelationApi correlationApi) {

    this(adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, new io.vanillabp.pea.deployment.PeaDeployedProcesses());

  }

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi userTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.correlation.CorrelationApi correlationApi,
      final io.vanillabp.pea.deployment.PeaDeployedProcesses deployedProcesses) {

    this(adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, deployedProcesses, null);

  }

  /**
   * The core's sync model (story 28). The Process-Engine-API is treated as a
   * REMOTE BPMS - it can only evaluate what VanillaBP puts into the payload - so
   * the adapter's default is
   * {@link io.vanillabp.integration.adapter.spi.AggregateSyncMode#FULL}. May be
   * <code>null</code> (tests): only the technical aggregate-ID variable travels
   * then.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  /**
   * The default of this adapter: everything is shared unless the application
   * excludes it ({@code @NoSyncWithBPMS}).
   */
  public static final io.vanillabp.integration.adapter.spi.AggregateSyncMode SYNC_MODE = io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL;

  /**
   * The core's name-clash-avoidance model (story 35): translates process ids, message
   * names and error codes into what the engine knows. May be <code>null</code>
   * (tests): identifiers are passed through then.
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Sets the name-clash-avoidance support (the platform modules inject it after
   * construction).
   *
   * @param scoping The name-clash-avoidance support
   */
  public void setScoping(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

  }

  /**
   * The BPMN process id as the engine knows it.
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * A message name / error code as the engine knows it.
   */
  private String scopedIdentifier(
      final String workflowModuleId,
      final String identifier) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * The payload sent whenever this adapter talks to the engine on behalf of a
   * workflow: the aggregate's shared attributes PLUS - always, no matter what the
   * sync model says - the variable carrying the aggregate's ID (named after the
   * aggregate's ID property). The Process-Engine-API has no business-key slot:
   * that variable is how VanillaBP finds the workflow again.
   */
  private Map<String, Object> payloadOf(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final A workflowAggregate) {

    final var payload = new java.util.LinkedHashMap<String, Object>();
    if (aggregatePersistence == null) {
      // no persistence at hand (e.g. a test driving the SPI directly): neither the
      // shared attributes nor the technical ID variable can be determined
      return payload;
    }
    final var aggregate = workflowAggregate != null
        ? workflowAggregate
        : aggregatePersistence.loadById(workflowAggregateId);
    if ((aggregateSync != null) && (aggregate != null)) {
      payload.putAll(aggregateSync.syncedValues(aggregate, SYNC_MODE));
    }
    payload.put(aggregatePersistence.getAggregateIdName(), workflowAggregateId);
    return payload;

  }

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi userTaskCompletionApi,
      final dev.bpmcrafters.processengineapi.correlation.CorrelationApi correlationApi,
      final io.vanillabp.pea.deployment.PeaDeployedProcesses deployedProcesses,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    this.aggregateSync = aggregateSync;
    this.adapterId = adapterId;
    this.startProcessApi = startProcessApi;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;
    this.userTaskCompletionApi = userTaskCompletionApi;
    this.correlationApi = correlationApi;
    this.deployedProcesses = deployedProcesses;

  }

  /**
   * What this application version deployed - the only source of process
   * definitions and BPMN XML (the Process-Engine-API has no repository API, see
   * {@code GAPS.md}).
   */
  private final io.vanillabp.pea.deployment.PeaDeployedProcesses deployedProcesses;

  /**
   * The viewer API's process definitions. The Process-Engine-API knows neither
   * process definitions nor the definition a running instance uses, so the
   * adapter answers from what it deployed at boot. Call activities are NOT
   * reported: the Process-Engine-API has no BPMN model type and no notion of
   * call activities (GAPS.md) - which sub-process a call activity addresses is
   * BPMS-dialect-specific.
   */
  @Override
  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    if (historyContext != null) {
      // secondary history contexts are only ever handed out by getWorkflowHistory,
      // which this adapter cannot fill - so a context can never be one of ours
      log.warn(
          "Process-Engine-API adapter '{}': the history context '{}' is unknown - this adapter "
              + "reports no element history and therefore no secondary contexts (see GAPS.md)",
          adapterId,
          historyContext);
      return java.util.List.of();
    }

    final var deployed = deployedProcesses.deployedVersionOf(workflowModuleId, bpmnProcessId);
    if (deployed == null) {
      return java.util.List.of();
    }
    return java.util.List.of(
        new io.vanillabp.spi.process.ProcessDefinition(
            io.vanillabp.pea.deployment.PeaDeployedProcesses.definitionId(workflowModuleId,
                bpmnProcessId), bpmnProcessId, deployed.deploymentKey(), null));

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    final var deployed = deployedProcesses.byDefinitionId(processDefinitionId);
    return deployed == null
        ? null
        : new java.io.ByteArrayInputStream(
            deployed
                .model()
                .resource());

  }

  /**
   * The Process-Engine-API has NO history or query API at all (GAPS.md): neither
   * start/end times of a workflow nor an element history can be obtained. The
   * SPI's answer for that is a history without elements - reported for the
   * definition the workflow was deployed with.
   */
  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    if (historyContext != null) {
      return null;
    }
    final var deployed = deployedProcesses.deployedVersionOf(workflowModuleId, bpmnProcessId);
    if (deployed == null) {
      return null;
    }
    return new io.vanillabp.spi.process.WorkflowHistory(
        io.vanillabp.pea.deployment.PeaDeployedProcesses.definitionId(workflowModuleId,
            bpmnProcessId), null, null, null);

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
  public boolean deliversTasksAtLeastOnce() {

    // subscriptions report a task as completed AFTER the local transaction was
    // committed, so an engine which did not learn the result delivers the task again
    // (story 51). The identity across such a redelivery is the TASK ID the engine
    // reports, which every invocation context carries.
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
          // story 28b: the aggregate changed before the task was completed - the
          // engine only sees what the adapter sends
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
              taskId, payloadOf(aggregatePersistence, workflowAggregateId, null)))
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
          // story 28b: the error boundary's outgoing path may branch on the
          // aggregate, which the caller changed before canceling the task
          .completeTaskByError(new io.vanillabp.pea.wiring.PeaCompleteTaskByErrorCmd(
              taskId, scopedIdentifier(workflowModuleId,
                  bpmnErrorCode), "canceled via ProcessService#cancelTask", payloadOf(
                      aggregatePersistence, workflowAggregateId, null)))
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
          .completeTask(new io.vanillabp.pea.wiring.PeaCompleteTaskCmd(
              taskId, payloadOf(aggregatePersistence, workflowAggregateId, null)))
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
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // the Process-Engine-API offers NO way to probe a workflow's existence: there
    // is no query API, and CorrelateMessageCmd is FINAL - a PREFLIGHT_CHECK
    // execution mode cannot be transported (GAPS.md entry 11). The adapter
    // answers OPTIMISTICALLY - fine for single-BPMS setups; unsafe for multi-BPMS
    // migration scenarios (warned once).
    if (noWorkflowAwarenessWarned.compareAndSet(false, true)) {
      log.warn(
          "PEA[{}]: the Process-Engine-API cannot probe workflow awareness (no query API, final "
              + "command classes without execution-mode transport - see GAPS.md); answering "
              + "OPTIMISTICALLY (ACTIVE). Unsafe for BPMS migration scenarios.",
          adapterId);
    }
    return WorkflowAwareness.ACTIVE;

  }

  private final java.util.concurrent.atomic.AtomicBoolean noWorkflowAwarenessWarned = new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * The START re-dispatch mitigation probe (story 25) - STRICTER contract than
   * {@link #awarenessOfWorkflow(io.vanillabp.integration.spi.AggregatePersistenceAware, Object)}: the answer must NEVER be optimistic.
   * The Process-Engine-API cannot probe a workflow's existence at all (GAPS.md
   * entry 11), so the honest answer is
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}: the recovered start proceeds and
   * {@link #startWorkflowPhaseTwo}'s at-least-once contract applies (a duplicate
   * is the accepted residual - the election's optimistic ACTIVE would instead
   * SKIP the start and LOSE the workflow).
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public void correlateMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    // no phase-one PREFLIGHT_CHECK is possible: CorrelateMessageCmd is FINAL and
    // cannot carry a non-default execution mode (GAPS.md entry 11) - like
    // workflow starts, phase one validates nothing against the engine

  }

  /**
   * Pushing a changed workflow-aggregate is not supported (GAPS.md entry 18): the
   * Process-Engine-API can update the payload of a TASK, never the payload of a
   * running process instance. Phase one already refuses, so an application learns it
   * where it made the call instead of somewhere behind a commit.
   */
  @Override
  public void aggregateChangedPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    throw aggregateChangedNotSupported(workflowModuleId, bpmnProcessId);

  }

  @Override
  public void aggregateChangedPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    throw aggregateChangedNotSupported(workflowModuleId, bpmnProcessId);

  }

  private UnsupportedOperationException aggregateChangedNotSupported(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new UnsupportedOperationException(
        ("The Process-Engine-API adapter '%s' cannot push a changed workflow-aggregate of BPMN "
            + "process '%s' (workflow module '%s') to the engine: the API updates the payload of a "
            + "TASK, not of a running process instance (see GAPS.md entry 18). What the engine sees "
            + "changes when VanillaBP completes a task of the workflow - model a task the workflow "
            + "waits at, or run this workflow module on a BPMS whose adapter can update a running "
            + "instance.")
            .formatted(adapterId, bpmnProcessId, workflowModuleId));

  }

  /**
   * The Process-Engine-API is treated as a REMOTE BPMS: nothing may happen before
   * the caller's transaction committed, so the broadcast waits for phase two.
   * <p>
   * What phase one CAN answer is whether this adapter is able to broadcast at all:
   * without a {@code SignalApi} phase two can only fail, and it would fail behind the
   * commit, over and over, until the outbox entry is blocked. The application learns
   * it where it made the call instead.
   */
  @Override
  public void sendSignalPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    if (signalApi == null) {
      throw signalNotSupported(workflowModuleId, signalName);
    }

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param signalName The signal which cannot be broadcast
   * @return The failure telling an application that this adapter has no SignalApi
   */
  private UnsupportedOperationException signalNotSupported(
      final String workflowModuleId,
      final String signalName) {

    return new UnsupportedOperationException(
        ("The Process-Engine-API adapter '%s' was built without a SignalApi, so the signal '%s' of "
            + "workflow module '%s' cannot be broadcast! Provide a SignalApi implementation of your "
            + "engine to the adapter.")
            .formatted(adapterId, signalName, workflowModuleId));

  }

  @Override
  public void sendSignalPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    if (signalApi == null) {
      throw signalNotSupported(workflowModuleId, signalName);
    }

    // no payload travels with a signal: unlike a message it is not addressed to a
    // workflow, so there is no aggregate whose state could be meant
    try {
      signalApi
          .sendSignal(new dev.bpmcrafters.processengineapi.correlation.SendSignalCmd(
              scopedIdentifier(workflowModuleId, signalName), java.util.Map.of()))
          .get();
      log.info(
          "PEA[{}]: broadcast signal '{}' of workflow module '{}'",
          adapterId,
          signalName,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(
          "PEA[%s]: broadcasting signal '%s' of workflow module '%s' failed"
              .formatted(adapterId, signalName, workflowModuleId), e.getCause());
    }

  }

  @Override
  public void correlateMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    // PAYLOAD DOCTRINE: no message CONTENT travels - what does travel is the
    // aggregate state shared with the engine (story 28/28b), because the engine
    // can only evaluate expressions against the payload it was given.
    // CorrelateMessageCmd is FINAL - the command carries the DEFAULT execution
    // mode; the intended SYNC semantics cannot be expressed (GAPS.md entry 11).
    final var correlationKey = correlationId != null
        ? correlationId
        : String.valueOf(workflowAggregateId);
    try {
      correlationApi
          .correlateMessage(new dev.bpmcrafters.processengineapi.correlation.CorrelateMessageCmd(
              scopedIdentifier(workflowModuleId, messageName), payloadOf(
                  aggregatePersistence,
                  workflowAggregateId,
                  null), dev.bpmcrafters.processengineapi.correlation.Correlation.Companion
                      .withKey(correlationKey)))
          .get();
      log.info(
          "PEA[{}]: correlated message '{}' (correlation key '{}') for BPMN process '{}' of "
              + "workflow module '{}'",
          adapterId,
          messageName,
          correlationKey,
          bpmnProcessId,
          workflowModuleId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      // stale outbox entry - the subscription disappeared between the
      // dispatch-time probe and this correlation (at-least-once residual)
      log.warn(
          "PEA[{}]: message '{}' (correlation key '{}') could not be correlated anymore - "
              + "skipping the redelivered phase-two correlation",
          adapterId,
          messageName,
          correlationKey,
          e.getCause());
    }

  }

  @Override
  public void startWorkflowByMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName) {

    // start semantics: nothing to check against the engine (the degenerate
    // two-phase case); a PREFLIGHT_CHECK cannot be expressed either -
    // StartProcessByMessageCmd is FINAL (GAPS.md entry 11)

  }

  @Override
  public void startWorkflowByMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName) {

    // no message CONTENT travels - what travels is the aggregate state shared with
    // the engine (story 28) plus the technical aggregate-ID variable;
    // StartProcessByMessageCmd is FINAL - the DEFAULT execution mode travels
    // (GAPS.md entry 11); schedule deduplication comes from the outbox key
    // 'module|process|aggregateId'
    try {
      startProcessApi
          .startProcess(new dev.bpmcrafters.processengineapi.process.StartProcessByMessageCmd(
              scopedIdentifier(workflowModuleId, messageName), payloadOf(
                  aggregatePersistence, workflowAggregateId, null), java.util.Map.of()))
          .get();
      log.info(
          "PEA[{}]: started workflow by message '{}' for BPMN process '{}' of workflow module "
              + "'{}' (aggregate '{}')",
          adapterId,
          messageName,
          bpmnProcessId,
          workflowModuleId,
          workflowAggregateId);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException e) {
      throw new IllegalStateException(
          "Phase two of starting a workflow by message '%s' failed".formatted(messageName), e.getCause());
    }

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
                scopedProcessId(workflowModuleId, bpmnProcessId), payloadOf(
                    aggregatePersistence, aggregateId, workflowAggregate), ExecutionMode.PREFLIGHT_CHECK)),
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
                scopedProcessId(workflowModuleId, bpmnProcessId), payloadOf(
                    aggregatePersistence, workflowAggregateId, null), ExecutionMode.SYNC)),
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
