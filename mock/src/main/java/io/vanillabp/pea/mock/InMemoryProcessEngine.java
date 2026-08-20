package io.vanillabp.pea.mock;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.ExecutionModeAware;
import dev.bpmcrafters.processengineapi.MetaInfo;
import dev.bpmcrafters.processengineapi.MetaInfoAware;
import dev.bpmcrafters.processengineapi.correlation.CorrelateMessageCmd;
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation;
import dev.bpmcrafters.processengineapi.deploy.NamedResource;
import dev.bpmcrafters.processengineapi.process.ProcessInformation;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.process.StartProcessCommand;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskSubscription;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;

/**
 * Hand-written, in-memory fake of the bpm-crafters Process-Engine-API. It implements the
 * real Process-Engine-API interfaces so the VanillaBP Process-Engine-API adapter can run
 * (in tests and early applications) without a real BPMS and without Mockito.
 * <p>
 * <b>Purpose - mock-first development:</b> this fake grows with every feature story of the
 * adapter. Its job is to make it obvious, feature by feature, where either VanillaBP or
 * the Process-Engine-API needs an extension. Findings are collected in the adapter repo's
 * {@code GAPS.md}. It is intentionally <i>not</i> a Mockito mock: later stories need it to
 * carry real state (deployed definitions, started instances, subscribed tasks), which a
 * generated mock cannot do.
 * <p>
 * <b>Current (skeleton) behavior:</b> every API method records its invocation - the command
 * object and, where the command carries one, its {@link ExecutionMode} - into the public,
 * inspectable {@link #invocations} list and returns a completed future / empty result of
 * the declared type. No stateful behavior yet. Use {@link #reset()} to clear recordings
 * between tests.
 * <p>
 * A single instance implements all Process-Engine-API interfaces the adapter needs, so the
 * platform modules can inject the same bean wherever any of these interfaces is required.
 */
public class InMemoryProcessEngine implements DeploymentApi, StartProcessApi, CorrelationApi, dev.bpmcrafters.processengineapi.correlation.SignalApi, TaskSubscriptionApi, ServiceTaskCompletionApi, UserTaskCompletionApi {

  /**
   * A single recorded API invocation.
   *
   * @param api The Process-Engine-API interface the method belongs to
   * @param method The invoked method name
   * @param command The command object passed to the method
   * @param executionMode The command's {@link ExecutionMode}, or {@code null} if the
   *          command does not carry one (e.g. task subscription commands)
   */
  public record Invocation(String api, String method, Object command, ExecutionMode executionMode) {

  }

  /**
   * A resource bundle deployed via {@link #deploy(DeployBundleCommand)}.
   *
   * @param deploymentKey The generated key of this deployment
   * @param resources The resources deployed (filename + bytes, opaque to the engine)
   * @param tenantId The tenant the bundle was deployed for, or {@code null} for the
   *          default tenant
   */
  public record Deployment(String deploymentKey, List<NamedResource> resources, String tenantId) {

  }

  /**
   * An in-memory process instance created by a {@link ExecutionMode#SYNC} start (phase
   * two of VanillaBP's two-phase start). The workflow-aggregate id is one of the
   * {@code variables} - which one is the adapter's decision (the variable is named
   * after the aggregate's ID property), so the engine fake does not single it out.
   *
   * @param instanceId The generated instance id
   * @param variables The process variables the instance was started with
   */
  public record StartedInstance(String instanceId, Map<String, Object> variables) {

  }

  /**
   * All invocations recorded so far, in call order. Public and inspectable on purpose:
   * tests assert against it directly.
   */
  public final List<Invocation> invocations = new CopyOnWriteArrayList<>();

  private final List<Deployment> deployments = new CopyOnWriteArrayList<>();

  /**
   * All instances created by {@link ExecutionMode#SYNC} starts, in creation order.
   * Deliberately a LIST (not a map keyed by aggregate id): duplicate starts for the
   * same aggregate have to be observable by tests - a map would silently overwrite
   * and hide exactly the bug the mock is meant to surface.
   */
  private final List<StartedInstance> startedInstances = new CopyOnWriteArrayList<>();

  /**
   * BPMN process ids whose {@link ExecutionMode#PREFLIGHT_CHECK} is injected to
   * fail - used by tests to assert that a failed phase one rolls the caller's
   * transaction back.
   */
  private final Set<String> failPreflightForProcessIds = ConcurrentHashMap.newKeySet();

