package io.vanillabp.pea.quarkus.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * What the Quarkus end-to-end tests can see of the running application: a handful of
 * <code>introspect/...</code> endpoints reporting the in-memory engine's recordings
 * and triggering the {@code ProcessService} operations the tests want to observe.
 * <p>
 * The endpoints doing two-phase work drive the transaction themselves
 * ({@link UserTransaction}) and report what was already visible INSIDE it, because
 * the whole point of those tests is the ordering: phase one runs in the caller's
 * transaction, phase two only after it committed.
 */
@Path("/introspect")
@ApplicationScoped
public class PeaE2eIntrospectionController {

  @Inject
  InMemoryProcessEngine engine;

  @Inject
  PeaE2eWorkflowService workflowService;

  @Inject
  UserTransaction userTransaction;

  @Inject
  PeaE2eAggregateRepository repository;

  // --- what the engine recorded ---

  @GET
  @Path("/workflow-module")
  public String workflowModule() {

    return workflowService.getWorkflowModuleId();

  }

  @GET
  @Path("/deployments")
  public List<String> deployments() {

    return engine
        .getDeployments()
        .stream()
        .flatMap(deployment -> deployment
            .resources()
            .stream())
        .map(resource -> resource.getName())
        .toList();

  }

  @GET
  @Path("/subscriptions")
  public List<String> subscriptions() {

    return engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .sorted()
        .toList();

  }

  @GET
  @Path("/subscriptions/{taskDefinition}/payload")
  public List<String> subscriptionPayload(
      @PathParam("taskDefinition") final String taskDefinition) {

    return engine
        .getSubscriptions()
        .stream()
        .filter(subscription -> subscription
            .taskDescriptionKey()
            .equals(taskDefinition))
        .findFirst()
        .map(subscription -> subscription
            .payloadDescription()
            .stream()
            .sorted()
            .toList())
        .orElse(List.of());

  }

  @GET
  @Path("/started-instances")
  public List<Map<String, Object>> startedInstances() {

    return engine
        .getStartedInstances()
        .stream()
        .map(InMemoryProcessEngine.StartedInstance::variables)
        .toList();

  }

  @GET
  @Path("/completed-tasks")
  public List<String> completedTasks() {

    return engine
        .getCompletedTasks()
        .stream()
        .map(InMemoryProcessEngine.CompletedTask::taskId)
        .toList();

  }

  @GET
  @Path("/errored-tasks")
  public List<String> erroredTasks() {

    return engine
        .getErroredTasks()
        .stream()
        .map(errored -> errored.taskId()
            + ":"
            + errored.errorCode())
        .toList();

  }

  @GET
  @Path("/failed-tasks")
  public List<String> failedTasks() {

    return engine
        .getFailedTasks()
        .stream()
        .map(InMemoryProcessEngine.FailedTask::taskId)
        .toList();

  }

  @GET
  @Path("/open-tasks")
  public List<String> openTasks() {

    return engine
        .getOpenTaskIds()
        .stream()
        .sorted()
        .toList();

  }

  @GET
  @Path("/tasks/{taskId}/completion-payload")
  public Map<String, Object> completionPayload(
      @PathParam("taskId") final String taskId) {

    return engine.getCompletionPayload(taskId);

  }

  @GET
  @Path("/correlated-messages")
  public List<String> correlatedMessages() {

    return engine
        .getCorrelatedMessages()
        .stream()
        .map(message -> message.messageName()
            + ":"
            + message.correlationKey())
        .toList();

  }

  @GET
  @Path("/signals")
  public List<String> signals() {

    return engine.getBroadcastSignals();

  }

  @GET
  @Path("/execution-modes/{method}/{taskId}")
  public List<String> executionModes(
      @PathParam("method") final String method,
      @PathParam("taskId") final String taskId) {

    return modesOf(method, taskId)
        .stream()
        .map(Enum::name)
        .toList();

  }

  @GET
  @Path("/execution-modes/{method}")
  public List<String> executionModes(
      @PathParam("method") final String method) {

    return engine
        .getInvocations()
        .stream()
        .filter(invocation -> method.equals(invocation.method()))
        .map(invocation -> invocation
            .executionMode()
            .name())
        .toList();

  }

  private List<ExecutionMode> modesOf(
      final String method,
      final String taskId) {

    return engine
        .getInvocations()
        .stream()
        .filter(invocation -> method.equals(invocation.method()))
        .filter(invocation -> {
          final var command = invocation.command();
          return ((command instanceof CompleteTaskCmd complete) && taskId
              .equals(complete
                  .getTaskId())) || ((command instanceof CompleteTaskByErrorCmd byError) && taskId
                      .equals(byError.getTaskId()));
        })
        .map(InMemoryProcessEngine.Invocation::executionMode)
        .toList();

  }

  // --- driving the application ---

  @POST
  @Path("/reset")
  @Transactional
  public void reset() {

    engine.clearTaskRecordings();
    repository.deleteAll();

  }

