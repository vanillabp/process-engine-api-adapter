package io.vanillabp.pea.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Edge cases of the {@link PeaUserTaskHandler} (story 24): the notification is
 * OPTIONAL (skipped without a handler), routing failures and handler defects are
 * logged loudly but never break the user task itself, and a TaskException in a
 * notification handler is a defect.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaUserTaskHandlerTest {

  static class RecordingInvoker implements WorkflowTaskInvoker {

    boolean handlerExists = true;

    WorkflowTaskOutcome outcome = WorkflowTaskOutcome.completed();

    String invokedBpmnProcessId;

    TaskEvent.Event invokedEvent;

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

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return handlerExists;

    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      invokedBpmnProcessId = bpmnProcessId;
      invokedEvent = context.getTaskEvent();
      return outcome;

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
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return "id";

    }

  }

  private final RecordingInvoker invoker = new RecordingInvoker();

  private PeaUserTaskHandler handler(
      final List<String> bpmnProcessIds) {

    return new PeaUserTaskHandler("pea", "test-module", "approve", bpmnProcessIds, invoker);

  }

  @Test
  @DisplayName("A delivered user task notifies the handler with CREATED")
  public void deliveryNotifiesWithCreated() {

    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("utask-1", Map.of()),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);
    assertEquals(TaskEvent.Event.CREATED, invoker.invokedEvent);

  }

  @Test
  @DisplayName("Without a handler the notification is skipped silently (optional)")
  public void withoutHandlerSkipped() {

    invoker.handlerExists = false;

    handler(List.of("OnlyProcess"))
        .accept(
            new TaskInformation("utask-2", Map.of()),
            Map.of("id", "4711"));

    assertNull(invoker.invokedBpmnProcessId, "the handler must not be invoked");

  }

  @Test
  @DisplayName("Defects never break the user task: missing variable, ambiguous routing, TaskException")
  public void defectsAreLoggedNotThrown() {

    // missing aggregate-ID variable
    handler(List.of("OnlyProcess"))
        .accept(new TaskInformation("utask-3", Map.of()), Map.of("unrelated", "x"));

    // ambiguous routing without the meta entry
    handler(List.of("ProcessA", "ProcessB"))
        .accept(new TaskInformation("utask-4", Map.of()), Map.of("id", "4711"));

    // a TaskException outcome is a defect - logged, not thrown
    invoker.outcome = WorkflowTaskOutcome.bpmnError("SOME_ERROR", null);
    handler(List.of("OnlyProcess"))
        .accept(new TaskInformation("utask-5", Map.of()), Map.of("id", "4711"));

  }

}
