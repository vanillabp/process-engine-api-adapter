package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;

/**
 * This adapter gives the core no {@link OpenTaskProbe}, and these tests keep that visible.
 * The core can work out a cancellation for a BPMS which reports none. But it needs a probe
 * which tells "the engine does not have this task" apart from "the engine did not answer".
 * The Process-Engine-API fails every command with one untyped exception. So the adapter can
 * never say the first of the two. See entry 27 in {@code GAPS.md} and decision 13 in the
 * repository's {@code DECISIONS.md}.
 * <p>
 * A probe which always answered "cannot say" would cost a round trip per open task and
 * report nothing. A probe which read a failure as "gone" would cancel the open work of a
 * workflow whenever the engine has a short outage. So the adapter supplies no probe at all.
 * A later change which adds one has to get past the tests below first.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaOpenTaskProbeTest {

  /**
   * Records what the adapter asks the core to work out. A real core reads its delivery log
   * here and probes the other open tasks of the same workflow. The double is there to show
   * that the call never arrives.
   */
  static class RecordingInvoker implements WorkflowTaskInvoker {

    final List<String> reportedWakeUps = new ArrayList<>();

    @Override
    public void reportTasksTheBpmsNoLongerHas(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext wakeUp,
        final OpenTaskProbe probe) {

      reportedWakeUps.add(bpmnProcessId);

    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      return WorkflowTaskOutcome.completed();

    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return true;

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
        final String propertyName) {

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

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final RecordingInvoker invoker = new RecordingInvoker();

  private PeaTaskHandler asyncTaskHandler() {

    return PeaTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .taskDefinition("someTask")
        .bpmnProcessIds(List.of("OnlyProcess"))
        .workflowTaskInvoker(invoker)
        .serviceTaskCompletionApi(engine)
        .build();

  }

  private PeaUserTaskHandler userTaskHandler() {

    return PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(List.of("OnlyProcess"))
        .workflowTaskInvoker(invoker)
        .build();

  }

  @Test
  @DisplayName("A delivered async task asks the core to work no cancellation out")
  public void anAsyncDeliveryReportsNothing() {

    engine
        .getOpenTaskIds()
        .add("task-1");

    asyncTaskHandler()
        .accept(
            new TaskInformation("task-1", Map.of()),
            Map.of("id", "4711"));

    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-1")),
        engine.getCompletedTasks(),
        "the delivery itself has to run as it always did");
    assertTrue(
        invoker.reportedWakeUps.isEmpty(),
        "an adapter which cannot say 'gone' supplies no probe, so no wake-up is reported: "
            + invoker.reportedWakeUps);

  }

  @Test
  @DisplayName("A delivered user task asks the core to work no cancellation out either")
  public void aUserTaskDeliveryReportsNothing() {

    userTaskHandler()
        .accept(
            new TaskInformation("utask-1", Map.of()),
            Map.of("id", "4711"));

    assertTrue(
        invoker.reportedWakeUps.isEmpty(),
        "a user-task notification is a wake-up like any other and reports nothing: "
            + invoker.reportedWakeUps);

  }

  @Test
  @DisplayName("Even a task the engine withdrew reaches the core through no probe")
  public void aWithdrawnUserTaskReportsNothing() {

    // This is the one moment this adapter does learn that a task is gone: the engine
    // pushes the termination of a task it had delivered. It carries no payload, so no
    // workflow aggregate. And no SPI call takes a single gone task. So it only reaches the
    // observers. Entry 27 of GAPS.md says what would have to change.
    userTaskHandler()
        .terminated(new TaskInformation("utask-2", Map.of(TaskInformation.REASON, "delete")));

    assertTrue(
        invoker.reportedWakeUps.isEmpty(),
        "a termination is not a wake-up and must not turn into one: "
            + invoker.reportedWakeUps);

  }

}
