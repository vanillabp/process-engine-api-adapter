package io.vanillabp.pea.mock;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.Empty;
import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.ExecutionModeAware;
import dev.bpmcrafters.processengineapi.MetaInfo;
import dev.bpmcrafters.processengineapi.MetaInfoAware;
import dev.bpmcrafters.processengineapi.correlation.CorrelateMessageCmd;
import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SendSignalCmd;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentInformation;
import dev.bpmcrafters.processengineapi.deploy.NamedResource;
import dev.bpmcrafters.processengineapi.process.ProcessInformation;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.process.StartProcessByMessageCmd;
import dev.bpmcrafters.processengineapi.process.StartProcessCommand;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.FailTaskCmd;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskHandler;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskSubscription;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler;
import dev.bpmcrafters.processengineapi.task.TaskType;
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;

/**
 * A fake of the bpm-crafters Process-Engine-API, written by hand, which keeps everything in
 * memory. It implements the real interfaces of the API. So the VanillaBP adapter runs without
 * a real BPMS and without Mockito, in tests and in early applications.
 * <p>
 * <b>Why a fake comes first:</b> this fake grows with every feature story of the adapter.
 * Feature by feature, it shows where VanillaBP or the Process-Engine-API has to be extended.
 * The findings go into {@code GAPS.md} of the adapter repository. It is not a Mockito mock on
 * purpose. Later stories need it to keep real state: deployed definitions, started instances,
 * subscribed tasks. A generated mock cannot do that.
 * <p>
 * <b>What it records and what it keeps:</b> every method of the API records its call in the
 * public list {@link #invocations}. A record holds the command and, if the command has one,
 * its {@link ExecutionMode}. Next to that, the fake keeps the state a test has to assert: the
 * deployed bundles, the instances a {@link ExecutionMode#SYNC} start created, the open
 * subscriptions, which task went to which subscription, and what a completion carried.
 * Nothing here polls, so a test delivers a task by calling {@code deliverTask}.
 * {@link #reset()} clears all of it between tests.
 * <p>
 * <b>A task names the instance it belongs to.</b> The API promises this: the
 * <code>instanceId</code> in the answer to a start is the <code>processInstanceId</code> in the
 * meta of every task of that instance. This engine keeps the promise for the instances it
 * started. {@link #deliverTask(String, String, String, Map, Map)} says how it finds the
 * instance of a delivered task.
 * <p>
 * <b>It refuses what the engines behind the API refuse, and no more.</b> That is a preflight
 * against a task which is not open, and a completion of a task which is gone. A fake which is
 * stricter than the real engine makes a valid call fail in a test. This is how a null value
 * in a shared aggregate attribute was found once.
 * <p>
 * One instance implements every interface of the API the adapter needs. So the platform
 * modules can inject the same bean wherever one of these interfaces is asked for.
 */
public class InMemoryProcessEngine implements DeploymentApi, StartProcessApi, CorrelationApi, SignalApi, TaskSubscriptionApi, ServiceTaskCompletionApi, UserTaskCompletionApi {

