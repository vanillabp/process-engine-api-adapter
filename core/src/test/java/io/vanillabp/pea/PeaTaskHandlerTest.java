package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaTaskHandler;

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
        reason.contains(PeaTaskHandler.META_BPMN_PROCESS_ID) && reason.contains("ProcessA"),
        "expected a guiding failure naming the meta key and the candidate processes but got: "
            + reason);

  }

  @Test
  public void theVersionTagOfTheTaskMetaIsReported() {

    // The Process-Engine-API knows no version NUMBER (GAPS.md, entry 19), so the
    // version tag from the task's meta map is all a version specification can be
    // matched against - and only where the engine behind the API supplies it
    engine.getOpenTaskIds().add("task-4");
    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("task-4", Map.of(PeaTaskHandler.META_VERSION_TAG, "release-2024")),
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