  /**
   * BPMN process ids whose {@link ExecutionMode#SYNC} start is injected to fail
   * ONCE per entry - used by tests to assert that a failed phase two makes the
   * outbox retry the dispatch.
   */
  private final Set<String> failNextSyncForProcessIds = ConcurrentHashMap.newKeySet();

  private final AtomicLong deploymentCounter = new AtomicLong();

  private final AtomicLong instanceCounter = new AtomicLong();

  /**
   * @return All invocations recorded so far, in call order.
   */
  public List<Invocation> getInvocations() {

    return invocations;

  }

  /**
   * @return All resource bundles deployed so far, in deployment order.
   */
  public List<Deployment> getDeployments() {

    return deployments;

  }

  /**
   * @return All process instances created by a {@link ExecutionMode#SYNC} start, in
   *         creation order (duplicates for the same aggregate id are visible)
   */
  public List<StartedInstance> getStartedInstances() {

    return startedInstances;

  }

  /**
   * Injects a failing {@link ExecutionMode#PREFLIGHT_CHECK} for the given BPMN
   * process id (until {@link #reset()}).
   *
   * @param bpmnProcessId The BPMN process id whose preflight check should fail
   */
  public void failPreflightFor(
      final String bpmnProcessId) {

    failPreflightForProcessIds.add(bpmnProcessId);

  }

  /**
   * Injects ONE failing {@link ExecutionMode#SYNC} start for the given BPMN process
   * id - the next start fails, subsequent starts succeed (retry testing).
   *
   * @param bpmnProcessId The BPMN process id whose next SYNC start should fail
   */
  public void failNextSyncFor(
      final String bpmnProcessId) {

    failNextSyncForProcessIds.add(bpmnProcessId);

  }

  /**
   * Clears all recorded invocations and all fake state (deployments, started instances).
   */
  /**
   * Clears only the task-processing recordings (invocations, completed/errored/failed
   * tasks and one-shot failure triggers) while KEEPING deployments, started instances
   * and active subscriptions - for tests exercising several deliveries against the
   * subscriptions opened at startup.
   */
  public void clearTaskRecordings() {

    invocations.clear();
    completedTasks.clear();
    completionPayloads.clear();
    erroredTasks.clear();
    failedTasks.clear();
    failNextCompletionForTaskIds.clear();
    openTaskIds.clear();
    correlatedMessages.clear();

  }

  public void reset() {

    invocations.clear();
    deployments.clear();
    startedInstances.clear();
    subscriptions.clear();
    completedTasks.clear();
    completionPayloads.clear();
    erroredTasks.clear();
    failedTasks.clear();
    failNextCompletionForTaskIds.clear();
    openTaskIds.clear();
    correlatedMessages.clear();
    failPreflightForProcessIds.clear();
    failNextSyncForProcessIds.clear();

  }

  /**
   * Extracts the BPMN process id from the command. The Process-Engine-API's
   * {@code StartProcessCommand} interface has no accessor for it, and depending on
   * the adapter core only for its command type would couple this fake to the
   * adapter - so the id is read reflectively from a {@code getBpmnProcessId()}
   * method if present.
   *
   * @param cmd The start command
   * @return The BPMN process id or null if not determinable
   */
  private static String bpmnProcessIdOf(
      final StartProcessCommand cmd) {

    try {
      return (String) cmd.getClass().getMethod("getBpmnProcessId").invoke(cmd);
    } catch (final Exception e) {
      return null;
    }

  }

  private void record(
      final String api,
      final String method,
      final Object command) {

    final var executionMode = command instanceof ExecutionModeAware executionModeAware
        ? executionModeAware.executionMode()
        : null;
    invocations.add(new Invocation(api, method, command, executionMode));

  }

  // --- DeploymentApi ---

  @Override
  public CompletableFuture<DeploymentInformation> deploy(
      final DeployBundleCommand cmd) {

    record("DeploymentApi", "deploy", cmd);
    final var deploymentInformation = new DeploymentInformation(
        "mock-deployment-"
            + deploymentCounter.incrementAndGet(), Instant.now(), cmd.getTenantId());
    deployments.add(
        new Deployment(deploymentInformation.getDeploymentKey(), List.copyOf(cmd.getResources()), cmd.getTenantId()));
    return CompletableFuture.completedFuture(deploymentInformation);

  }