  /**
   * Starts with nothing deployed, nothing running and nothing recorded. One instance serves
   * every interface of the API the adapter needs. So a test wires the same object everywhere
   * and sees one engine.
   */
  public InMemoryProcessEngine() {

  }

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
   * A process instance in memory, created by a {@link ExecutionMode#SYNC} start. That is
   * phase two of the two-phase start of VanillaBP. The id of the workflow aggregate is one of
   * the {@code variables}. The adapter decides which one: the variable is named after the ID
   * property of the aggregate. So the fake does not single it out.
   *
   * @param instanceId The generated instance id
   * @param variables The process variables the instance was started with
   * @param bpmnProcessId The BPMN process the instance was started for, or <code>null</code>
   *          where the start command did not say
   */
  public record StartedInstance(String instanceId, Map<String, Object> variables, String bpmnProcessId) {

    /**
     * Whether a task with this payload is a task of this instance. Nothing here runs a model.
     * So the mock goes by what an instance carries. The task has to name at least one of the
     * variables the instance was started with. Every variable it names needs the same value.
     * The variable with the aggregate ID is such a variable, and it never changes. If a shared
     * value changed since the start, the task matches no instance. Then it names no instance,
     * as it did before the mock kept the promise.
     *
     * @param bpmnProcessIdOfTask The BPMN process the task was delivered as
     * @param payload What the task carries
     * @return <code>true</code> if the task belongs to this instance
     */
    boolean holds(
        final String bpmnProcessIdOfTask,
        final Map<String, Object> payload) {

      if ((bpmnProcessIdOfTask == null) || !bpmnProcessIdOfTask.equals(bpmnProcessId)) {
        return false;
      }
      final var common = payload
          .keySet()
          .stream()
          .filter(variables::containsKey)
          .toList();
      return !common.isEmpty() && common.stream()
          .allMatch(name -> Objects.equals(payload.get(name), variables.get(name)));

    }

  }

  /**
   * All calls recorded so far, in the order they came. It is public on purpose, because
   * tests assert against it directly.
   */
  public final List<Invocation> invocations = new CopyOnWriteArrayList<>();

  private final List<Deployment> deployments = new CopyOnWriteArrayList<>();

  /**
   * All instances created by {@link ExecutionMode#SYNC} starts, in the order they were made.
   * This is a list on purpose, not a map by aggregate id. A test has to see two starts for
   * the same aggregate. A map would overwrite the first one without a word, and hide exactly
   * the bug the mock is there to show.
   */
  private final List<StartedInstance> startedInstances = new CopyOnWriteArrayList<>();

  /**
   * The BPMN process ids whose {@link ExecutionMode#PREFLIGHT_CHECK} is made to fail. Tests
   * use it to show that a failed phase one rolls back the transaction of the caller.
   */
  private final Set<String> failPreflightForProcessIds = ConcurrentHashMap.newKeySet();

  /**
   * The BPMN process ids whose {@link ExecutionMode#SYNC} start is made to fail, ONCE per
   * entry. Tests use it to show that the outbox tries the dispatch again after a failed
   * phase two.
   */
  private final Set<String> failNextSyncForProcessIds = ConcurrentHashMap.newKeySet();

  /**
   * What the answer about a created instance carries in its meta map. It stays empty unless
   * a test sets something. That matches the reference adapter of the API, which fills in no
   * version tag.
   */
  private final Map<String, String> metaOfStartAnswers = new ConcurrentHashMap<>();

  private final AtomicLong deploymentCounter = new AtomicLong();

  private final AtomicLong instanceCounter = new AtomicLong();

  /**
   * Every call the adapter made. A test checks a command and its execution mode against it.
   *
   * @return All invocations recorded so far, in call order.
   */
  public List<Invocation> getInvocations() {

    return invocations;

  }

  /**
   * What the adapter handed over as a bundle. The API deploys resources without looking into
   * them, so these are bytes and a name. A test reads them to check what the scoping
   * rewrote.
   *
   * @return All resource bundles deployed so far, in deployment order.
   */
  public List<Deployment> getDeployments() {

    return deployments;

  }

  /**
   * The workflows this engine really started. A preflight creates nothing. So the difference
   * between this list and the recorded calls is what the two phases did. Duplicates are
   * kept. An outbox which delivers at least once may dispatch a start twice, and a test has
   * to see that.
   *
   * @return All process instances created by a {@link ExecutionMode#SYNC} start, in
   *         creation order (duplicates for the same aggregate id are visible)
   */
  public List<StartedInstance> getStartedInstances() {

    return startedInstances;

  }

  /**
   * Makes the {@link ExecutionMode#PREFLIGHT_CHECK} for the given BPMN process id fail,
   * until {@link #reset()}.
   *
   * @param bpmnProcessId The BPMN process id whose preflight check should fail
   */
  public void failPreflightFor(
      final String bpmnProcessId) {

    failPreflightForProcessIds.add(bpmnProcessId);

  }

  /**
   * Makes ONE {@link ExecutionMode#SYNC} start for the given BPMN process id fail. The next
   * start fails, and the starts after it work. Tests use it for retries.
   *
   * @param bpmnProcessId The BPMN process id whose next SYNC start should fail
   */
  public void failNextSyncFor(
      final String bpmnProcessId) {

    failNextSyncForProcessIds.add(bpmnProcessId);

  }

  /**
   * Every start after this call which creates an instance answers with this meta map, until
   * {@link #reset()}. An engine adapter puts there whatever it has. A test uses this to play
   * an engine which names the version tag of the started process definition.
   *
   * @param meta What the answer to a start carries in its meta map
   */
  public void answerStartsWithMeta(
      final Map<String, String> meta) {

    metaOfStartAnswers.clear();
    metaOfStartAnswers.putAll(meta);

  }

  /**
   * Clears the recordings and every failure a test asked for. It KEEPS the deployments, the
   * started instances and the open subscriptions. This is for tests which run several
   * operations against the subscriptions opened at startup. The failures asked for at a
   * start are cleared too: {@link #failPreflightFor(String)} does not end after one use. A
   * test which left it behind would make every workflow start after it fail.
   */
  public void clearTaskRecordings() {

    invocations.clear();
    completedTasks.clear();
    completionPayloads.clear();
    erroredTasks.clear();
    failedTasks.clear();
    failNextCompletionForTaskIds.clear();
    openTaskIds.clear();
    deliveredTo.clear();
    deliveredAs.clear();
    deliveredIn.clear();
    correlatedMessages.clear();
    failPreflightForProcessIds.clear();
    failNextSyncForProcessIds.clear();

  }

  /**
   * Clears all recorded calls and all state of the fake.
   */
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
    deliveredTo.clear();
    deliveredAs.clear();
    deliveredIn.clear();
    correlatedMessages.clear();
    failPreflightForProcessIds.clear();
    failNextSyncForProcessIds.clear();
    metaOfStartAnswers.clear();

  }

