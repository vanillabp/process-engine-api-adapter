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
import io.vanillabp.pea.wiring.PeaTaskMeta;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.TaskNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * What the migration adapter calls to talk to the engine behind the Process-Engine-API. There
 * is one instance per configured adapter id.
 * <p>
 * This adapter treats the Process-Engine-API as a remote BPMS. It cannot join the local
 * transaction of the application, so a start goes through the outbox, like every other
 * operation which reaches the engine. Phase one is the API's
 * {@code ExecutionMode.PREFLIGHT_CHECK}: it only checks, inside the caller's transaction.
 * Phase two is {@code ExecutionMode.SYNC}: it creates the instance after the commit, sent by
 * the outbox. The id of the workflow aggregate travels as a payload variable. The variable is
 * named after the id property of the aggregate (see
 * {@link AggregatePersistenceAware#getAggregateIdName()}). Where the id is kept in the BPMS
 * is up to the adapter, and here it is a payload variable, as in Camunda 8.
 * <p>
 * The {@link CompletableFuture}s the Process-Engine-API returns are joined. A failed
 * {@code PREFLIGHT_CHECK} has to fail the caller's transaction. A failed {@code SYNC} has to
 * throw, so the outbox tries again. If the future were dropped, the outbox entry would count
 * as done and the workflow would never start, and nobody would notice.
 * <p>
 * The APIs this class calls come in through the constructor, one for each interface the API
 * splits its commands into. The signal API comes through {@link #setSignalApi(SignalApi)}
 * instead. An engine may be built without it, and then signals are the one operation this
 * adapter refuses. The platform modules inject the implementations: the in-memory mock of
 * this repository, or a real one the application brings.
 * <p>
 * Three rules hold here, and several places rely on them. A preflight asks inside the
 * caller's transaction, and the SYNC command runs after the commit (decision 4 in the
 * repository's DECISIONS.md). A failure of phase two is passed on, not swallowed (decision 5
 * in the repository's DECISIONS.md). Only a feature the API lacks completely counts as a
 * permanent failure (decision 6 in the repository's DECISIONS.md).
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
   * The signal API of the engine. It is optional. Without it, signals are not supported,
   * and {@link #signalNotSupported(String, String)} says so.
   */
  private SignalApi signalApi;

  /**
   * Sets the signal API of the engine.
   *
   * @param signalApi The signal API or <code>null</code>
   */
  public void setSignalApi(
      final SignalApi signalApi) {

    this.signalApi = signalApi;

  }

  /**
   * Runs a phase-one check right before the transaction of the workflow aggregate commits
   * (platform-supplied). It always arrives, because the platform refuses to build the
   * collaborators without one. Where the transaction runner of the aggregate offers no
   * hook, the platform runs the check at once.
   */
  private final PreCommitRegistrar preCommitRegistrar;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * A shorter constructor for tests. There is no shared record of deployed processes and
   * there are no collaborators. So the viewer API answers nothing, and no aggregate values
   * travel.
   *
   * @param adapterId The configured adapter id this service serves
   * @param startProcessApi Where a workflow is started
   * @param serviceTaskCompletionApi Where a service task is completed or failed
   * @param userTaskCompletionApi Where a user task is completed or canceled
   * @param correlationApi Where a message is correlated
   */
  public PeaProcessService(
      final String adapterId,
      final StartProcessApi startProcessApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi,
      final CorrelationApi correlationApi) {

    this(adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, new PeaDeployedProcesses());

  }

  /**
   * A shorter constructor for tests which need the viewer API. It reads the record the
   * deployment service filled. No aggregate values travel.
   *
   * @param adapterId The configured adapter id this service serves
   * @param startProcessApi Where a workflow is started
   * @param serviceTaskCompletionApi Where a service task is completed or failed
   * @param userTaskCompletionApi Where a user task is completed or canceled
   * @param correlationApi Where a message is correlated
   * @param deployedProcesses What this application version deployed, the only source of
   *          process definitions this engine has
   */
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
   * The sync model of the core. This adapter treats the Process-Engine-API as a REMOTE BPMS.
   * It can only evaluate what VanillaBP puts into the payload, so the default here is
   * {@link AggregateSyncMode#FULL}. It may be <code>null</code> in tests. Then only the
   * technical aggregate-ID variable travels.
   */
  private final WorkflowAggregateSync aggregateSync;

  /**
   * The default of this adapter. Everything is shared, unless the application excludes it
   * with {@code @NoSyncWithBPMS}.
   */
  public static final AggregateSyncMode SYNC_MODE = AggregateSyncMode.FULL;

  /**
   * The name-clash-avoidance model of the core. It turns process ids, message names and error
   * codes into the names the engine knows. It may be <code>null</code> in tests. Then the
   * identifiers pass through as they are.
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
   * A message name or an error code as the engine knows it.
   */
  private String scopedIdentifier(
      final String workflowModuleId,
      final String identifier) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * The payload sent whenever this adapter talks to the engine for a workflow. It holds the
   * shared attributes of the aggregate. It ALWAYS holds the variable with the id of the
   * aggregate as well, whatever the sync model says. The variable is named after the id
   * property of the aggregate. The Process-Engine-API has no slot for a business key, so this
   * variable is how VanillaBP finds the workflow again. That is why an aggregate annotated
   * {@code @NoSyncWithBPMS} must not lose it. See {@code PeaSharedValuesTest}.
   */
  private Map<String, Object> payloadOf(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final A workflowAggregate) {

    final var payload = new LinkedHashMap<String, Object>();
    if (aggregatePersistence == null) {
      // no persistence, for example in a test which calls the SPI directly: then neither
      // the shared attributes nor the technical id variable can be worked out
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

  /**
   * The constructor a platform integration uses. The collaborators make the shared aggregate
   * values and the aggregate-ID variable travel with every command. A service built without
   * them sends the engine an aggregate id and nothing else.
   *
   * @param adapterId The configured adapter id this service serves
   * @param startProcessApi Where a workflow is started
   * @param serviceTaskCompletionApi Where a service task is completed or failed
   * @param userTaskCompletionApi Where a user task is completed or canceled
   * @param correlationApi Where a message is correlated
   * @param deployedProcesses What this application version deployed, the only source of
   *          process definitions this engine has
   * @param collaborators Everything the platform hands over, the sync model included; may
   *          be <code>null</code> in a test
   */
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
   * What this version of the application deployed. It is the only source of process
   * definitions and BPMN XML, because the Process-Engine-API has no repository API (see
   * {@code GAPS.md}).
   */
  private final PeaDeployedProcesses deployedProcesses;

  /**
   * The process definitions for the viewer API. The Process-Engine-API knows no process
   * definitions, and it does not say which definition a running instance uses. So the
   * adapter answers from what it deployed at startup. Call activities are NOT reported. The
   * Process-Engine-API has no BPMN model type and does not know call activities (GAPS.md).
   * Which sub-process a call activity points to depends on the BPMS dialect.
   */
  @Override
  public List<ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    if (historyContext != null) {
      // only getWorkflowHistory hands out further history contexts, and this adapter
      // cannot fill it, so a context can never be one of ours
      log.warn(
          "Process-Engine-API adapter '{}': the history context '{}' is unknown - this adapter "
              + "reports no element history and therefore no secondary contexts (see GAPS.md)",
          adapterId,
          historyContext);
      return List.of();
    }

    final var deployed = deployedProcesses.deployedVersionOf(workflowModuleId, bpmnProcessId);
    if (deployed == null) {
      sayWhyThereIsNothingToShow(workflowModuleId, bpmnProcessId, "process definitions");
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
    if (deployed == null) {
      sayWhyThereIsNothingToShow(workflowModuleId, bpmnProcessId, "the BPMN XML");
      return null;
    }
    return new ByteArrayInputStream(
        deployed
            .model()
            .resource());

  }

  /**
   * Says why a viewer gets nothing for a workflow whose BPMN process this application
   * DECLARES but deploys no model for. That is the old id of a renamed process.
   * <p>
   * The workflow may run without any problem, and its tasks may be served (see decision 11
   * in the repository's DECISIONS.md). But an earlier version of the application deployed
   * the model behind that id, and the Process-Engine-API has no repository to read it back
   * from (see {@code GAPS.md}). So the answer is empty. Without this line, nobody could
   * explain why.
   * <p>
   * Every other empty answer stays silent. If this adapter never heard of a process id, the
   * question is wrong. That is not a limit of the adapter and is not worth a log line.
   *
   * @param workflowModuleId The workflow module the caller asked about
   * @param bpmnProcessId The plain BPMN process id the caller asked about
   * @param what What the caller wanted, as a noun phrase
   */
  private void sayWhyThereIsNothingToShow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String what) {

    if (!deployedProcesses.isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId)) {
      return;
    }
    log.warn(
        """
            Process-Engine-API adapter '{}': no {} for BPMN process '{}' of workflow module '{}'. \
            The application declares that id without deploying a model under it, which is what \
            renaming a BPMN process leaves behind, and the model the engine still holds for it \
            cannot be read back through this API (see GAPS.md). Their tasks are served as far as \
            the start of this workflow module said they are, only nothing about their model can be \
            shown. Keep deploying the old model under its old id as long as a viewer has to show \
            it.""",
        adapterId,
        what,
        bpmnProcessId,
        workflowModuleId);

  }

  /**
   * The Process-Engine-API has NO history or query API at all (GAPS.md). There is no way to
   * get the start and end time of a workflow or the history of its elements. For that case
   * the SPI answers with a history without elements, reported for the definition the
   * workflow was deployed with.
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
      sayWhyThereIsNothingToShow(workflowModuleId, bpmnProcessId, "a workflow history");
      return null;
    }
    return new WorkflowHistory(
        PeaDeployedProcesses.definitionId(workflowModuleId,
            bpmnProcessId), null, null, null);

  }

  /**
   * What the engine behind the Process-Engine-API does with that type. The startup check
   * uses it to say what happens to a value which travels between the application and this
   * BPMS.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict of this adapter
   */
  @Override
  public io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict whatThisBpmsDoesWith(
      final Class<?> valueType,
      final io.vanillabp.integration.adapter.spi.values.ValueDirection direction) {

    return PeaValueTypes.verdictFor(valueType, direction);

  }

  /**
   * The BPMS this service talks to, named as in the configuration and the startup lines. An
   * application may configure only one adapter id of this type. The API cannot tell two
   * engines behind it apart (GAPS entry 14).
   *
   * @return The adapter type of the Process-Engine-API adapter
   */
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
   * Phase one is a <code>PREFLIGHT_CHECK</code> command wherever the API offers one. It
   * checks without moving the process on. It runs as a pre-commit hook, so the time until
   * phase two stays short. Phase two sends the real command. It is idempotent, because the
   * outbox may send a command more than once.
   * <p>
   * Two operations are in the map although this adapter cannot serve them: sending a signal
   * without a <code>SignalApi</code>, and pushing a changed aggregate at all. They stay,
   * because the handler knows the reason: which API is missing, or which entry of GAPS.md
   * names the gap. A message which only says "this adapter cannot" would not tell the
   * reader how to fix it.
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

    // a subscription reports a task as completed AFTER the local transaction committed.
    // An engine which did not learn the result delivers the task again. What stays the
    // same across such a second delivery is the TASK ID the engine reports, and every
    // invocation context carries it.
    return true;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the probe is a PREFLIGHT_CHECK completion, which is what the mode is for: it only
    // checks, fails early and never moves the process on. The Process-Engine-API has no
    // typed exceptions, so a failed preflight looks the same as an engine which cannot be
    // reached. It counts as UNKNOWN_TO_BPMS (see GAPS.md). Telling BPMS_UNAVAILABLE apart
    // would need typed errors.
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

    // the phase-one check, which does not move the process on: a PREFLIGHT_CHECK completion
    // checks that the task still exists, so the local transaction can stop early. It runs
    // right before the commit, so the time until phase two stays short
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
   * before phase two acts on it. A caller which names no aggregate persistence gets its
   * check run at once. Where the transaction runner offers no hook, the platform runs it
   * at once as well.
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
      throw taskIsGone(
          taskId,
          ("The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction "
              + "%s it! If this task was completed by a concurrent redelivery, retrying the "
              + "business operation will end in the documented no-op.")
              .formatted(taskId, operationDescription),
          e.getCause());
    }

  }

  /**
   * What a phase-one check throws once the engine has refused the task.
   * <p>
   * The SPI names this type for a task no BPMS knows any more. So an application which
   * catches {@link TaskNotFoundException} catches this one as well. The caller does not need
   * to know whether the task was found gone while a BPMS was probed or while this check ran.
   * <p>
   * That exception carries only a message. So the answer of the engine goes to the log
   * before the exception is thrown. The awareness probe logs the same on debug, because the
   * election which reads its answer can still ask the next adapter. A check is the last
   * word, so this line is the only place where the reason is ever written.
   *
   * @param taskId The task the engine refused
   * @param message What the caller reads, including what a retry does
   * @param rejection What the engine answered the check with
   * @return The exception to throw
   */
  private TaskNotFoundException taskIsGone(
      final String taskId,
      final String message,
      final Throwable rejection) {

    // the Process-Engine-API has no typed errors (see GAPS.md). A refusal looks the same as
    // an engine which cannot be reached, so the answer of the engine is all we can pass on
    log.info(
        "PEA[{}]: the phase-one check of task '{}' was refused - the task is gone",
        adapterId,
        taskId,
        rejection);
    return new TaskNotFoundException(message);

  }

  private void completeTask(
      final PhaseTwoRequest<A> request) {

    try {
      serviceTaskCompletionApi
          // the aggregate changed before the task was completed, and the engine only
          // sees what the adapter sends
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
          // the path out of the error boundary may branch on the aggregate, and the
          // caller changed it before canceling the task
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

    // the same probe as for service tasks: a PREFLIGHT_CHECK completion, here sent to the
    // completion API for USER tasks. A failure has no type, so it counts as UNKNOWN (GAPS.md)
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
      throw taskIsGone(
          taskId,
          ("The user task '%s' is gone (completed or canceled meanwhile) - aborting the "
              + "transaction %s it!")
              .formatted(taskId, operationDescription),
          e.getCause());
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
              request.taskId(), scopedIdentifier(request.workflowModuleId(),
                  request.bpmnErrorCode()), "canceled via ProcessService#cancelUserTask"))
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

    // the Process-Engine-API has NO way to ask whether a workflow exists. There is no
    // query API, and CorrelateMessageCmd is FINAL, so it cannot carry the PREFLIGHT_CHECK
    // execution mode (GAPS.md entry 11). So the adapter answers OPTIMISTICALLY. That is
    // fine with one BPMS. It is not safe while workflows move between BPMS, and the log
    // warns about it once
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
   * Always <code>false</code>. The Process-Engine-API has no query API. Its command classes
   * carry no execution mode which could ask without acting (GAPS.md entry 11). So
   * {@link #awarenessOfWorkflow} answers optimistically. That is right while this is the
   * only configured BPMS, and a guess as soon as there are more. Because this method says
   * so, the core refuses the second case at startup. Otherwise it would route operations by
   * the order of the list.
   *
   * @return <code>false</code>
   */
  @Override
  public boolean canLocateWorkflows() {

    return false;

  }

  /**
   * The probe asked before a START is sent again. Its contract is STRICTER than the one of
   * {@link #awarenessOfWorkflow}: the answer must NEVER be optimistic. The
   * Process-Engine-API cannot ask whether a workflow exists at all (GAPS.md entry 11). So
   * the honest answer is {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. The recovered start goes
   * on, and the phase-two start may then happen more than once. A duplicate is the risk we
   * accept. The optimistic ACTIVE of the election would SKIP the start instead and LOSE the
   * workflow.
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
   * The Process-Engine-API has no typed exceptions. So this adapter cannot tell "the task is
   * gone" from "the engine cannot be reached". The first is a risk we accept, because the
   * outbox may send an operation more than once. The second must never lose the operation.
   * So every failure reaches the outbox. The outbox tries again and in the end blocks the
   * entry. An operation on something the engine already finished then costs some retries
   * and a blocked entry. In return, no operation is ever dropped without a trace.
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
   * The Process-Engine-API has no typed exceptions. Whatever an engine throws arrives
   * wrapped in an {@link ExecutionException}, and "the engine cannot be reached" looks just
   * like "the engine refused". So only one kind of failure can be told apart, and this
   * adapter throws it itself. That is where the API cannot do what VanillaBP asks: a signal
   * without a {@code SignalApi}, or pushing a changed aggregate. Every attempt ends the same
   * way, so the outbox entry is blocked at once. It does not use up all its attempts first.
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

    // there is no PREFLIGHT_CHECK for phase one: CorrelateMessageCmd is FINAL and cannot
    // carry another execution mode than the default (GAPS.md entry 11). So phase one
    // checks nothing against the engine here

  }

  /**
   * Pushing a changed workflow aggregate is not supported (GAPS.md entry 18). The
   * Process-Engine-API can update the payload of a TASK, but never the payload of a running
   * process instance. Phase one refuses already. So the application learns it where it made
   * the call, and not somewhere after the commit.
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
   * This adapter treats the Process-Engine-API as a REMOTE BPMS. Nothing may happen before
   * the transaction of the caller commits, so the signal is sent in phase two.
   * <p>
   * But phase one CAN say whether this adapter is able to send signals at all. Without a
   * {@code SignalApi}, phase two can only fail. It would fail after the commit, again and
   * again, until the outbox entry is blocked. So the application learns it where it made
   * the call.
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

    // no payload travels with a signal. Unlike a message, it is not sent to one workflow,
    // so there is no aggregate whose state could be meant
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
      // returning here would mark the outbox entry as done, although no signal was sent.
      // A signal nobody receives leaves a workflow waiting forever
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

    // no message CONTENT travels. What travels is the aggregate state shared with the
    // engine (see decision 1 in the repository's DECISIONS.md). CorrelateMessageCmd is
    // FINAL, so the command carries the DEFAULT execution mode. The SYNC mode we want
    // cannot be expressed (GAPS.md entry 11).
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

    // as for any start, there is nothing to check against the engine here. A
    // PREFLIGHT_CHECK cannot be expressed either, because StartProcessByMessageCmd is FINAL
    // (GAPS.md entry 11)

  }

  private void startWorkflowByMessage(
      final PhaseTwoRequest<A> request) {

    // no message CONTENT travels - what travels is the aggregate state shared with
    // the engine plus the technical aggregate-ID variable;
    // StartProcessByMessageCmd is FINAL - the DEFAULT execution mode travels
    // (GAPS.md entry 11); a second scheduled start of the same aggregate is dropped by the
    // outbox key 'START_WORKFLOW|module|process|aggregateId', which a start by message
    // shares with a plain start
    try {
      final var started = startProcessApi
          .startProcess(new StartProcessByMessageCmd(
              scopedIdentifier(request.workflowModuleId(), request.messageName()), payloadOf(
                  request.aggregatePersistence(), request.workflowAggregateId(), null), Map.of()))
          .get();
      reportTheStartedInstance(request, started);
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
      // returning here would mark the outbox entry as done, although no workflow was
      // started. The database of the application would then hold an aggregate no engine
      // knows
      throw new IllegalStateException(
          "Phase two of starting a workflow by message '%s' was interrupted".formatted(request.messageName()), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          "Phase two of starting a workflow by message '%s' failed".formatted(request.messageName()), e.getCause());
    }

  }

  private void preflightStart(
      final PhaseOneRequest<A> request) {

    // phase one runs inside the local transaction of the caller, so it only checks
    // (PREFLIGHT_CHECK). The Process-Engine-API is remote, so the instance must not be
    // created here. Phase two creates it after the commit. Otherwise a transaction which is
    // rolled back would leave a workflow behind that nobody owns. A failed check throws and
    // so rolls the transaction of the caller back. That is better than committing an outbox
    // entry which can never be sent
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
    // DECISIONS.md). The core asks a probe before it sends a start again. This adapter
    // cannot ask the engine (GAPS.md, entry 11), so it answers that probe with
    // UNKNOWN_TO_BPMS, and the recovered start goes on.
    final var started = await(
        startProcessApi.startProcess(
            new PeaStartProcessCommand(
                scopedProcessId(request.workflowModuleId(), request.bpmnProcessId()), payloadOf(
                    request.aggregatePersistence(), request.workflowAggregateId(), null), ExecutionMode.SYNC)),
        "Starting the workflow (phase two)",
        request.bpmnProcessId(),
        request.workflowModuleId());
    reportTheStartedInstance(request, started);

  }

  /**
   * Tells VanillaBP which instance of the engine a start created. So VanillaBP can later say
   * which workflow an aggregate belongs to without asking the engine.
   * <p>
   * The id is the <code>instanceId</code> of the answer to the start command. The API promises
   * that a delivered task names the same value under <code>processInstanceId</code>. This
   * adapter reports that value as the workflow id of a delivery. If an engine answers a start
   * without an instance id, there is nothing to report. A Camunda 8 engine started by a message
   * may do that, because it learns the id only later. VanillaBP then finds the workflow by
   * asking the adapters one by one.
   * <p>
   * The version goes with the id. It is read from the meta map of the answer, under the key a
   * delivered task uses for its version tag ({@link PeaTaskMeta#PROCESS_VERSION_TAG}). So the
   * start and the deliveries of one workflow name its version the same way. The API names no
   * such key for the answer to a start, and its reference adapter for an embedded Camunda 7
   * fills none there. Then the version is <code>null</code>. That tells VanillaBP that a
   * delivery may still bring it (see <code>GAPS.md</code>, entry 31).
   *
   * @param request The phase-two request of the start
   * @param started What the engine answered, <code>null</code> where it answered nothing
   */
  private static void reportTheStartedInstance(
      final PhaseTwoRequest<?> request,
      final ProcessInformation started) {

    if (started == null) {
      return;
    }
    final var meta = started.getMeta();
    final var versionTag = meta == null
        ? null
        : meta.get(PeaTaskMeta.PROCESS_VERSION_TAG);
    // a blank tag counts as none, as it does when a delivery is read
    request.reportStartedWorkflow(
        started.getInstanceId(), (versionTag == null) || versionTag.isBlank()
            ? null
            : versionTag);

  }

  /**
   * Waits for the future of a Process-Engine-API command, as
   * {@code PeaDeploymentService.deployResources} does. The result has to reach the caller.
   * In phase one, a failure rolls the local transaction back. In phase two, it makes the
   * outbox try again.
   *
   * @param future The future returned by the Process-Engine-API
   * @param operation Description used in error messages
   * @param bpmnProcessId The BPMN process id (used in error messages)
   * @param workflowModuleId The workflow module id (used in error messages)
   * @return What the engine answered
   */
  private static ProcessInformation await(
      final CompletableFuture<ProcessInformation> future,
      final String operation,
      final String bpmnProcessId,
      final String workflowModuleId) {

    try {
      return future.get();
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