  // --- StartProcessApi ---

  @Override
  public CompletableFuture<ProcessInformation> startProcess(
      final StartProcessCommand cmd) {

    final var bpmnProcessId = bpmnProcessIdOf(cmd);
    // Only ExecutionMode.SYNC creates an instance: this is phase two of VanillaBP's
    // two-phase start. ExecutionMode.PREFLIGHT_CHECK (phase one) validates only and
    // must not create one; any other mode is recorded but creates nothing either.
    // NOTE: the mock cannot validate a PREFLIGHT_CHECK against the deployed
    // processes - deployed resources are opaque (no BPMN model type, see GAPS.md);
    // tests inject failures via failPreflightFor instead.
    if (cmd.executionMode() == ExecutionMode.PREFLIGHT_CHECK) {
      record("StartProcessApi", "startProcess", cmd);
      if ((bpmnProcessId != null) && failPreflightForProcessIds.contains(bpmnProcessId)) {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Preflight check failed for BPMN process '%s' (injected by the mock)".formatted(bpmnProcessId)));
      }
      return CompletableFuture.completedFuture(new ProcessInformation("mock-no-instance", Map.of()));
    }
    if ((cmd
        .executionMode() != ExecutionMode.SYNC) && !(cmd instanceof dev.bpmcrafters.processengineapi.process.StartProcessByMessageCmd)) {
      // StartProcessByMessageCmd is FINAL and cannot carry a non-default
      // execution mode (GAPS.md entry 11) - its DEFAULT-mode command is the
      // phase-two start and creates an instance like a SYNC one
      record("StartProcessApi", "startProcess", cmd);
      return CompletableFuture.completedFuture(new ProcessInformation("mock-no-instance", Map.of()));
    }
    if ((bpmnProcessId != null) && failNextSyncForProcessIds.remove(bpmnProcessId)) {
      record("StartProcessApi", "startProcess", cmd);
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Starting BPMN process '%s' failed (injected by the mock, once)".formatted(bpmnProcessId)));
    }
    final Map<String, ?> payload = cmd.get();
    final var variables = payload == null
        ? Map.<String, Object>of()
        : new LinkedHashMap<String, Object>(payload);
    final var instanceId = "mock-instance-"
        + instanceCounter.incrementAndGet();
    startedInstances.add(new StartedInstance(instanceId, variables));
    // record LAST: tests await the SYNC invocation and then assert the started
    // instance - recording first would open a race window for the asserting thread
    record("StartProcessApi", "startProcess", cmd);
    return CompletableFuture.completedFuture(new ProcessInformation(instanceId, Map.of()));

  }

  // --- CorrelationApi ---

