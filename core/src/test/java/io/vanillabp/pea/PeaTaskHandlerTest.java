package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * Cases of the {@link PeaTaskHandler} which the end-to-end tests do not reach. The mock
 * engine always fills the {@code bpmnProcessId} meta entry. So the fallback to the one
 * process of a task definition, and the guiding failure when there are several, are tested
 * here directly. The same goes for routing, error codes and failures.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaTaskHandlerTest {

  static class RecordingInvoker implements WorkflowTaskInvoker {

    String invokedBpmnProcessId;

    String invokedProcessVersion;

    String invokedBpmnElementId;

    String invokedDeliveryId;

    String invokedActivationId;

    /**
     * A <code>&#64;TaskParam</code> the handler method would read, or <code>null</code> for a
     * method which reads only its aggregate.
     */
    String readParameter;

    Object readParameterValue;

    /**
     * What the business method did. It completes the task, unless a test says otherwise.
     */
    WorkflowTaskOutcome outcome = WorkflowTaskOutcome.completed();

    /**
     * What the core throws instead of an answer, or <code>null</code> for a delivery which
     * reaches a business method.
     */
    RuntimeException refusal;

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      invokedBpmnProcessId = bpmnProcessId;
      invokedProcessVersion = context.getProcessVersion();
      invokedBpmnElementId = context.getBpmnElementId();
      invokedDeliveryId = context.getDeliveryId();
      invokedActivationId = context.getActivationId();
      if (readParameter != null) {
        readParameterValue = context.getTaskParameter(readParameter);
      }
      if (refusal != null) {
        throw refusal;
      }
      return outcome;

    }

    // The migration fallback. It is deprecated for removal, and this BPMS does not use it.
    // A test double has to implement it while the interface declares it. The 'removal'
    // lint is mandatory, so it needs the suppression.
    @SuppressWarnings("removal")
    @Override
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName) {
      return false;
    }

    @SuppressWarnings("removal")
    @Override
    public Object resolveWorkflowAggregateProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String property) {

      return null;

    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {
      return true;
    }


    /**
     * What the completion payload carries next to the ID variable.
     */
    Map<String, Object> syncedValues = Map.of();

    @Override
    public Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final AggregateSyncMode adapterDefault) {

      return syncedValues;

    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return "id";

    }

  }

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final RecordingInvoker invoker = new RecordingInvoker();

  private PeaTaskHandler handler(
      final List<String> bpmnProcessIds) {

    return PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engine)
        .build();

  }

  /**
   * The handler of a subscription whose module avoids name clashes the given way. The mode
   * decides what the engine's meta entry looks like and what the handler has to strip off.
   */
  private PeaTaskHandler handler(
      final List<String> bpmnProcessIds,
      final NameClashAvoidance mode) {

    return PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engine)
        .scoping(TestScoping.of(mode, "test-module"))
        .build();

  }

  private PeaTaskHandler handler(
      final List<String> bpmnProcessIds,
      final PeaFetchVariables.Selection fetchVariables) {

    return PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engine)
        .fetchVariables(fetchVariables)
        .build();

  }

  @Test
  public void missingMetaEntryFallsBackToTheUniqueProcess() {

    // The mock only completes OPEN tasks.
    engine.getOpenTaskIds().add("task-1");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-1", Map.of()),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);
    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-1")),
        engine.getCompletedTasks());

  }

  @Test
  public void missingMetaEntryWithAmbiguousDefinitionFailsGuiding() {

    handler(List.of("ProcessA", "ProcessB"))
        .accept(
            new TaskInformation("task-2", Map.of()),
            Map.of("id", "4711"));

    assertEquals(1, engine.getFailedTasks().size());
    final var reason = engine.getFailedTasks().getFirst().reason();
    assertTrue(
        reason.contains(PeaTaskMeta.BPMN_PROCESS_ID) && reason.contains("ProcessA"),
        "expected a guiding failure naming the meta key and the candidate processes but got: "
            + reason);

  }

  @Test
  @DisplayName("A scoped meta entry is routed to the plain process the core is keyed by")
  public void aScopedMetaEntryIsRoutedToThePlainProcess() {

    // An engine fills the meta entry with the id IT knows. Under use-prefix that id
    // carries the workflow module. The core's registries use the plain id as key, so a
    // value passed on with the prefix matches nothing.
    engine.getOpenTaskIds().add("task-scoped");
    handler(List.of("OnlyProcess"), NameClashAvoidance.USE_PREFIX)
        .accept(
            new TaskInformation(
                "task-scoped", Map.of(
                    PeaTaskMeta.BPMN_PROCESS_ID,
                    "test-module"
                        + NameClashAvoidanceSupport.SEPARATOR
                        + "OnlyProcess")),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);
    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-scoped")),
        engine.getCompletedTasks());

  }

  @Test
  @DisplayName("Under 'none' the meta entry passes through as the engine spelled it")
  public void underNoneTheMetaEntryPassesThrough() {

    engine.getOpenTaskIds().add("task-plain");
    handler(List.of("OnlyProcess", "AnotherProcess"), NameClashAvoidance.NONE)
        .accept(
            new TaskInformation("task-plain", Map.of(PeaTaskMeta.BPMN_PROCESS_ID, "AnotherProcess")),
            Map.of("id", "4711"));

    assertEquals("AnotherProcess", invoker.invokedBpmnProcessId);

  }

  @Test
  @DisplayName("Without a meta entry the single process of the subscription still answers, prefixes or not")
  public void withoutAMetaEntryTheSingleProcessStillAnswersUnderPrefixes() {

    // The fallback reads the subscription, not the delivery. A subscription already
    // holds the plain id.
    engine.getOpenTaskIds().add("task-fallback");
    handler(List.of("OnlyProcess"), NameClashAvoidance.USE_PREFIX)
        .accept(
            new TaskInformation("task-fallback", Map.of()),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);

  }

  /**
   * The handler of a subscription which knows which element each of its processes delivers
   * from. {@code PeaDeploymentService} reads this from the deployed models.
   */
  private PeaTaskHandler handler(
      final List<String> bpmnProcessIds,
      final Map<String, String> bpmnElementIds) {

    return PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(bpmnProcessIds)
        .bpmnElementIds(bpmnElementIds)
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engine)
        .build();

  }

  @Test
  @DisplayName("The element of a delivery is the one its process carries the task definition on")
  public void theElementComesFromTheProcessTheDeliveryWasRoutedTo() {

    // One subscription, two processes, and the same task definition on a different element
    // in each. The handler first routes the delivery to a process. Then the model of that
    // process says which element the delivery belongs to.
    engine.getOpenTaskIds().add("task-element");
    handler(List.of("ProcessA", "ProcessB"), Map.of("ProcessA", "ApproveInA", "ProcessB", "ApproveInB"))
        .accept(
            new TaskInformation("task-element", Map.of(PeaTaskMeta.BPMN_PROCESS_ID, "ProcessB")),
            Map.of("id", "4711"));

    assertEquals("ProcessB", invoker.invokedBpmnProcessId);
    assertEquals("ApproveInB", invoker.invokedBpmnElementId);

  }

  @Test
  @DisplayName("An element the engine names itself is reported instead of the one the model holds")
  public void theElementTheEngineNamesWins() {

    // The model says which element carries a name. The engine says which element THIS
    // delivery came from, and the engine runs the model, so it knows better.
    engine.getOpenTaskIds().add("task-element-meta");
    handler(List.of("OnlyProcess"), Map.of("OnlyProcess", "ApproveFromTheModel"))
        .accept(
            new TaskInformation("task-element-meta", Map.of(PeaTaskMeta.BPMN_TASK_ID, "ApproveFromTheEngine")),
            Map.of("id", "4711"));

    assertEquals("ApproveFromTheEngine", invoker.invokedBpmnElementId);

  }

  @Test
  @DisplayName("A subscription whose models name no element reports none")
  public void withoutAModelAndWithoutTheEngineNoElementIsReported() {

    engine.getOpenTaskIds().add("task-element-none");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-element-none", Map.of()),
            Map.of("id", "4711"));

    assertEquals(null, invoker.invokedBpmnElementId);

  }

  @Test
  public void theVersionTagOfTheTaskMetaIsReported() {

    // The Process-Engine-API knows no version NUMBER (GAPS.md, entry 19). So a version
    // specification can only be matched against the version tag in the task's meta map.
    // And only if the engine behind the API fills it.
    engine.getOpenTaskIds().add("task-4");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-4", Map.of(PeaTaskMeta.PROCESS_VERSION_TAG, "release-2024")),
            Map.of("id", "4711"));

    assertEquals("release-2024", invoker.invokedProcessVersion);

    // Without the meta entry no version is reported, and that matches every method.
    engine.getOpenTaskIds().add("task-5");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-5", Map.of()),
            Map.of("id", "4711"));

    assertEquals(null, invoker.invokedProcessVersion);

  }

  @Test
  public void everyTaskIsItsOwnActivation() {

    // Two elements of a multi-instance activity are two tasks of this engine, so their
    // activations differ. That keeps the correlations they plan from sharing an
    // idempotency key. Here the delivery id and the activation id are the same value on
    // purpose. This engine creates one task per activation and redelivers it under that
    // id, so one value serves both contracts.
    engine.getOpenTaskIds().add("task-mi-0");
    final var testee = handler(List.of("OnlyProcess"));
    testee.accept(new TaskInformation("task-mi-0", Map.of()), Map.of("id", "4711"));
    final var firstElement = invoker.invokedActivationId;
    assertEquals("task-mi-0", firstElement);
    assertEquals(invoker.invokedDeliveryId, firstElement);

    engine.getOpenTaskIds().add("task-mi-1");
    testee.accept(new TaskInformation("task-mi-1", Map.of()), Map.of("id", "4711"));
    assertEquals("task-mi-1", invoker.invokedActivationId);
    assertTrue(
        !firstElement.equals(invoker.invokedActivationId),
        "two elements of one aggregate must not share an activation");

  }

  @Test
  public void missingAggregateIdVariableFailsGuiding() {

    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-3", Map.of()),
            Map.of("unrelated", "x"));

    assertEquals(1, engine.getFailedTasks().size());
    final var reason = engine.getFailedTasks().getFirst().reason();
    assertTrue(
        reason.contains("'id'"),
        "expected a guiding failure naming the missing payload variable but got: "
            + reason);

  }

  @Test
  public void aTaskParameterTheSubscriptionAskedForIsAnswered() {

    // The subscription named 'region', so the delivery carries it. The core reads it
    // through the invocation context.
    engine.getOpenTaskIds().add("task-6");
    invoker.readParameter = "region";
    handler(
        List.of("OnlyProcess"),
        PeaFetchVariables.Selection.of(List.of("id", "region")))
        .accept(
            new TaskInformation("task-6", Map.of()),
            Map.of("id", "4711", "region", "east"));

    assertEquals("east", invoker.readParameterValue);

  }

  @Test
  public void aTaskParameterOutsideTheSubscriptionFailsGuiding() {

    // A name which no @TaskParam declares cannot have reached the subscription. A null
    // for the method would look just like a variable which is really missing.
    invoker.readParameter = "bigPayload";
    handler(List.of("OnlyProcess"), PeaFetchVariables.Selection.of(List.of("id")))
        .accept(
            new TaskInformation("task-7", Map.of()),
            Map.of("id", "4711"));

    assertEquals(1, engine.getFailedTasks().size());
    final var reason = engine.getFailedTasks().getFirst().reason();
    assertTrue(
        reason.contains("bigPayload") && reason.contains("workflow aggregate") && !reason.contains("fetch-variables"),
        "expected a guiding failure naming the variable and the workflow aggregate, and no removed key, but got: "
            + reason);

  }

  @Test
  @DisplayName("A TaskException raises the error code the deployed model carries, prefix included")
  public void aTaskExceptionRaisesTheCodeTheModelCarries() {

    // Under use-prefix the errorCode of a bpmn:error is deployed with the module's prefix,
    // like every other identifier. No boundary event of that model catches a plain code,
    // and nothing says so. The engine accepts the command, and the workflow skips the
    // error path.
    engine.getOpenTaskIds().add("task-error");
    invoker.outcome = WorkflowTaskOutcome.bpmnError("PAYMENT_FAILED", "Payment failed");
    handler(List.of("OnlyProcess"), NameClashAvoidance.USE_PREFIX)
        .accept(
            new TaskInformation("task-error", Map.of()),
            Map.of("id", "4711"));

    assertEquals(1, engine.getErroredTasks().size());
    assertEquals(
        "test-module"
            + NameClashAvoidanceSupport.SEPARATOR
            + "PAYMENT_FAILED",
        engine.getErroredTasks().getFirst().errorCode());
    assertEquals("Payment failed", engine.getErroredTasks().getFirst().errorMessage());

  }

  @Test
  @DisplayName("Under 'none' the error code reaches the engine as the application wrote it")
  public void underNoneTheErrorCodeIsPassedThrough() {

    engine.getOpenTaskIds().add("task-plain-error");
    invoker.outcome = WorkflowTaskOutcome.bpmnError("PAYMENT_FAILED", "Payment failed");
    handler(List.of("OnlyProcess"), NameClashAvoidance.NONE)
        .accept(
            new TaskInformation("task-plain-error", Map.of()),
            Map.of("id", "4711"));

    assertEquals("PAYMENT_FAILED", engine.getErroredTasks().getFirst().errorCode());

  }

  @Test
  public void anInterruptedCompletionIsReportedInsteadOfSwallowed() {

    // The local transaction is committed when the completion is sent. A shutdown
    // interrupts the subscription thread. Such an interrupt used to leave a task the
    // engine still owns, and nothing in the log explained the redelivery which followed.
    final var engineWhichNeverAnswers = Mockito.mock(
        ServiceTaskCompletionApi.class,
        Mockito.withSettings().defaultAnswer(invocation -> new CompletableFuture<>()));
    final var testee = PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(List.of("OnlyProcess"))
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engineWhichNeverAnswers)
        .build();

    Thread.currentThread().interrupt();
    final var warnings = warningsOf(
        () -> testee.accept(new TaskInformation("task-8", Map.of()), Map.of("id", "4711")));
    final var interruptSurvived = Thread.interrupted();

    assertTrue(interruptSurvived, "the interrupt was swallowed");
    assertEquals(1, warnings.size(), () -> "expected exactly one warning but got: "
        + warnings);
    assertTrue(
        warnings.getFirst().contains("task-8") && warnings.getFirst().contains("redelivers"),
        "expected a warning naming the task and the redelivery but got: "
            + warnings.getFirst());

  }

  @Test
  @DisplayName("A task of a workflow this application does not own is failed with no further attempt")
  public void aTaskOfAWorkflowWeDoNotOwnIsRefusedOnce() {

    // A task subscription of this API matches a task type across the whole engine, and
    // there is no tenant. So a second application which deploys the same BPMN process gets
    // this application's tasks, and the other way round. If such a task fails with the
    // engine's default retries, the same refusal comes once per attempt. On an engine which
    // never runs out of retries, it comes for ever.
    invoker.refusal = new DeliveryOfAnUnknownWorkflowException(
        "pea", "test-module", "OnlyProcess", "someTask", "io.example.Aggregate", "4711", "wf-99");

    final var warnings = warningsOf(
        () -> handler(List.of("OnlyProcess"))
            .accept(new TaskInformation("task-stranger", Map.of()), Map.of("id", "4711")));

    assertEquals(1, engine.getFailedTasks().size());
    final var failed = engine.getFailedTasks().getFirst();
    assertEquals(
        Integer.valueOf(0), failed.retryCount(),
        "the same delivery would meet the same refusal, so the engine is asked not to repeat it");
    assertTrue(
        failed.reason().contains("does not own"),
        "the reason the engine shows is the core's own message but got: "
            + failed.reason());
    assertEquals(1, warnings.size(), () -> "expected exactly one warning but got: "
        + warnings);
    assertTrue(
        warnings.getFirst().contains("task-stranger") && warnings.getFirst().contains("does not own"),
        "expected a warning naming the task and the reason but got: "
            + warnings.getFirst());

  }

  @Test
  @DisplayName("Any other failure keeps the retries the engine would apply anyway")
  public void anOrdinaryFailureLeavesTheRetriesToTheEngine() {

    // The counterpart of the test above. A handler which threw once may work the next
    // time. So the count stays empty, and the engine decides.
    invoker.refusal = new IllegalStateException("the database was down");

    handler(List.of("OnlyProcess"))
        .accept(new TaskInformation("task-flaky", Map.of()), Map.of("id", "4711"));

    assertEquals(1, engine.getFailedTasks().size());
    assertNull(
        engine.getFailedTasks().getFirst().retryCount(),
        "an ordinary failure must not pin the number of attempts");

  }

  /**
   * What the handler logged at WARN or above while the given work ran.
   */
  private List<String> warningsOf(
      final Runnable work) {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var logger = (Logger) LoggerFactory
        .getLogger(PeaTaskHandler.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

}
