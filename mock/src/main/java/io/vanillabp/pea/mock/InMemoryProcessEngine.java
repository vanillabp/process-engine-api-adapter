package io.vanillabp.pea.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
public class InMemoryProcessEngine implements DeploymentApi, StartProcessApi, CorrelationApi, TaskSubscriptionApi, ServiceTaskCompletionApi, UserTaskCompletionApi {

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
   * All invocations recorded so far, in call order. Public and inspectable on purpose:
   * tests assert against it directly.
   */
  public final List<Invocation> invocations = new CopyOnWriteArrayList<>();

  /**
   * @return All invocations recorded so far, in call order.
   */
  public List<Invocation> getInvocations() {

    return invocations;

  }

  /**
   * Clears all recorded invocations (and, later, all fake state).
   */
  public void reset() {

    invocations.clear();

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
    return CompletableFuture.completedFuture(
        new DeploymentInformation("mock-deployment", Instant.now(), cmd.getTenantId()));

  }

  // --- StartProcessApi ---

  @Override
  public CompletableFuture<ProcessInformation> startProcess(
      final StartProcessCommand cmd) {

    record("StartProcessApi", "startProcess", cmd);
    return CompletableFuture.completedFuture(new ProcessInformation("mock-instance", Map.of()));

  }

  // --- CorrelationApi ---

  @Override
  public CompletableFuture<Empty> correlateMessage(
      final CorrelateMessageCmd cmd) {

    record("CorrelationApi", "correlateMessage", cmd);
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  // --- TaskSubscriptionApi ---

  @Override
  public CompletableFuture<TaskSubscription> subscribeForTask(
      final SubscribeForTaskCmd cmd) {

    record("TaskSubscriptionApi", "subscribeForTask", cmd);
    return CompletableFuture.completedFuture(new TaskSubscription() {
    });

  }

  @Override
  public CompletableFuture<Empty> unsubscribe(
      final UnsubscribeFromTaskCmd cmd) {

    record("TaskSubscriptionApi", "unsubscribe", cmd);
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  // --- ServiceTaskCompletionApi / UserTaskCompletionApi (shared method signatures) ---

  @Override
  public CompletableFuture<Empty> completeTask(
      final CompleteTaskCmd cmd) {

    record("TaskCompletionApi", "completeTask", cmd);
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  @Override
  public CompletableFuture<Empty> completeTaskByError(
      final CompleteTaskByErrorCmd cmd) {

    record("TaskCompletionApi", "completeTaskByError", cmd);
    return CompletableFuture.completedFuture(Empty.INSTANCE);

  }

  @Override
  public CompletableFuture<Empty> failTask(
      final FailTaskCmd cmd) {

    record("ServiceTaskCompletionApi", "failTask", cmd);
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
