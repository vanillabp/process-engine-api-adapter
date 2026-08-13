package io.vanillabp.pea.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskHandler;

/**
 * Routing and failure edge cases of the {@link PeaTaskHandler} (story 21c) which
 * the end-to-end tests do not reach: the mock engine always supplies the
 * {@code bpmnProcessId} meta entry, so the unique-definition fallback and the
 * ambiguous-definition guiding failure are exercised here directly.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaTaskHandlerTest {

  static class RecordingInvoker implements WorkflowTaskInvoker {

    String invokedBpmnProcessId;

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    String invokedProcessVersion;

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      invokedBpmnProcessId = bpmnProcessId;
      invokedProcessVersion = context.getProcessVersion();
      return WorkflowTaskOutcome.completed();

    }

    @Override
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName) {
      return false;
    }

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
     * Story 28b: what the completion payload carries beside the ID variable.
     */
    java.util.Map<String, Object> syncedValues = java.util.Map.of();

    @Override
    public java.util.Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final io.vanillabp.integration.adapter.spi.AggregateSyncMode adapterDefault) {

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

    return new PeaTaskHandler(
        "pea", "test-module", "someTask", bpmnProcessIds, invoker, engine);

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

    // story 48 / GAPS 19: the Process-Engine-API knows no version NUMBER, so the
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

}
