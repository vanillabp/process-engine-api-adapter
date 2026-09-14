package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Edge cases of the {@link PeaUserTaskHandler}: the notification is
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
    public Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final AggregateSyncMode adapterDefault) {

      return Map.of();

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

    return PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .build();

  }

  /**
   * The handler of a subscription whose module avoids name clashes the given way - the
   * mirror of the service-task side, so neither of the two can start reading the engine's
   * meta entry differently again.
   */
  private PeaUserTaskHandler handler(
      final List<String> bpmnProcessIds,
      final NameClashAvoidance mode) {

    return PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .scoping(TestScoping.of(mode, "test-module"))
        .build();

  }

  @Test
  @DisplayName("A scoped meta entry is routed to the plain process the core is keyed by")
  public void aScopedMetaEntryIsRoutedToThePlainProcess() {

    handler(List.of("OnlyProcess"), NameClashAvoidance.USE_PREFIX)
        .accept(
            new TaskInformation(
                "utask-scoped", Map.of(
                    PeaTaskMeta.BPMN_PROCESS_ID,
                    "test-module"
                        + NameClashAvoidanceSupport.SEPARATOR
                        + "OnlyProcess")),
            Map.of("id", "4711"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);

  }

  @Test
  @DisplayName("Under 'none' the meta entry passes through as the engine spelled it")
  public void underNoneTheMetaEntryPassesThrough() {

    handler(List.of("OnlyProcess", "AnotherProcess"), NameClashAvoidance.NONE)
        .accept(
            new TaskInformation("utask-plain", Map.of(PeaTaskMeta.BPMN_PROCESS_ID, "AnotherProcess")),
            Map.of("id", "4711"));

    assertEquals("AnotherProcess", invoker.invokedBpmnProcessId);

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