  /**
   * Reads the BPMN process id from the command. The interface {@code StartProcessCommand} of
   * the API has no getter for it. Depending on the adapter core just for its command type
   * would tie this fake to the adapter. So the id is read by reflection, from a method
   * {@code getBpmnProcessId()} if there is one.
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
    // Only ExecutionMode.SYNC creates an instance. It is phase two of the two-phase start.
    // ExecutionMode.PREFLIGHT_CHECK is phase one. It only checks and must not create an
    // instance. Any other mode is recorded and creates nothing either.
    // The mock cannot check a PREFLIGHT_CHECK against the deployed processes. It cannot
    // read the deployed resources, because the API has no BPMN model type (see GAPS.md).
    // So tests ask for a failure through failPreflightFor instead.
    if (cmd.executionMode() == ExecutionMode.PREFLIGHT_CHECK) {
      record("StartProcessApi", "startProcess", cmd);
      if ((bpmnProcessId != null) && failPreflightForProcessIds.contains(bpmnProcessId)) {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "Preflight check failed for BPMN process '%s' (injected by the mock)".formatted(bpmnProcessId)));
      }
      return CompletableFuture.completedFuture(new ProcessInformation("mock-no-instance", Map.of()));
    }
    if ((cmd
        .executionMode() != ExecutionMode.SYNC) && !(cmd instanceof StartProcessByMessageCmd)) {
      // StartProcessByMessageCmd is final and cannot carry an execution mode other than the
      // default (GAPS.md entry 11). So its command in DEFAULT mode is the start of phase two,
      // and it creates an instance like a SYNC one.
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
    startedInstances.add(new StartedInstance(instanceId, variables, bpmnProcessId));
    // Record LAST. Tests wait for the SYNC call and then assert the started instance. If
    // the call were recorded first, the test thread could look before the instance exists.
    record("StartProcessApi", "startProcess", cmd);
    return CompletableFuture.completedFuture(new ProcessInformation(instanceId, Map.copyOf(metaOfStartAnswers)));

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
      final SendSignalCmd cmd) {

    record("SignalApi", "sendSignal", cmd);
    broadcastSignals.add(cmd.getSignalName());
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  /**
   * The signals sent so far, for tests to read.
   *
   * @return The signal names, in the order they were broadcast
   */
  public List<String> getBroadcastSignals() {

    return List.copyOf(broadcastSignals);

  }