  @Override
  public CompletableFuture<Empty> correlateMessage(
      final CorrelateMessageCmd cmd) {

    record("CorrelationApi", "correlateMessage", cmd);
    correlatedMessages.add(new CorrelatedMessage(
        cmd.getMessageName(), cmd.getCorrelation() != null
            ? cmd.getCorrelation().get().getCorrelationKey()
            : null));
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  // --- SignalApi ---

  @Override
  public CompletableFuture<Empty> sendSignal(
      final dev.bpmcrafters.processengineapi.correlation.SendSignalCmd cmd) {

    record("SignalApi", "sendSignal", cmd);
    broadcastSignals.add(cmd.getSignalName());
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  /**
   * The signals broadcast so far - inspectable by tests.
   *
   * @return The signal names, in the order they were broadcast
   */
  public java.util.List<String> getBroadcastSignals() {

    return java.util.List.copyOf(broadcastSignals);

  }

  private final java.util.List<String> broadcastSignals = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * A correlated message - inspectable by tests.
   *
   * @param messageName The BPMN message name
   * @param correlationKey The correlation key (aggregate ID or correlation id)
   */
  public record CorrelatedMessage(String messageName, String correlationKey) {
  }

  private final List<CorrelatedMessage> correlatedMessages = new CopyOnWriteArrayList<>();

  public List<CorrelatedMessage> getCorrelatedMessages() {

    return correlatedMessages;

  }

  // --- TaskSubscriptionApi ---

  /**
   * One active task subscription: the subscribed task definition, the payload
   * variables it asked for and the handler task deliveries are dispatched to.
   *
   * @param taskDescriptionKey The subscribed task definition
   * @param payloadDescription The payload variables the subscription asked for - an
   *          EMPTY set is the API's way of asking for everything
   * @param handler The subscriber's task handler
   */
  public record ActiveSubscription(
                                   String taskDescriptionKey,
                                   java.util.Set<String> payloadDescription,
                                   dev.bpmcrafters.processengineapi.task.TaskHandler handler) implements TaskSubscription {

    /**
     * Narrows a delivered payload the way an engine does: a subscription naming
     * variables gets those and nothing else, one naming none gets everything.
     *
     * @param payload What the process instance holds
     * @return What this subscription's handler sees
     */
    public Map<String, Object> narrow(
        final Map<String, Object> payload) {

      if (payloadDescription.isEmpty()) {
        return payload;
      }
      final var narrowed = new java.util.LinkedHashMap<String, Object>();
      payload.forEach((
          name,
          value) -> {
        if (payloadDescription.contains(name)) {
          narrowed.put(name, value);
        }
      });
      return narrowed;

    }

  }

  /**
   * The active task subscriptions - inspectable by tests.
   */
  private final List<ActiveSubscription> subscriptions = new CopyOnWriteArrayList<>();

  public List<ActiveSubscription> getSubscriptions() {

    return subscriptions;

  }

  /**
   * Delivers a task to the subscription matching the given task definition - the
   * mock cannot derive tasks from the deployed (opaque) resources, so tests drive
   * deliveries explicitly (incl. DUPLICATE deliveries for at-least-once tests).
   * The BPMN process ID travels in the {@code TaskInformation} meta (adapter
   * convention key <code>bpmnProcessId</code>).
   *
   * @param taskId The delivered task's ID
   * @param taskDefinition The task definition (matched against subscriptions)
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param payload The task's payload variables
   */
  public void deliverTask(
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final Map<String, Object> payload) {

    openTaskIds.add(taskId);
    final var subscription = subscriptions
        .stream()
        .filter(candidate -> candidate.taskDescriptionKey().equals(taskDefinition))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No subscription for task definition '%s' - subscribed: %s"
                .formatted(taskDefinition, subscriptions
                    .stream()
                    .map(ActiveSubscription::taskDescriptionKey)
                    .toList())));
    subscription
        .handler()
        .accept(
            new dev.bpmcrafters.processengineapi.task.TaskInformation(
                taskId, Map.of("bpmnProcessId", bpmnProcessId)),
            // an engine hands the subscriber what the subscription asked for, and the
            // adapter's derivation is only worth anything if the mock does the same
            subscription.narrow(payload));

  }

  @Override
  public CompletableFuture<TaskSubscription> subscribeForTask(
      final SubscribeForTaskCmd cmd) {

    record("TaskSubscriptionApi", "subscribeForTask", cmd);
    final var subscription = new ActiveSubscription(
        cmd.getTaskDescriptionKey(), cmd.getPayloadDescription(), cmd.getAction());
    subscriptions.add(subscription);
    return CompletableFuture.completedFuture(subscription);

  }