  /**
   * Seeds an aggregate WITHOUT starting a workflow - the shape the task-delivery
   * tests need, where the engine (not the application) is the one starting things.
   *
   * @param id The aggregate ID
   */
  @POST
  @Path("/aggregates/{id}")
  @Transactional
  public void seedAggregate(
      @PathParam("id") final String id) {

    final var aggregate = new PeaE2eAggregate();
    aggregate.setId(id);
    repository.persist(aggregate);

  }

  @GET
  @Path("/aggregates/{id}")
  @Transactional
  public Map<String, Object> aggregate(
      @PathParam("id") final String id) {

    final var aggregate = repository.findById(id);
    final var state = new LinkedHashMap<String, Object>();
    state.put("exists", aggregate != null);
    if (aggregate != null) {
      state.put("results", aggregate.getResults());
      state.put("taskId", aggregate.getTaskId());
      state.put("secret", aggregate.getSecret());
    }
    return state;

  }

  /**
   * Starts a workflow and reports what was visible while the transaction was still
   * open: phase one has to have run, phase two must not.
   *
   * @param id The aggregate ID
   * @return What phase one left behind inside the transaction
   */
  @POST
  @Path("/workflows/{id}")
  public Map<String, Object> startWorkflow(
      @PathParam("id") final String id) throws Exception {

    final var insideTransaction = new LinkedHashMap<String, Object>();
    userTransaction.begin();
    try {
      workflowService.startWorkflow(id);
      insideTransaction.put("preflightStarts", startsWithMode(ExecutionMode.PREFLIGHT_CHECK));
      insideTransaction.put("syncStarts", startsWithMode(ExecutionMode.SYNC));
      insideTransaction.put("startedInstances", startedInstancesOf(id));
    } catch (Exception e) {
      // a failing phase one aborts the caller's transaction - the test wants to read
      // the message the application would see, so it travels in the response
      userTransaction.rollback();
      insideTransaction.put("exception", e
          .getClass()
          .getSimpleName());
      insideTransaction.put("message", e.getMessage());
    }
    if (!insideTransaction.containsKey("exception")) {
      userTransaction.commit();
    }
    return insideTransaction;

  }

  /**
   * Starts a workflow and rolls the transaction back - no phase two may ever follow.
   *
   * @param id The aggregate ID
   */
  @POST
  @Path("/workflows/{id}/rollback")
  public void startWorkflowAndRollback(
      @PathParam("id") final String id) throws Exception {

    userTransaction.begin();
    workflowService.startWorkflow(id);
    userTransaction.rollback();

  }

  private int startedInstancesOf(
      final String aggregateId) {

    return (int) engine
        .getStartedInstances()
        .stream()
        .filter(instance -> aggregateId.equals(instance
            .variables()
            .get("id")))
        .count();

  }

  private int startsWithMode(
      final ExecutionMode mode) {

    return (int) engine
        .getInvocations()
        .stream()
        .filter(invocation -> "startProcess".equals(invocation.method()))
        .filter(invocation -> invocation.executionMode() == mode)
        .count();

  }

  /**
   * Hands a task to the adapter the way an engine does. The adapter opens its own
   * transaction, so this endpoint deliberately runs without one.
   *
   * @param taskId The delivered task's ID
   * @param taskDefinition The task definition
   * @param aggregateId The aggregate the task belongs to
   */
  @POST
  @Path("/tasks/{taskId}/deliver/{taskDefinition}/{aggregateId}")
  public void deliverTask(
      @PathParam("taskId") final String taskId,
      @PathParam("taskDefinition") final String taskDefinition,
      @PathParam("aggregateId") final String aggregateId) {

    engine.deliverTask(
        taskId,
        taskDefinition,
        PeaE2eWorkflowService.PROCESS,
        Map.of("id", aggregateId));

  }

  /**
   * Lets the engine reject the next phase one for a BPMN process - the injection is
   * cleared by the next {@code introspect/reset}.
   *
   * @param bpmnProcessId The BPMN process whose preflight fails
   */
  @POST
  @Path("/engine/fail-preflight/{bpmnProcessId}")
  public void failPreflight(
      @PathParam("bpmnProcessId") final String bpmnProcessId) {

    engine.failPreflightFor(bpmnProcessId);

  }

  /**
   * Lets the engine reject the next phase two for a BPMN process once, so the outbox
   * has to retry.
   *
   * @param bpmnProcessId The BPMN process whose next SYNC start fails
   */
  @POST
  @Path("/engine/fail-next-sync/{bpmnProcessId}")
  public void failNextSync(
      @PathParam("bpmnProcessId") final String bpmnProcessId) {

    engine.failNextSyncFor(bpmnProcessId);

  }

  @POST
  @Path("/tasks/{taskId}/fail-next-completion")
  public void failNextCompletion(
      @PathParam("taskId") final String taskId) {

    engine.failNextCompletionFor(taskId);

  }