  private final List<String> broadcastSignals = new CopyOnWriteArrayList<>();

  /**
   * A correlated message, for tests to read.
   *
   * @param messageName The BPMN message name
   * @param correlationKey The correlation key (aggregate ID or correlation id)
   */
  public record CorrelatedMessage(String messageName, String correlationKey) {
  }

  private final List<CorrelatedMessage> correlatedMessages = new CopyOnWriteArrayList<>();

  /**
   * What was correlated. A message carries no content of its own here. So a name and a
   * correlation key are all a test can assert.
   *
   * @return The messages correlated so far, in call order
   */
  public List<CorrelatedMessage> getCorrelatedMessages() {

    return correlatedMessages;

  }

  // --- TaskSubscriptionApi ---

  /**
   * One open task subscription. It holds the subscribed task definition, the payload
   * variables it asked for, and the handler which gets the delivered tasks.
   *
   * @param taskDescriptionKey The subscribed task definition
   * @param taskType Whether the subscriber asked for asynchronous tasks or for user tasks.
   *          One name may be subscribed as both, and then this tells the two apart
   * @param payloadDescription The payload variables the subscription asked for. An EMPTY
   *          set is how the API asks for everything
   * @param handler The subscriber's task handler
   * @param termination Where the subscriber is told that a delivered task is gone
   */
  public record ActiveSubscription(
                                   String taskDescriptionKey,
                                   TaskType taskType,
                                   Set<String> payloadDescription,
                                   TaskHandler handler,
                                   TaskTerminationHandler termination) implements TaskSubscription {

    /**
     * Cuts down a delivered payload the way an engine does. A subscription which names
     * variables gets those and nothing else. One which names none gets everything.
     *
     * @param payload What the process instance holds
     * @return What this subscription's handler sees
     */
    public Map<String, Object> narrow(
        final Map<String, Object> payload) {

      if (payloadDescription.isEmpty()) {
        return payload;
      }
      final var narrowed = new LinkedHashMap<String, Object>();
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

  private final List<ActiveSubscription> subscriptions = new CopyOnWriteArrayList<>();

  /**
   * Which tasks this engine would deliver to whom. A test reads them to check what the
   * subscriptions ask the engine for.
   *
   * @return The subscriptions which are open, in the order they were opened
   */
  public List<ActiveSubscription> getSubscriptions() {

    return subscriptions;

  }

  /**
   * Delivers a task to the subscription for the given task definition. The mock cannot read
   * tasks out of the deployed resources. So tests deliver tasks themselves, and they deliver
   * a task TWICE to test delivery at least once. The BPMN process ID goes in the meta of
   * {@code TaskInformation}, under the key <code>bpmnProcessId</code> the adapter agrees
   * on.
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

    deliverTask(taskId, taskDefinition, bpmnProcessId, payload, Map.of());

  }

  /**
   * Delivers a task the way an engine does which puts a lot into its meta map. Next to the
   * BPMN process id, the subscriber gets whatever this engine knows about the task: the
   * element id, the assignee, the candidate users and groups, a due date, the name a modeller
   * wrote. The reference adapter for an embedded Camunda 7 fills in these keys. A test about
   * what a subscriber makes of them needs an engine which can send them.
   * <p>
   * The keys are the words of the engine. On the side of the adapter, {@code PeaTaskMeta}
   * names them. The mock cannot reach that class, because the adapter depends on this
   * module and not the other way round.
   * <p>
   * The task names its instance under {@link CommonRestrictions#PROCESS_INSTANCE_ID}. That is
   * the latest instance this engine started for that BPMN process whose start variables agree
   * with the payload (see {@link StartedInstance}). The id is the one the start answered
   * with. A task which matches no started instance names none. If the <code>meta</code> names
   * an instance itself, that one wins. This is how a test plays a task of a called process,
   * which runs in an instance the application never started.
   *
   * @param taskId The delivered task's ID
   * @param taskDefinition The task definition (matched against subscriptions)
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param payload The task's payload variables
   * @param meta What else this engine says about the task; an entry named
   *          <code>bpmnProcessId</code> is overwritten by the argument above
   */
  public void deliverTask(
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final Map<String, Object> payload,
      final Map<String, String> meta) {

    openTaskIds.add(taskId);
    final var subscription = subscriptionFor(taskDefinition);
    // Remember which subscription got the task. Then a completion can tell the same
    // subscription that the task is gone. An engine remembers this too. Without it, the
    // completion API and the termination callback would have nothing to do with each other.
    deliveredTo.put(taskId, subscription);
    deliveredAs.put(taskId, bpmnProcessId);
    final var all = withInstance(taskId, bpmnProcessId, payload, meta);
    subscription
        .handler()
        .accept(
            new TaskInformation(taskId, metaOf(bpmnProcessId, all)),
            // An engine hands the subscriber what the subscription asked for. What the
            // adapter works out from it only counts if the mock does the same.
            subscription.narrow(payload));

  }

  /**
   * The meta map of a delivery or a termination. It holds what the caller said about the
   * task. It also holds the BPMN process id, which this engine always names if it knows it.
   *
   * @param bpmnProcessId The BPMN process, or <code>null</code> for an engine which fills
   *          none
   * @param meta What else the engine says
   * @return The meta map
   */
  private static Map<String, String> metaOf(
      final String bpmnProcessId,
      final Map<String, String> meta) {

    final var all = new LinkedHashMap<String, String>(meta);
    if (bpmnProcessId != null) {
      all.put("bpmnProcessId", bpmnProcessId);
    }
    return Map.copyOf(all);

  }

  /**
   * Adds the instance a delivered task belongs to, unless the caller named one. It remembers
   * the instance for the termination of that task.
   *
   * @param taskId The delivered task
   * @param bpmnProcessId The BPMN process the task is delivered as, or <code>null</code>
   * @param payload What the task carries
   * @param meta What the caller says about the task
   * @return The meta map with the instance, where one is known
   */
  private Map<String, String> withInstance(
      final String taskId,
      final String bpmnProcessId,
      final Map<String, Object> payload,
      final Map<String, String> meta) {

    final var named = meta.get(CommonRestrictions.PROCESS_INSTANCE_ID);
    final var instanceId = named != null
        ? named
        : startedInstances
            .reversed()
            .stream()
            .filter(instance -> instance.holds(bpmnProcessId, payload))
            .map(StartedInstance::instanceId)
            .findFirst()
            .orElse(null);
    if (instanceId == null) {
      deliveredIn.remove(taskId);
      return meta;
    }
    deliveredIn.put(taskId, instanceId);
    final var all = new LinkedHashMap<String, String>(meta);
    all.put(CommonRestrictions.PROCESS_INSTANCE_ID, instanceId);
    return all;

  }

  /**
   * The meta of a termination. It names the instance the task was delivered in, unless the
   * caller named one. An engine names the instance when it takes a task back, too.
   *
   * @param instanceId The instance the task was delivered in, or <code>null</code>
   * @param meta What the caller says about the task
   * @return The meta map with the instance, where one is known
   */
  private static Map<String, String> withInstance(
      final String instanceId,
      final Map<String, String> meta) {

    if ((instanceId == null) || meta.containsKey(CommonRestrictions.PROCESS_INSTANCE_ID)) {
      return meta;
    }
    final var all = new LinkedHashMap<String, String>(meta);
    all.put(CommonRestrictions.PROCESS_INSTANCE_ID, instanceId);
    return all;

  }

  /**
   * Takes back a task the mock delivered. The termination handler of the subscription is
   * called with the reason, in the words of the engine. This is how an engine tells a
   * subscriber that a task it got is gone. The reason goes in {@code TaskInformation.meta}
   * under {@link TaskInformation#REASON}. It is {@code complete} if a completion API finished
   * the task, and {@code delete} for everything else. These are the words the engine adapters
   * of the API use.
   *
   * @param taskId The terminated task's ID
   * @param taskDefinition The task definition (matched against subscriptions)
   * @param bpmnProcessId The BPMN process the task belonged to, or <code>null</code> to
   *          terminate it the way an engine does which fills no meta of its own
   * @param reason The engine's reason
   */
  public void terminateTask(
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final String reason) {

    terminateTask(taskId, taskDefinition, bpmnProcessId, reason, Map.of());

  }

  /**
   * Takes back a task and says as much about it as {@link #deliverTask(String, String,
   * String, Map, Map)} does. An engine which puts a lot into its meta map does so for a
   * termination too.
   *
   * @param taskId The terminated task's ID
   * @param taskDefinition The task definition (matched against subscriptions)
   * @param bpmnProcessId The BPMN process the task belonged to, or <code>null</code>
   * @param reason The engine's reason
   * @param meta What else this engine says about the task
   */
  public void terminateTask(
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final String reason,
      final Map<String, String> meta) {

    openTaskIds.remove(taskId);
    deliveredTo.remove(taskId);
    deliveredAs.remove(taskId);
    final var instanceId = deliveredIn.remove(taskId);
    terminate(subscriptionFor(taskDefinition), taskId, bpmnProcessId, reason, withInstance(instanceId, meta));

  }

  /**
   * Tells the subscription which got a task that the task is gone. An engine does this after
   * a completion API finished the task. So a test sees the reason of a termination from end
   * to end, and not only when it calls {@link #terminateTask(String, String, String, String)}
   * itself.
   *
   * @param taskId The completed task
   * @param reason The engine's word for what happened to it
   */
  private void terminateDelivered(
      final String taskId,
      final String reason) {

    final var subscription = deliveredTo.remove(taskId);
    final var bpmnProcessId = deliveredAs.remove(taskId);
    final var instanceId = deliveredIn.remove(taskId);
    if (subscription == null) {
      // The task was completed but never delivered here, so there is nobody to tell.
      return;
    }
    terminate(subscription, taskId, bpmnProcessId, reason, withInstance(instanceId, Map.of()));

  }

  private void terminate(
      final ActiveSubscription subscription,
      final String taskId,
      final String bpmnProcessId,
      final String reason,
      final Map<String, String> meta) {

    subscription
        .termination()
        .accept(new TaskInformation(taskId, metaOf(bpmnProcessId, meta)).withReason(reason));

  }

  /**
   * Delivers a task to the subscription of that name AND of that kind. One task definition
   * may be subscribed as an asynchronous task and as a user task at the same time. An
   * application does that for a BPMN process id it declares without a model, because it
   * cannot tell which of the two the task is. An engine knows. So a test about that case says
   * which one it delivers.
   *
   * @param taskId The delivered task's ID
   * @param taskDefinition The task definition (matched against subscriptions)
   * @param taskType Whether an asynchronous task or a user task is delivered
   * @param bpmnProcessId The BPMN process the task belongs to, or <code>null</code> for an
   *          engine which names none
   * @param payload The task's payload variables
   */
  public void deliverTask(
      final String taskId,
      final String taskDefinition,
      final TaskType taskType,
      final String bpmnProcessId,
      final Map<String, Object> payload) {

    openTaskIds.add(taskId);
    final var subscription = subscriptionFor(taskDefinition, taskType);
    deliveredTo.put(taskId, subscription);
    if (bpmnProcessId != null) {
      deliveredAs.put(taskId, bpmnProcessId);
    }
    final var meta = withInstance(taskId, bpmnProcessId, payload, Map.of());
    subscription
        .handler()
        .accept(
            new TaskInformation(taskId, metaOf(bpmnProcessId, meta)),
            subscription.narrow(payload));

  }

  private ActiveSubscription subscriptionFor(
      final String taskDefinition,
      final TaskType taskType) {

    return subscriptions
        .stream()
        .filter(candidate -> candidate.taskDescriptionKey().equals(taskDefinition))
        .filter(candidate -> candidate.taskType() == taskType)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No %s subscription for task definition '%s' - subscribed: %s"
                .formatted(taskType, taskDefinition, subscriptions
                    .stream()
                    .map(ActiveSubscription::taskDescriptionKey)
                    .toList())));

  }

