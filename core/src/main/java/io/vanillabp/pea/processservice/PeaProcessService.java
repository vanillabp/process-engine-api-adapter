package io.vanillabp.pea.processservice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.correlation.CorrelateMessageCmd;
import dev.bpmcrafters.processengineapi.correlation.Correlation;
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SendSignalCmd;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.process.ProcessInformation;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.process.StartProcessByMessageCmd;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;
import io.vanillabp.pea.wiring.PeaCompleteTaskByErrorCmd;
import io.vanillabp.pea.wiring.PeaCompleteTaskCmd;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * The Process-Engine-API adapter's per-adapter runtime the migration adapter delegates
 * to - one instance per configured adapter id.
 * <p>
 * The Process-Engine-API is treated as a remote BPMS: it cannot join the application's
 * local transaction, so workflow starts are routed through the generic outbox path like
 * every other operation which reaches the engine.
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
 * <p>
 * Three rules of this class are written down because several places rely on them: a preflight asks
 * inside the caller's transaction while the SYNC command runs after the commit (decision 4 in the
 * repository's DECISIONS.md), a failure of phase two is propagated rather than consumed (decision
 * 5 in the repository's DECISIONS.md), and only a capability the API lacks entirely counts as
 * permanent (decision 6 in the repository's DECISIONS.md).
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
// see decision 3 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class PeaProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  private final StartProcessApi startProcessApi;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  private final UserTaskCompletionApi userTaskCompletionApi;

  private final CorrelationApi correlationApi;

  /**
   * The engine's signal API. Optional: an engine implementation without
   * it leaves signals unsupported, which {@link #sendSignalPhaseTwo} says.
   */
  private SignalApi signalApi;

  /**
   * Hands over the engine's signal API.
   *
   * @param signalApi The signal API or <code>null</code>
   */
  public void setSignalApi(
      final SignalApi signalApi) {

    this.signalApi = signalApi;

  }

  /**
   * Runs a phase-one check right before the transaction of the workflow aggregate commits
   * (platform-supplied). Optional: without it the checks run when the application
   * calls, which is what this adapter did before - correct, with a wider window between the
   * check and the phase-two dispatch.
   */
  private final PreCommitRegistrar preCommitRegistrar;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi,
      final CorrelationApi correlationApi) {

    this(adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, new PeaDeployedProcesses());

  }

  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi,
      final CorrelationApi correlationApi,
      final PeaDeployedProcesses deployedProcesses) {

    this(adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, deployedProcesses, null);

  }

  /**
   * The core's sync model. The Process-Engine-API is treated as a
   * REMOTE BPMS - it can only evaluate what VanillaBP puts into the payload - so
   * the adapter's default is {@link AggregateSyncMode#FULL}. May be <code>null</code>
   * (tests): only the technical aggregate-ID variable travels then.
   */
  private final WorkflowAggregateSync aggregateSync;

  /**
   * The default of this adapter: everything is shared unless the application
   * excludes it ({@code @NoSyncWithBPMS}).
   */
  public static final AggregateSyncMode SYNC_MODE = AggregateSyncMode.FULL;

  /**
   * The core's name-clash-avoidance model: translates process ids, message
   * names and error codes into what the engine knows. May be <code>null</code>
   * (tests): identifiers are passed through then.
   */
  private final NameClashAvoidanceSupport scoping;

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
   * that variable is how VanillaBP finds the workflow again, so an aggregate
   * annotated {@code @NoSyncWithBPMS} must not lose it. See
   * {@code PeaSharedValuesTest}.
   */
  private Map<String, Object> payloadOf(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final A workflowAggregate) {

    final var payload = new LinkedHashMap<String, Object>();
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
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi,
      final CorrelationApi correlationApi,
      final PeaDeployedProcesses deployedProcesses,
      final AdapterCollaborators collaborators) {

    this.collaborators = collaborators;
    this.aggregateSync = collaborators == null
        ? null
        : collaborators.workflowAggregateSync();
    this.scoping = collaborators == null
        ? null
        : collaborators.scoping();
    this.preCommitRegistrar = collaborators == null
        ? null
        : collaborators.preCommitRegistrar();
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
  private final PeaDeployedProcesses deployedProcesses;

  /**
   * The viewer API's process definitions. The Process-Engine-API knows neither
   * process definitions nor the definition a running instance uses, so the
   * adapter answers from what it deployed at boot. Call activities are NOT
   * reported: the Process-Engine-API has no BPMN model type and no notion of
   * call activities (GAPS.md) - which sub-process a call activity addresses is
   * BPMS-dialect-specific.
   */
  @Override
  public List<ProcessDefinition> getProcessDefinitions(
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
      return List.of();
    }

    final var deployed = deployedProcesses.deployedVersionOf(workflowModuleId, bpmnProcessId);
    if (deployed == null) {
      return List.of();
    }
    return List.of(
        new ProcessDefinition(
            PeaDeployedProcesses.definitionId(workflowModuleId,
                bpmnProcessId), bpmnProcessId, deployed.deploymentKey(), null));

  }

  @Override
  public InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    final var deployed = deployedProcesses.byDefinitionId(processDefinitionId);
    return deployed == null
        ? null
        : new ByteArrayInputStream(
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
  public WorkflowHistory getWorkflowHistory(
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
    return new WorkflowHistory(
        PeaDeployedProcesses.definitionId(workflowModuleId,
            bpmnProcessId), null, null, null);

  }

  public String getAdapterType() {

    return PeaAdapter.ADAPTER_TYPE;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * What this adapter does for each operation, in both phases.
   * <p>
   * Phase one is a <code>PREFLIGHT_CHECK</code> command wherever the API offers one: it
   * validates without advancing, and it runs as a pre-commit hook so the window to the
   * phase-two dispatch stays small. Phase two sends the real command and is idempotent,
   * because the outbox dispatches at-least-once.
   * <p>
   * Two operations are in the map although this adapter cannot serve them: broadcasting
   * a signal without a <code>SignalApi</code>, and pushing a changed aggregate at all.
   * They stay because the handler is where the reason lives - which API is missing, or
   * which entry of GAPS.md names the gap - and a message which says only "this adapter
   * cannot" would leave the reader without the fix.
   */
  @Override
  public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {

    return Map
        .ofEntries(
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW,
                    PhaseOperationHandler.of(this::preflightStart, this::startWorkflow)),
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW_BY_MESSAGE,
                    PhaseOperationHandler.of(this::preflightStartByMessage, this::startWorkflowByMessage)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteTask, this::completeTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    PhaseOperationHandler.of(this::preflightCancelTask, this::cancelTask)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteUserTask, this::completeUserTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCancelUserTask, this::cancelUserTask)),
            Map
                .entry(
                    PhaseOperation.CORRELATE_MESSAGE,
                    PhaseOperationHandler.of(this::preflightCorrelateMessage, this::correlateMessage)),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    PhaseOperationHandler.of(this::preflightSendSignal, this::sendSignal)),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    PhaseOperationHandler.of(this::preflightAggregateChanged, this::pushChangedAggregate)));

  }

  @Override
  public boolean deliversTasksAtLeastOnce() {

    // subscriptions report a task as completed AFTER the local transaction was
    // committed, so an engine which did not learn the result delivers the task again.
    // The identity across such a redelivery is the TASK ID the engine
    // reports, which every invocation context carries.
    return true;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final WorkflowScope scope,
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
          .completeTask(new PeaCompleteTaskCmd(
              taskId, ExecutionMode.PREFLIGHT_CHECK))
          .get();
      return WorkflowAwareness.ACTIVE;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    } catch (final ExecutionException e) {
      log.debug(
          "PEA[{}]: preflight probe of task '{}' failed - reporting UNKNOWN_TO_BPMS",
          adapterId,
          taskId,
          e.getCause());
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  private void preflightCompleteTask(
      final PhaseOneRequest<A> request) {

    // the non-advancing phase-one check: a PREFLIGHT_CHECK completion validates the task
    // still exists so the local transaction can abort early - run right before the commit,
    // which keeps the window to the phase-two dispatch small
    beforeCommit(
        request.aggregatePersistence(),
        () -> preflight(new PeaCompleteTaskCmd(
            request.taskId(), ExecutionMode.PREFLIGHT_CHECK), request.taskId(),
            "completing"));

  }

  private void preflightCancelTask(
      final PhaseOneRequest<A> request) {

    beforeCommit(
        request.aggregatePersistence(),
        () -> preflight(new PeaCompleteTaskCmd(
            request.taskId(), ExecutionMode.PREFLIGHT_CHECK), request.taskId(),
            "canceling"));

  }

  /**
   * Hands a phase-one check to the platform's pre-commit hook, so it runs right
   * before the transaction of the workflow aggregate commits instead of when the application
   * called - the later the check, the smaller the window in which its answer goes stale
   * before phase two acts on it. Without a hook the check runs immediately.
   *
   * @param aggregatePersistence The persistence of the aggregate whose transaction is meant
   * @param check The check to run
   */
  private void beforeCommit(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Runnable check) {

    // the registrar always arrives with the collaborators, so the only reason left to run
    // the check here and now is a caller which brought no persistence to name
    if (aggregatePersistence == null) {
      check.run();
      return;
    }
    preCommitRegistrar.beforeCommit(aggregatePersistence.getAggregateClass(), check);

  }

  private void preflight(
      final CompleteTaskCmd command,
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
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          ("The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction "
              + "%s it! If this task was completed by a concurrent redelivery, retrying the "
              + "business operation will end in the documented no-op.")
              .formatted(taskId, operationDescription), e.getCause());
    }

  }

  private void completeTask(
      final PhaseTwoRequest<A> request) {

    try {
      serviceTaskCompletionApi
          // The aggregate changed before the task was completed - the
          // engine only sees what the adapter sends
          .completeTask(new PeaCompleteTaskCmd(
              request.taskId(), payloadOf(request.aggregatePersistence(), request.workflowAggregateId(), null)))
          .get();
      log.info(
          "PEA[{}]: completed task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw phaseTwoFailed(
          "completing task '%s'".formatted(request.taskId()), "task", request.workflowModuleId(),
          request.bpmnProcessId(), e);
    } catch (final ExecutionException e) {
      throw phaseTwoFailed(
          "completing task '%s'".formatted(request.taskId()), "task", request.workflowModuleId(),
          request.bpmnProcessId(), e.getCause());
    }

  }

  private void cancelTask(
      final PhaseTwoRequest<A> request) {

    try {
      serviceTaskCompletionApi
          // The error boundary's outgoing path may branch on the
          // aggregate, which the caller changed before canceling the task
          .completeTaskByError(new PeaCompleteTaskByErrorCmd(
              request.taskId(), scopedIdentifier(request.workflowModuleId(),
                  request.bpmnErrorCode()), "canceled via ProcessService#cancelTask", payloadOf(
                      request.aggregatePersistence(), request.workflowAggregateId(), null)))
          .get();
      log.info(
          "PEA[{}]: canceled task '{}' (error code '{}') of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnErrorCode(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw phaseTwoFailed(
          "canceling task '%s'".formatted(request.taskId()), "task", request.workflowModuleId(),
          request.bpmnProcessId(), e);
    } catch (final ExecutionException e) {
      throw phaseTwoFailed(
          "canceling task '%s'".formatted(request.taskId()), "task", request.workflowModuleId(),
          request.bpmnProcessId(), e.getCause());
    }

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // same probe shape as service tasks: a PREFLIGHT_CHECK completion against the
    // USER-task completion API (untyped failures map to UNKNOWN - GAPS.md)
    try {
      userTaskCompletionApi
          .completeTask(new PeaCompleteTaskCmd(
              taskId, ExecutionMode.PREFLIGHT_CHECK))
          .get();
      return WorkflowAwareness.ACTIVE;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    } catch (final ExecutionException e) {
      log.debug(
          "PEA[{}]: preflight probe of user task '{}' failed - reporting UNKNOWN_TO_BPMS",
          adapterId,
          taskId,
          e.getCause());
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  private void preflightCompleteUserTask(
      final PhaseOneRequest<A> request) {

    beforeCommit(request.aggregatePersistence(), () -> preflightUserTask(request.taskId(), "completing user"));

  }

  private void preflightCancelUserTask(
      final PhaseOneRequest<A> request) {

    beforeCommit(request.aggregatePersistence(), () -> preflightUserTask(request.taskId(), "canceling user"));

  }

  private void preflightUserTask(
      final String taskId,
      final String operationDescription) {

    try {
      userTaskCompletionApi
          .completeTask(new PeaCompleteTaskCmd(
              taskId, ExecutionMode.PREFLIGHT_CHECK))
          .get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while %s task '%s'".formatted(operationDescription, taskId), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          ("The user task '%s' is gone (completed or canceled meanwhile) - aborting the "
              + "transaction %s it!")
              .formatted(taskId, operationDescription), e.getCause());
    }

  }

  private void completeUserTask(
      final PhaseTwoRequest<A> request) {

    try {
      userTaskCompletionApi
          .completeTask(new PeaCompleteTaskCmd(
              request.taskId(), payloadOf(request.aggregatePersistence(), request.workflowAggregateId(), null)))
          .get();
      log.info(
          "PEA[{}]: completed user task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw phaseTwoFailed(
          "completing user task '%s'".formatted(request.taskId()), "user task", request.workflowModuleId(),
          request.bpmnProcessId(), e);
    } catch (final ExecutionException e) {
      throw phaseTwoFailed(
          "completing user task '%s'".formatted(request.taskId()), "user task", request.workflowModuleId(),
          request.bpmnProcessId(), e.getCause());
    }

  }

  private void cancelUserTask(
      final PhaseTwoRequest<A> request) {

    try {
      userTaskCompletionApi
          .completeTaskByError(new PeaCompleteTaskByErrorCmd(
              request.taskId(), request.bpmnErrorCode(), "canceled via ProcessService#cancelUserTask"))
          .get();
      log.info(
          "PEA[{}]: canceled user task '{}' (error code '{}') of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnErrorCode(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw phaseTwoFailed(
          "canceling user task '%s'".formatted(request.taskId()), "user task", request.workflowModuleId(),
          request.bpmnProcessId(), e);
    } catch (final ExecutionException e) {
      throw phaseTwoFailed(
          "canceling user task '%s'".formatted(request.taskId()), "user task", request.workflowModuleId(),
          request.bpmnProcessId(), e.getCause());
    }

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
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

  private final AtomicBoolean noWorkflowAwarenessWarned = new AtomicBoolean();

  /**
   * Always <code>false</code>: the Process-Engine-API has no query API, and its command
   * classes carry no execution mode which could ask without acting (GAPS.md entry 11).
   * {@link #awarenessOfWorkflow} therefore answers optimistically, which is right while
   * this is the only configured BPMS and a guess as soon as it is not - and saying so
   * here is what makes the core refuse the second case while it boots rather than
   * routing operations by list order.
   *
   * @return <code>false</code>
   */
  @Override
  public boolean canLocateWorkflows() {

    return false;

  }

  /**
   * The START re-dispatch mitigation probe - STRICTER contract than
   * {@link #awarenessOfWorkflow}: the answer must NEVER be optimistic.
   * The Process-Engine-API cannot probe a workflow's existence at all (GAPS.md
   * entry 11), so the honest answer is
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}: the recovered start proceeds and
   * {@link #startWorkflowPhaseTwo}'s at-least-once contract applies (a duplicate
   * is the accepted residual - the election's optimistic ACTIVE would instead
   * SKIP the start and LOSE the workflow).
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  /**
   * What a failed phase-two operation throws.
   * <p>
   * The Process-Engine-API declares no typed exceptions, so this adapter cannot tell "the
   * task is gone" - the accepted at-least-once residual - from "the engine is unreachable".
   * It used to assume the first and consume the outbox entry with a WARN line, which lost
   * the operation whenever the second was true. Now every failure reaches the outbox: it
   * repeats and finally blocks the entry, so a repeated operation on something the engine
   * already finished costs a retry series and a blocked entry, while an operation is never
   * silently dropped again.
   *
   * @param operationDescription What was attempted
   * @param subject What it was attempted on, as a noun ("task", "user task", "message")
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param cause What the API answered
   * @return The failure to throw
   */
  private IllegalStateException phaseTwoFailed(
      final String operationDescription,
      final String subject,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Throwable cause) {

    return new IllegalStateException(
        """
            Phase two of %s failed for BPMN process '%s' of workflow module '%s' (adapter '%s')! \
            The Process-Engine-API reports no typed errors, so this adapter cannot tell an engine \
            which is unreachable from one which finished this %s meanwhile - the operation is \
            therefore repeated by the outbox and finally blocked, instead of being dropped with a \
            log line. A blocked entry on a %s the engine finished already is the harmless case: \
            look at the cause, then remove the entry."""
            .formatted(
                operationDescription, bpmnProcessId, workflowModuleId, adapterId, subject, subject), cause);

  }

  /**
   * Whether repeating a failed phase-two operation may succeed.
   * <p>
   * The Process-Engine-API declares no typed exceptions: whatever an engine
   * implementation throws arrives wrapped in an {@link ExecutionException},
   * and "the engine is unreachable" looks exactly like "the engine refused". So only one
   * family can be classified, and it is the one this adapter throws itself: where the API
   * cannot do what VanillaBP asks - a signal without a {@code SignalApi}, pushing a changed
   * aggregate - every attempt ends the same way, and the outbox entry is blocked at once
   * instead of being retried until its attempts are used up.
   * <p>
   * Everything else stays repeatable, which is the safe default of the SPI.
   *
   * @param failure What the phase-two operation threw
   * @return Whether repeating the operation may succeed
   */
  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof UnsupportedOperationException) {
        return false;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return true;

  }

  private void preflightCorrelateMessage(
      final PhaseOneRequest<A> request) {

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
  private void preflightAggregateChanged(
      final PhaseOneRequest<A> request) {

    throw aggregateChangedNotSupported(request.workflowModuleId(), request.bpmnProcessId());

  }

  private void pushChangedAggregate(
      final PhaseTwoRequest<A> request) {

    throw aggregateChangedNotSupported(request.workflowModuleId(), request.bpmnProcessId());

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
  private void preflightSendSignal(
      final PhaseOneRequest<A> request) {

    if (signalApi == null) {
      throw signalNotSupported(request.workflowModuleId(), request.signalName());
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

  private void sendSignal(
      final PhaseTwoRequest<A> request) {

    if (signalApi == null) {
      throw signalNotSupported(request.workflowModuleId(), request.signalName());
    }

    // no payload travels with a signal: unlike a message it is not addressed to a
    // workflow, so there is no aggregate whose state could be meant
    try {
      signalApi
          .sendSignal(new SendSignalCmd(
              scopedIdentifier(request.workflowModuleId(), request.signalName()), Map.of()))
          .get();
      log.info(
          "PEA[{}]: broadcast signal '{}' of workflow module '{}'",
          adapterId,
          request.signalName(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      // returning here would mark the outbox entry done although nothing was
      // broadcast, and a signal nobody receives is a workflow waiting forever
      throw new IllegalStateException(
          "PEA[%s]: broadcasting signal '%s' of workflow module '%s' was interrupted"
              .formatted(adapterId, request.signalName(), request.workflowModuleId()), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          "PEA[%s]: broadcasting signal '%s' of workflow module '%s' failed"
              .formatted(adapterId, request.signalName(), request.workflowModuleId()), e.getCause());
    }

  }

  private void correlateMessage(
      final PhaseTwoRequest<A> request) {

    // no message CONTENT travels - what does travel is the aggregate state shared
    // with the engine (see decision 1 in the repository's DECISIONS.md).
    // CorrelateMessageCmd is FINAL - the command carries the DEFAULT execution
    // mode; the intended SYNC semantics cannot be expressed (GAPS.md entry 11).
    final var correlationKey = request.correlationId() != null
        ? request.correlationId()
        : String.valueOf(request.workflowAggregateId());
    try {
      correlationApi
          .correlateMessage(new CorrelateMessageCmd(
              scopedIdentifier(request.workflowModuleId(), request.messageName()), payloadOf(
                  request.aggregatePersistence(),
                  request.workflowAggregateId(),
                  null), Correlation.Companion
                      .withKey(correlationKey)))
          .get();
      log.info(
          "PEA[{}]: correlated message '{}' (correlation key '{}') for BPMN process '{}' of "
              + "workflow module '{}'",
          adapterId,
          request.messageName(),
          correlationKey,
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw phaseTwoFailed(
          "correlating message '%s' (correlation key '%s')".formatted(request.messageName(), correlationKey),
          "message", request.workflowModuleId(), request.bpmnProcessId(), e);
    } catch (final ExecutionException e) {
      throw phaseTwoFailed(
          "correlating message '%s' (correlation key '%s')".formatted(request.messageName(), correlationKey),
          "message", request.workflowModuleId(), request.bpmnProcessId(), e.getCause());
    }

  }

  private void preflightStartByMessage(
      final PhaseOneRequest<A> request) {

    // start semantics: nothing to check against the engine (the degenerate
    // two-phase case); a PREFLIGHT_CHECK cannot be expressed either -
    // StartProcessByMessageCmd is FINAL (GAPS.md entry 11)

  }

  private void startWorkflowByMessage(
      final PhaseTwoRequest<A> request) {

    // no message CONTENT travels - what travels is the aggregate state shared with
    // the engine plus the technical aggregate-ID variable;
    // StartProcessByMessageCmd is FINAL - the DEFAULT execution mode travels
    // (GAPS.md entry 11); schedule deduplication comes from the outbox key
    // 'module|process|aggregateId'
    try {
      startProcessApi
          .startProcess(new StartProcessByMessageCmd(
              scopedIdentifier(request.workflowModuleId(), request.messageName()), payloadOf(
                  request.aggregatePersistence(), request.workflowAggregateId(), null), Map.of()))
          .get();
      log.info(
          "PEA[{}]: started workflow by message '{}' for BPMN process '{}' of workflow module "
              + "'{}' (aggregate '{}')",
          adapterId,
          request.messageName(),
          request.bpmnProcessId(),
          request.workflowModuleId(),
          request.workflowAggregateId());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      // returning here would mark the outbox entry done although no workflow was
      // started, and the application's database would carry an aggregate no engine knows
      throw new IllegalStateException(
          "Phase two of starting a workflow by message '%s' was interrupted".formatted(request.messageName()), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          "Phase two of starting a workflow by message '%s' failed".formatted(request.messageName()), e.getCause());
    }

  }

  private void preflightStart(
      final PhaseOneRequest<A> request) {

    // Phase one runs inside the caller's local transaction: only validate optimistically
    // (PREFLIGHT_CHECK). The Process-Engine-API is remote, so the instance itself must not
    // be created here - that happens in phase two after the transaction committed, otherwise
    // a rolled-back transaction would leave a ghost workflow behind. A failed check throws
    // and thereby rolls the caller's transaction back (fail fast instead of committing an
    // outbox entry which cannot be dispatched).
    final var aggregateId = request.aggregatePersistence().getAggregateId(request.workflowAggregate());
    await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                scopedProcessId(request.workflowModuleId(), request.bpmnProcessId()), payloadOf(
                    request.aggregatePersistence(), aggregateId,
                    request.workflowAggregate()), ExecutionMode.PREFLIGHT_CHECK)),
        "Preflight check (phase one)",
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void startWorkflow(
      final PhaseTwoRequest<A> request) {

    // Phase two runs after the local transaction was committed, dispatched via the outbox:
    // actually create the process instance (SYNC). The aggregate id travels as a payload
    // variable named after the aggregate's ID property. A failed start throws so the
    // outbox retries the dispatch. A crash between a successful create and marking the
    // outbox entry DONE may duplicate the instance (at-least-once): a workflow is located
    // by asking rather than remembered in a registry (decision 25 of the platform's
    // DECISIONS.md), and the core's probe before a re-dispatched start is what narrows
    // the window - which this adapter answers optimistically (GAPS.md, entry 11).
    await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                scopedProcessId(request.workflowModuleId(), request.bpmnProcessId()), payloadOf(
                    request.aggregatePersistence(), request.workflowAggregateId(), null), ExecutionMode.SYNC)),
        "Starting the workflow (phase two)",
        request.bpmnProcessId(),
        request.workflowModuleId());

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
