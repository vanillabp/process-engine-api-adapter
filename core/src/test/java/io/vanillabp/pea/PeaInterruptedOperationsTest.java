package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * The Process-Engine-API answers with futures, so every operation of this adapter waits.
 * A waiting thread can be interrupted - which is what an application shutting down does
 * to the thread dispatching the outbox - and two things have to hold then, neither of
 * which shows in a green system: the interrupt has to survive the call, because a thread
 * whose interrupt was swallowed cannot be stopped any more, and the operation must not
 * look like it happened.
 * <p>
 * The awareness probes are the sharper half. An interrupted probe answering
 * <code>UNKNOWN_TO_BPMS</code> would tell the election that this BPMS does not know the
 * workflow, and the next adapter would start or complete it a second time.
 * <p>
 * The engine here never answers: its futures stay open, so the wait is a real one and an
 * already interrupted thread ends it immediately.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaInterruptedOperationsTest {

  private ServiceTaskCompletionApi serviceTasks;

  private UserTaskCompletionApi userTasks;

  private CorrelationApi correlation;

  private SignalApi signals;

  private PeaProcessService<Object> service;

  @BeforeEach
  public void anEngineWhichNeverAnswers() {

    // every call answers with a future which never completes, so the wait is a real one
    final var neverAnswers = Mockito.withSettings().defaultAnswer(invocation -> new CompletableFuture<>());
    serviceTasks = Mockito.mock(ServiceTaskCompletionApi.class, neverAnswers);
    userTasks = Mockito.mock(UserTaskCompletionApi.class, neverAnswers);
    correlation = Mockito.mock(CorrelationApi.class, neverAnswers);
    signals = Mockito.mock(SignalApi.class, neverAnswers);

    service = new PeaProcessService<>(
        "pea", Mockito.mock(StartProcessApi.class), serviceTasks, userTasks, correlation);
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

    assertFailsNaming("task-1", () -> service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-1"));
    assertFailsNaming("task-1", () -> service.cancelTaskPhaseTwo("mod", "Process", null, "42", "task-1", "ERR"));
    // phase one only asks whether the task is still there, and it waits as well
    assertFailsNaming(
        "task-1",
        () -> service.completeTaskPhaseOne("mod", "Process", null, new Object(), "task-1"));

  }

  @Test
  @DisplayName("An interrupted user-task operation fails naming the task, and the interrupt survives")
  public void anInterruptedUserTaskOperationFailsAndKeepsTheInterrupt() {

    assertFailsNaming("utask-1", () -> service.completeUserTaskPhaseTwo("mod", "Process", null, "42", "utask-1"));
    assertFailsNaming(
        "utask-1",
        () -> service.cancelUserTaskPhaseTwo("mod", "Process", null, "42", "utask-1", "ERR"));
    assertFailsNaming(
        "utask-1",
        () -> service.completeUserTaskPhaseOne("mod", "Process", null, new Object(), "utask-1"));

  }

  @Test
  @DisplayName("An interrupted awareness probe reports BPMS_UNAVAILABLE, never 'not mine'")
  public void anInterruptedProbeReportsBpmsUnavailable() {

    Thread.currentThread().interrupt();
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, service.awarenessOfTask("42", "task-2"));
    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    Thread.interrupted();

    Thread.currentThread().interrupt();
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, service.awarenessOfUserTask("42", "utask-2"));
    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");

  }

  @Test
  @DisplayName("An interrupted broadcast fails instead of consuming the outbox entry silently")
  public void anInterruptedBroadcastFails() {

    // returning normally would mark the entry done although nothing was broadcast, and
    // a signal nobody receives is a workflow waiting forever
    assertFailsNaming("OrderCancelled", () -> service.sendSignalPhaseTwo("mod", "Process", "OrderCancelled"));

  }

  @Test
  @DisplayName("An interrupted correlation fails naming the message")
  public void anInterruptedCorrelationFails() {

    assertFailsNaming(
        "OrderApproved",
        () -> service.correlateMessagePhaseTwo("mod", "Process", null, "42", "OrderApproved", "42"));

  }

}