  @Override
  public CompletableFuture<Empty> unsubscribe(
      final UnsubscribeFromTaskCmd cmd) {

    record("TaskSubscriptionApi", "unsubscribe", cmd);
    if (cmd.getSubscription() instanceof ActiveSubscription active) {
      subscriptions.remove(active);
    }
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  // --- ServiceTaskCompletionApi / UserTaskCompletionApi (shared method signatures) ---

  /**
   * A completed task (via {@code completeTask}) - inspectable by tests.
   */
  public record CompletedTask(String taskId) {
  }

  /**
   * A task completed by BPMN error - inspectable by tests.
   */
  public record ErroredTask(String taskId, String errorCode, String errorMessage) {
  }

  /**
   * A failed task (via {@code failTask}) - inspectable by tests.
   */
  public record FailedTask(String taskId, String reason) {
  }

  private final List<CompletedTask> completedTasks = new CopyOnWriteArrayList<>();

  /**
   * The payload a completion command carried, per task ID (story 28b): the values
   * the workflow aggregate shares with the engine plus the aggregate-ID variable.
   * Kept beside {@link #completedTasks} so tests asserting the completion itself
   * stay unaffected.
   */
  private final java.util.Map<String, Map<String, Object>> completionPayloads = new ConcurrentHashMap<>();

  /**
   * @param taskId The completed (or by-error completed) task
   * @return The payload the completion carried - empty if none or unknown task
   */
  public Map<String, Object> getCompletionPayload(
      final String taskId) {

    return completionPayloads.getOrDefault(taskId, Map.of());

  }

  private final List<ErroredTask> erroredTasks = new CopyOnWriteArrayList<>();

  private final List<FailedTask> failedTasks = new CopyOnWriteArrayList<>();

  /**
   * Task IDs whose NEXT completeTask fails once (removed on use) - lets tests
   * exercise the at-least-once residual (completion fails after the local commit,
   * the engine redelivers, the handler converges).
   */
  private final Set<String> failNextCompletionForTaskIds = ConcurrentHashMap.newKeySet();

  /**
   * Task IDs delivered via {@link #deliverTask} and not yet completed/canceled -
   * the basis for honest {@code PREFLIGHT_CHECK} validation of task completions
   * (unlike deployments, the mock KNOWS which tasks are open).
   */
  private final Set<String> openTaskIds = ConcurrentHashMap.newKeySet();

  public Set<String> getOpenTaskIds() {

    return openTaskIds;

  }

  public List<CompletedTask> getCompletedTasks() {

    return completedTasks;

  }

  public List<ErroredTask> getErroredTasks() {

    return erroredTasks;

  }

  public List<FailedTask> getFailedTasks() {

    return failedTasks;

  }

  public void failNextCompletionFor(
      final String taskId) {

    failNextCompletionForTaskIds.add(taskId);

  }

  @Override
  public CompletableFuture<Empty> completeTask(
      final CompleteTaskCmd cmd) {

    record("TaskCompletionApi", "completeTask", cmd);
    if (cmd.executionMode() == ExecutionMode.PREFLIGHT_CHECK) {
      // validate only, never advance: the task has to be OPEN
      if (!openTaskIds.contains(cmd.getTaskId())) {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Task '%s' is not open (preflight failed)".formatted(cmd.getTaskId())));
      }
      return CompletableFuture.completedFuture(Empty.INSTANCE);
    }
    if (failNextCompletionForTaskIds.remove(cmd.getTaskId())) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Completing task '%s' failed (injected by the mock)".formatted(cmd.getTaskId())));
    }
    if (!openTaskIds.remove(cmd.getTaskId())) {
      // the SYNC completion of a gone task fails - the adapter tolerates this as
      // the at-least-once residual
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Task '%s' is not open (already completed or canceled)".formatted(cmd.getTaskId())));
    }
    completedTasks.add(new CompletedTask(cmd.getTaskId()));
    completionPayloads.put(cmd.getTaskId(), Map.copyOf(cmd.get()));
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  @Override
  public CompletableFuture<Empty> completeTaskByError(
      final CompleteTaskByErrorCmd cmd) {

    record("TaskCompletionApi", "completeTaskByError", cmd);
    if (cmd.executionMode() == ExecutionMode.PREFLIGHT_CHECK) {
      if (!openTaskIds.contains(cmd.getTaskId())) {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Task '%s' is not open (preflight failed)".formatted(cmd.getTaskId())));
      }
      return CompletableFuture.completedFuture(Empty.INSTANCE);
    }
    if (!openTaskIds.remove(cmd.getTaskId())) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Task '%s' is not open (already completed or canceled)".formatted(cmd.getTaskId())));
    }
    erroredTasks.add(new ErroredTask(cmd.getTaskId(), cmd.getErrorCode(), cmd.getErrorMessage()));
    completionPayloads.put(cmd.getTaskId(), Map.copyOf(cmd.get()));
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  @Override
  public CompletableFuture<Empty> failTask(
      final FailTaskCmd cmd) {

    record("ServiceTaskCompletionApi", "failTask", cmd);
    failedTasks.add(new FailedTask(cmd.getTaskId(), cmd.getReason()));
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  // --- MetaInfoAware / RestrictionAware ---

  @Override
  public MetaInfo meta(
      final MetaInfoAware instance) {

    return new MetaInfo() {
    };

  }

  @Override
  public Set<String> getSupportedRestrictions() {

    return Set.of();

  }

}
