package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * Routing and failure edge cases of the {@link PeaTaskHandler} which
 * the end-to-end tests do not reach: the mock engine always supplies the
 * {@code bpmnProcessId} meta entry, so the unique-definition fallback and the
 * ambiguous-definition guiding failure are exercised here directly.
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
     * A <code>&#64;TaskParam</code> the handler method would read, or
     * <code>null</code> for a method reading nothing but its aggregate.
     */
    String readParameter;

    Object readParameterValue;

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
      return WorkflowTaskOutcome.completed();

    }

    // The migration fallback, deprecated for removal in 2.1 and none of this
    // BPMS's business: a test double implements it as long as the interface declares
    // it, and the mandatory 'removal' lint needs the suppression
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
     * What the completion payload carries beside the ID variable.
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
   * The handler of a subscription whose module avoids name clashes the given way - which
   * decides what the engine's meta entry looks like and what has to be stripped off it.
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

    // the mock only completes OPEN tasks (honest preflight/completion since 22)
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

    // an engine which fills the meta entry fills it with the id IT knows, and under
    // use-prefix that id carries the workflow module. The core's registries are keyed
    // by the plain id, so a value handed on unstripped matches nothing at all
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

    // the fallback reads the subscription rather than the delivery, and what a
    // subscription was opened for is plain already
    engine.getOpenTaskIds().add("task-fallback");
    handler(List.of("OnlyProcess"), NameClashAvoidance.USE_PREFIX)
        .accept(
            new TaskInformation("task-fallback", Map.of()),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);

  }

  /**
   * The handler of a subscription which knows what element each of its processes delivers
   * from - what {@code PeaDeploymentService} reads off the deployed models.
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

    // one subscription, two processes, and the same task definition on a different element
    // in each of them: which element a delivery belongs to is answered after it was routed,
    // by the model of the process it was routed to
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

    // the model says which element carries a name, the engine says which element THIS
    // delivery came from - and the engine is the one running the model it is running
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

    // The Process-Engine-API knows no version NUMBER (GAPS.md, entry 19), so the
    // version tag from the task's meta map is all a version specification can be
    // matched against - and only where the engine behind the API supplies it
    engine.getOpenTaskIds().add("task-4");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-4", Map.of(PeaTaskMeta.PROCESS_VERSION_TAG, "release-2024")),
            Map.of("id", "4711"));

    assertEquals("release-2024", invoker.invokedProcessVersion);

    // without the meta entry no version is reported, which matches every method
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
    // activations differ - which is what keeps the correlations they plan from sharing
    // an idempotency key. Here the delivery id and the activation id are the same
    // value on purpose: this engine creates one task per activation and redelivers it
    // under that id, so one value answers both contracts
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

    // The subscription named 'region', so the delivery carries it and the
    // core reads it through the invocation context
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

    // a name no @TaskParam declares cannot have reached the subscription, and handing
    // the method a null would look exactly like a variable which is genuinely absent
    invoker.readParameter = "bigPayload";
    handler(List.of("OnlyProcess"), PeaFetchVariables.Selection.of(List.of("id")))
        .accept(
            new TaskInformation("task-7", Map.of()),
            Map.of("id", "4711"));

    assertEquals(1, engine.getFailedTasks().size());
    final var reason = engine.getFailedTasks().getFirst().reason();
    assertTrue(
        reason.contains("bigPayload") && reason.contains("vanillabp.adapters.pea.fetch-variables"),
        "expected a guiding failure naming the variable and the escape hatch but got: "
            + reason);

  }

  @Test
  public void anInterruptedCompletionIsReportedInsteadOfSwallowed() {

    // the local transaction is committed when the completion is sent, so an interrupt -
    // what a shutdown does to a subscription thread - used to leave a task the engine
    // still owns, with nothing in the log explaining the redelivery which follows
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
