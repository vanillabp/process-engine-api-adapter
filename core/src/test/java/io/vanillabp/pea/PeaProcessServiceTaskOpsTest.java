package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Task-operation edge cases of the {@link PeaProcessService} (story 22) at the
 * Process-Engine-API boundary: the awareness probe (PREFLIGHT_CHECK completion),
 * the phase-one existence check aborting the transaction and the gone-task
 * tolerance of phase two (at-least-once residual).
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaProcessServiceTaskOpsTest {

  /**
   * What a probe is asked about (story 107).
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final PeaProcessService<Object> service = new PeaProcessService<>("pea", engine, engine, engine, engine);

  @Test
  @DisplayName("awarenessOfTask: an open task probes ACTIVE, a gone task UNKNOWN_TO_BPMS")
  public void awarenessProbesViaPreflight() {

    engine.getOpenTaskIds().add("task-1");

    assertEquals(WorkflowAwareness.ACTIVE, service.awarenessOfTask(SCOPE, "42", "task-1"));
    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, service.awarenessOfTask(SCOPE, "42", "task-2"));
    // the probes never advanced anything
    assertTrue(engine.getCompletedTasks().isEmpty());

  }

  @Test
  @DisplayName("phase one aborts the transaction if the task is gone - and passes if it is open")
  public void phaseOneChecksExistence() {

    engine.getOpenTaskIds().add("task-3");

    assertDoesNotThrow(() -> service.completeTaskPhaseOne("mod", "Process", null, new Object(), "task-3"));
    assertDoesNotThrow(() -> service.cancelTaskPhaseOne("mod", "Process", null, new Object(), "task-3", "ERR"));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> service.completeTaskPhaseOne("mod", "Process", null, new Object(), "task-gone"));
    assertTrue(failure.getMessage().contains("task-gone"));
    // the preflight checks never completed anything
    assertTrue(engine.getCompletedTasks().isEmpty());
    assertTrue(engine.getErroredTasks().isEmpty());

  }

  @Test
  @DisplayName("user tasks: awareness probes, phase-one abort and a phase two which reports failures")
  public void userTaskOperations() {

    engine.getOpenTaskIds().add("utask-1");

    assertEquals(WorkflowAwareness.ACTIVE, service.awarenessOfUserTask(SCOPE, "42", "utask-1"));
    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, service.awarenessOfUserTask(SCOPE, "42", "utask-x"));

    assertDoesNotThrow(() -> service.completeUserTaskPhaseOne("mod", "Process", null, new Object(), "utask-1"));
    assertDoesNotThrow(() -> service
        .cancelUserTaskPhaseOne("mod", "Process", null, new Object(), "utask-1", "ERR"));
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> service.completeUserTaskPhaseOne("mod", "Process", null, new Object(), "utask-gone"));
    assertTrue(failure.getMessage().contains("utask-gone"));

    service.completeUserTaskPhaseTwo("mod", "Process", null, "42", "utask-1");
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("utask-2");
    service.cancelUserTaskPhaseTwo("mod", "Process", null, "42", "utask-2", "APPROVAL_WITHDRAWN");
    assertEquals("APPROVAL_WITHDRAWN", engine.getErroredTasks().getFirst().errorCode());

    // repeating both fails now (story 86): whether the user task was finished meanwhile or
    // the engine is unreachable looks the same to this adapter, so the outbox decides
    assertThrows(
        IllegalStateException.class,
        () -> service.completeUserTaskPhaseTwo("mod", "Process", null, "42", "utask-1"));
    assertThrows(
        IllegalStateException.class,
        () -> service.cancelUserTaskPhaseTwo("mod", "Process", null, "42", "utask-2", "X"));

  }

  @Test
  @DisplayName("phase two completes/cancels open tasks, and a failure reaches the outbox (story 86)")
  public void phaseTwoReportsFailuresInsteadOfDroppingThem() {

    engine.getOpenTaskIds().add("task-4");
    service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-4");
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("task-5");
    service.cancelTaskPhaseTwo("mod", "Process", null, "42", "task-5", "PAYMENT_FAILED");
    assertEquals(1, engine.getErroredTasks().size());
    assertEquals("PAYMENT_FAILED", engine.getErroredTasks().getFirst().errorCode());

    // repeating both operations fails now: the API answers the same way whether the task
    // was finished meanwhile or the engine is unreachable, and this adapter must not
    // assume the harmless case - the outbox repeats and finally blocks the entry
    final var completion = assertThrows(
        IllegalStateException.class,
        () -> service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-4"));
    assertTrue(completion.getMessage().contains("task-4"), completion.getMessage());
    assertTrue(completion.getMessage().contains("no typed errors"), completion.getMessage());
    assertNotNull(completion.getCause(), "the engine's answer has to stay readable");

    assertThrows(
        IllegalStateException.class,
        () -> service.cancelTaskPhaseTwo("mod", "Process", null, "42", "task-5", "X"));

    assertEquals(1, engine.getCompletedTasks().size());
    assertEquals(1, engine.getErroredTasks().size());

  }

  @Test
  @DisplayName("An unreachable engine is what the change is for: nothing is dropped silently")
  public void anUnreachableEngineFailsLoudly() {

    engine.getOpenTaskIds().add("task-9");
    engine.failNextCompletionFor("task-9");

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-9"));

    assertTrue(failure.getMessage().contains("Phase two of completing task"), failure.getMessage());
    // the task is still open: the completion did not happen, and now somebody knows
    assertTrue(engine.getOpenTaskIds().contains("task-9"));
    assertTrue(engine.getCompletedTasks().isEmpty());

  }

  @Test
  @DisplayName("The re-dispatch probe is never optimistic - unlike the election's workflow awareness")
  public void redispatchProbeIsNeverOptimistic() {

    // the election answers optimistically because the Process-Engine-API cannot
    // query workflows at all (GAPS 11) - correlation must keep working
    assertEquals(WorkflowAwareness.ACTIVE, service.awarenessOfWorkflow(SCOPE, null, "42"));

    // the START re-dispatch mitigation must NOT be optimistic: skipping a
    // recovered start would LOSE the workflow, whereas proceeding only risks the
    // documented at-least-once duplicate
    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, service.awarenessOfWorkflowForRedispatch(SCOPE, null, "42"));

  }

}
