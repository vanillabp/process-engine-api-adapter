package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * The Process-Engine-API answers with futures, so every operation of this adapter waits. A
 * waiting thread can be interrupted. An application which shuts down does that to the
 * thread which dispatches the outbox. Then two things have to hold, and neither shows in a
 * healthy system. First, the interrupt has to survive the call, because a thread whose
 * interrupt was swallowed cannot be stopped any more. Second, the operation must not look
 * like it happened.
 * <p>
 * The awareness probes matter most. An interrupted probe which answered
 * <code>UNKNOWN_TO_BPMS</code> would tell the election that this BPMS does not know the
 * workflow. Then the next adapter would start or complete it a second time.
 * <p>
 * The engine here never answers. Its futures stay open, so the wait is real, and a thread
 * which is already interrupted ends it at once.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaInterruptedOperationsTest {

  /**
   * What a probe is asked about.
   */
  private static final WorkflowScope SCOPE = WorkflowScope
      .of("test-module", "TestProcess");

  private ServiceTaskCompletionApi serviceTasks;

  private UserTaskCompletionApi userTasks;

  private CorrelationApi correlation;

  private SignalApi signals;

  private StartProcessApi starts;

  private PeaProcessService<Object> service;

  @BeforeEach
  public void anEngineWhichNeverAnswers() {

    // Every call answers with a future which never completes, so the wait is real.
    final var neverAnswers = Mockito.withSettings().defaultAnswer(invocation -> new CompletableFuture<>());
    serviceTasks = Mockito.mock(ServiceTaskCompletionApi.class, neverAnswers);
    userTasks = Mockito.mock(UserTaskCompletionApi.class, neverAnswers);
    correlation = Mockito.mock(CorrelationApi.class, neverAnswers);
    signals = Mockito.mock(SignalApi.class, neverAnswers);
    starts = Mockito.mock(StartProcessApi.class, neverAnswers);

    service = new PeaProcessService<>(
        "pea", starts, serviceTasks, userTasks, correlation);
    service.setSignalApi(signals);

  }

  @AfterEach
  public void clearTheInterruptFlag() {

    Thread.interrupted();

  }

  private void assertFailsNaming(
      final String expectedInMessage,
      final Runnable operation) {

    Thread.currentThread().interrupt();
    final var failure = assertThrows(IllegalStateException.class, operation::run);
    assertTrue(failure.getMessage().contains(expectedInMessage), failure.getMessage());
    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    Thread.interrupted();

  }

  @Test
  @DisplayName("An interrupted task operation fails naming the task, and the interrupt survives")
  public void anInterruptedTaskOperationFailsAndKeepsTheInterrupt() {

    assertFailsNaming("task-1",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-1")));
    assertFailsNaming("task-1",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_TASK, "mod",
            "Process", null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-1",
                PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));
    // Phase one only asks whether the task is still there, and it waits as well.
    assertFailsNaming(
        "task-1",
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-1")));

  }

  @Test
  @DisplayName("An interrupted user-task operation fails naming the task, and the interrupt survives")
  public void anInterruptedUserTaskOperationFailsAndKeepsTheInterrupt() {

    assertFailsNaming("utask-1",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_USER_TASK, "mod",
            "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1")));
    assertFailsNaming(
        "utask-1",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_USER_TASK, "mod",
            "Process", null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "utask-1", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));
    assertFailsNaming(
        "utask-1",
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_USER_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1")));

  }

  @Test
  @DisplayName("An interrupted awareness probe reports BPMS_UNAVAILABLE, never 'not mine'")
  public void anInterruptedProbeReportsBpmsUnavailable() {

    Thread.currentThread().interrupt();
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, service.awarenessOfTask(SCOPE, "42", "task-2"));
    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    Thread.interrupted();

    Thread.currentThread().interrupt();
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, service.awarenessOfUserTask(SCOPE, "42", "utask-2"));
    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");

  }

  @Test
  @DisplayName("An interrupted broadcast fails instead of consuming the outbox entry silently")
  public void anInterruptedBroadcastFails() {

    // Returning normally would mark the entry as done, although nothing was sent. And a
    // signal nobody receives leaves a workflow waiting for ever.
    assertFailsNaming("OrderCancelled",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.SEND_SIGNAL, "mod",
            "Process", null, null,
            PhaseOperations.args(PhaseTwoCall.ARG_SIGNAL_NAME, "OrderCancelled")));

  }

  @Test
  @DisplayName("An interrupted start by message fails instead of consuming the outbox entry")
  public void anInterruptedStartByMessageFails() {

    // This one used to return normally. The outbox entry was marked as done, the router
    // reported success, and the application kept an aggregate whose workflow never
    // started. This is the worst of the interrupts, because nothing is left to notice it.
    assertFailsNaming(
        "OrderPlaced",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.START_WORKFLOW_BY_MESSAGE,
            "mod", "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "OrderPlaced")));

  }

  @Test
  @DisplayName("An interrupted start fails naming the BPMN process")
  public void anInterruptedStartFails() {

    assertFailsNaming(
        "Process",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.START_WORKFLOW, "mod",
            "Process", null, "42", Map.of()));

  }

  @Test
  @DisplayName("An interrupted correlation fails naming the message")
  public void anInterruptedCorrelationFails() {

    assertFailsNaming(
        "OrderApproved",
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.CORRELATE_MESSAGE, "mod",
            "Process", null, "42", PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "OrderApproved", PhaseTwoCall.ARG_CORRELATION_ID, "42")));

  }

}