  @POST
  @Path("/tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final String aggregateId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.completeTask(aggregate, taskId),
        aggregateId,
        () -> modesOf("completeTask", taskId));

  }

  @POST
  @Path("/tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final String aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(
        aggregate -> workflowService.cancelTask(aggregate, taskId, errorCode),
        aggregateId,
        () -> modesOf("completeTaskByError", taskId));

  }

  @POST
  @Path("/user-tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final String aggregateId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.completeUserTask(aggregate, taskId),
        aggregateId,
        () -> modesOf("completeTask", taskId));

  }

  @POST
  @Path("/user-tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final String aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(
        aggregate -> workflowService.cancelUserTask(aggregate, taskId, errorCode),
        aggregateId,
        () -> modesOf("completeTaskByError", taskId));

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final String aggregateId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.correlateMessage(aggregate, messageName),
        aggregateId,
        () -> List.of());

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}/{correlationId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final String aggregateId,
      @PathParam("correlationId") final String correlationId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.correlateMessage(aggregate, messageName, correlationId),
        aggregateId,
        () -> List.of());

  }

  /**
   * Correlates a message and rolls the transaction back - nothing may be correlated.
   *
   * @param messageName The message name
   * @param aggregateId The aggregate ID
   */
  @POST
  @Path("/messages/{messageName}/correlate-and-rollback/{aggregateId}")
  public void correlateMessageAndRollback(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final String aggregateId) throws Exception {

    userTransaction.begin();
    workflowService.correlateMessage(repository.findById(aggregateId), messageName);
    userTransaction.rollback();

  }

  @POST
  @Path("/messages/{messageName}/start/{aggregateId}")
  @Transactional
  public void startWorkflowByMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final String aggregateId) {

    final var aggregate = new PeaE2eAggregate();
    aggregate.setId(aggregateId);
    workflowService.startWorkflowByMessage(aggregate, messageName);

  }

  @POST
  @Path("/signals/{signalName}")
  @Transactional
  public void sendSignal(
      @PathParam("signalName") final String signalName) {

    workflowService.sendSignal(signalName);

  }

  /**
   * The adapter cannot push a changed aggregate to a running instance (GAPS entry
   * 18) - what the application gets is the guiding message, and it has to get it on
   * Quarkus as well.
   *
   * @param aggregateId The aggregate ID
   * @return The exception the adapter raised
   */
  @POST
  @Path("/aggregate-changed/{aggregateId}")
  public Map<String, Object> aggregateChanged(
      @PathParam("aggregateId") final String aggregateId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.aggregateChanged(aggregate),
        aggregateId,
        () -> List.of());

  }

  @POST
  @Path("/aggregate-changed/{aggregateId}/{taskId}")
  public Map<String, Object> aggregateChanged(
      @PathParam("aggregateId") final String aggregateId,
      @PathParam("taskId") final String taskId) throws Exception {

    return inTransaction(
        aggregate -> workflowService.aggregateChanged(aggregate, taskId),
        aggregateId,
        () -> List.of());

  }

  // --- the viewer API ---

  @GET
  @Path("/process-definitions/{aggregateId}")
  @Transactional
  public List<String> processDefinitions(
      @PathParam("aggregateId") final String aggregateId) {

    final var aggregate = repository.findById(aggregateId);
    return workflowService
        .getProcessDefinitions(aggregate, null)
        .stream()
        .map(definition -> "%s|%s|%s".formatted(
            definition.id(),
            definition.bpmnProcessId(),
            definition.version()))
        .toList();

  }

  @GET
  @Path("/bpmn-xml/{processDefinitionId}")
  @Transactional
  public String bpmnXml(
      @PathParam("processDefinitionId") final String processDefinitionId) throws Exception {

    try (var xml = workflowService.getBpmnXml(processDefinitionId)) {
      return xml == null
          ? ""
          : new String(xml.readAllBytes());
    }

  }

  @GET
  @Path("/workflow-history/{aggregateId}")
  @Transactional
  public Map<String, Object> workflowHistory(
      @PathParam("aggregateId") final String aggregateId) {

    final var aggregate = repository.findById(aggregateId);
    final var history = workflowService.getWorkflowHistory(aggregate);
    final var reported = new LinkedHashMap<String, Object>();
    reported.put("processDefinitionId", history == null
        ? null
        : history.processDefinitionId());
    reported.put("elementsHistory", (history == null) || (history.elementsHistory() == null)
        ? null
        : history
            .elementsHistory()
            .size());
    return reported;

  }

  /**
   * Runs one {@code ProcessService} call inside a transaction of its own and reports
   * both the exception it may have raised and the execution modes recorded while the
   * transaction was still open.
   */
  private Map<String, Object> inTransaction(
      final java.util.function.Consumer<PeaE2eAggregate> operation,
      final String aggregateId,
      final java.util.function.Supplier<List<ExecutionMode>> modesInsideTransaction) throws Exception {

    final var result = new LinkedHashMap<String, Object>();
    userTransaction.begin();
    try {
      operation.accept(repository.findById(aggregateId));
      result.put("modesInsideTransaction", modesInsideTransaction
          .get()
          .stream()
          .map(Enum::name)
          .toList());
      result.put("correlationsInsideTransaction", engine
          .getCorrelatedMessages()
          .size());
    } catch (Exception e) {
      userTransaction.rollback();
      result.put("exception", e
          .getClass()
          .getSimpleName());
      result.put("message", e.getMessage());
      return result;
    }
    userTransaction.commit();
    return result;

  }

}
