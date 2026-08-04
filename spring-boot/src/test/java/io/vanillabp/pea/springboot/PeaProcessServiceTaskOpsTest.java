package io.vanillabp.pea.springboot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final PeaProcessService<Object> service = new PeaProcessService<>("pea", engine, engine);

  @Test
  @DisplayName("awarenessOfTask: an open task probes ACTIVE, a gone task UNKNOWN_TO_BPMS")
  public void awarenessProbesViaPreflight() {

    engine.getOpenTaskIds().add("task-1");

    assertEquals(WorkflowAwareness.ACTIVE, service.awarenessOfTask("42", "task-1"));
    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, service.awarenessOfTask("42", "task-2"));
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
  @DisplayName("phase two completes/cancels open tasks and tolerates gone ones (at-least-once residual)")
  public void phaseTwoToleratesGoneTasks() {

    engine.getOpenTaskIds().add("task-4");
    service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-4");
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("task-5");
    service.cancelTaskPhaseTwo("mod", "Process", null, "42", "task-5", "PAYMENT_FAILED");
    assertEquals(1, engine.getErroredTasks().size());
    assertEquals("PAYMENT_FAILED", engine.getErroredTasks().getFirst().errorCode());

    // stale outbox entries: both operations are warned no-ops, never errors
    assertDoesNotThrow(() -> service.completeTaskPhaseTwo("mod", "Process", null, "42", "task-4"));
    assertDoesNotThrow(() -> service.cancelTaskPhaseTwo("mod", "Process", null, "42", "task-5", "X"));
    assertEquals(1, engine.getCompletedTasks().size());
    assertEquals(1, engine.getErroredTasks().size());

  }

}