  private ActiveSubscription subscriptionFor(
      final String taskDefinition) {

    return subscriptions
        .stream()
        .filter(candidate -> candidate.taskDescriptionKey().equals(taskDefinition))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No subscription for task definition '%s' - subscribed: %s"
                .formatted(taskDefinition, subscriptions
                    .stream()
                    .map(ActiveSubscription::taskDescriptionKey)
                    .toList())));

  }

  @Override
  public CompletableFuture<TaskSubscription> subscribeForTask(
      final SubscribeForTaskCmd cmd) {

    record("TaskSubscriptionApi", "subscribeForTask", cmd);
    final var subscription = new ActiveSubscription(
        cmd.getTaskDescriptionKey(), cmd.getTaskType(), cmd.getPayloadDescription(), cmd.getAction(), cmd
            .getTermination());
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
   * A task completed through {@code completeTask}, for tests to read.
   *
   * @param taskId The task which was completed
   */
  public record CompletedTask(String taskId) {
  }

  /**
   * A task completed by a BPMN error, for tests to read.
   *
   * @param taskId The task which raised the error
   * @param errorCode The BPMN error code, which is what the model catches
   * @param errorMessage The BPMN error message
   */
  public record ErroredTask(String taskId, String errorCode, String errorMessage) {
  }

  /**
   * A task reported as failed through {@code failTask}, for tests to read.
   *
   * @param taskId The task whose handler threw
   * @param reason The short reason the adapter reported
   * @param retryCount How many attempts the adapter left the task, or <code>null</code> if
   *          it left the number to the engine
   */
  public record FailedTask(String taskId, String reason, Integer retryCount) {
  }

  private final List<CompletedTask> completedTasks = new CopyOnWriteArrayList<>();

  private final Map<String, Map<String, Object>> completionPayloads = new ConcurrentHashMap<>();

  /**
   * What came with a completion: the values the aggregate shares, and the variable with the
   * aggregate ID. It is kept per task and not in the list of completions. So a test about
   * the completion itself does not depend on what the aggregate shares.
   *
   * @param taskId The completed (or by-error completed) task
   * @return The payload the completion carried, or an empty map for none or an unknown task
   */
  public Map<String, Object> getCompletionPayload(
      final String taskId) {

    return completionPayloads.getOrDefault(taskId, Map.of());

  }

  private final List<ErroredTask> erroredTasks = new CopyOnWriteArrayList<>();

  private final List<FailedTask> failedTasks = new CopyOnWriteArrayList<>();

  /**
   * The task IDs whose NEXT completeTask fails, once. An entry is removed when it is used.
   * Tests use it for what delivery at least once leaves over: the completion fails after the
   * local commit, the engine delivers again, and the handler ends in the same state.
   */
  private final Set<String> failNextCompletionForTaskIds = ConcurrentHashMap.newKeySet();

  /**
   * The task IDs delivered through {@link #deliverTask} and not yet completed or canceled.
   * The mock does not know what a deployment holds, but it KNOWS which tasks are open. So it
   * can check a {@code PREFLIGHT_CHECK} of a task completion for real.
   */
  private final Set<String> openTaskIds = ConcurrentHashMap.newKeySet();

  /**
   * Which subscription got a delivered task, and as which BPMN process. This is what the
   * engine remembers, and a termination is reported from it.
   */
  private final Map<String, ActiveSubscription> deliveredTo = new ConcurrentHashMap<>();

  private final Map<String, String> deliveredAs = new ConcurrentHashMap<>();

  /**
   * Which instance a delivered task was given out in, so its termination names the same one.
   */
  private final Map<String, String> deliveredIn = new ConcurrentHashMap<>();

  /**
   * The tasks this engine holds as open: delivered, and neither completed nor terminated.
   * A preflight is answered from it.
   *
   * @return The ids of the open tasks
   */
  public Set<String> getOpenTaskIds() {

    return openTaskIds;

  }

  /**
   * The tasks which were finished normally.
   *
   * @return The tasks completed so far, in call order
   */
  public List<CompletedTask> getCompletedTasks() {

    return completedTasks;

  }

  /**
   * The tasks which raised a BPMN error instead of finishing, with the code the model is
   * meant to catch.
   *
   * @return The tasks ended by a BPMN error so far, in call order
   */
  public List<ErroredTask> getErroredTasks() {

    return erroredTasks;

  }

  /**
   * The tasks whose handler threw. A real engine would count the retries down and end in an
   * incident. This one only remembers that it was told.
   *
   * @return The tasks reported as failed so far, in call order
   */
  public List<FailedTask> getFailedTasks() {

    return failedTasks;

  }

  /**
   * Makes the next completion of that task fail. So a test can take the path of an outbox
   * retry without a real engine that fails.
   *
   * @param taskId The task whose next completion fails
   */
  public void failNextCompletionFor(
      final String taskId) {

    failNextCompletionForTaskIds.add(taskId);

  }

  @Override
  public CompletableFuture<Empty> completeTask(
      final CompleteTaskCmd cmd) {

    record("TaskCompletionApi", "completeTask", cmd);
    if (cmd.executionMode() == ExecutionMode.PREFLIGHT_CHECK) {
      // Only check, never move on: the task has to be OPEN.
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
      // The SYNC completion of a task which is gone fails. The adapter accepts this as what
      // delivery at least once leaves over.
      return CompletableFuture.failedFuture(new IllegalStateException(
          "Task '%s' is not open (already completed or canceled)".formatted(cmd.getTaskId())));
    }
    completedTasks.add(new CompletedTask(cmd.getTaskId()));
    completionPayloads.put(cmd.getTaskId(), payloadOf(cmd.get()));
    // The reason the reference adapter for an embedded Camunda 7 reports for a task which a
    // completion API finished.
    terminateDelivered(cmd.getTaskId(), TaskInformation.COMPLETE);
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
    completionPayloads.put(cmd.getTaskId(), payloadOf(cmd.get()));
    // A task ended by a BPMN error did not finish. The reference adapter says 'delete' for
    // everything it sees which was not a completion.
    terminateDelivered(cmd.getTaskId(), TaskInformation.DELETE);
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  /**
   * A copy of a completion payload which KEEPS null values. An attribute of the aggregate
   * which the workflow has not filled yet comes as null. {@code Map.copyOf} refuses that. A
   * mock which is stricter than the engines it stands in for makes a valid completion crash.
   * The Quarkus end-to-end tests found this, when the first aggregate with an unset shared
   * attribute reached a task completion.
   *
   * @param payload The payload the completion command carried
   * @return An unmodifiable copy, null values included
   */
  private static Map<String, Object> payloadOf(
      final Map<String, ?> payload) {

    return payload == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(payload));

  }

  @Override
  public CompletableFuture<Empty> failTask(
      final FailTaskCmd cmd) {

    record("ServiceTaskCompletionApi", "failTask", cmd);
    failedTasks.add(new FailedTask(cmd.getTaskId(), cmd.getReason(), cmd.getRetryCount()));
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
