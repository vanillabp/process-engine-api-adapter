package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Task-operation edge cases of the {@link PeaProcessService} at the
 * Process-Engine-API boundary: the awareness probe (PREFLIGHT_CHECK completion),
 * the phase-one existence check aborting the transaction and the gone-task
 * tolerance of phase two (at-least-once residual).
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaProcessServiceTaskOpsTest {

  /**
   * What a probe is asked about.
   */
  private static final WorkflowScope SCOPE = WorkflowScope
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

    assertDoesNotThrow(() -> PhaseOperations.phaseOne(service,
        PhaseOperation.COMPLETE_TASK, "mod", "Process", null, new Object(),
        PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-3")));
    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(service, PhaseOperation.CANCEL_TASK, "mod",
            "Process", null, new Object(), PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "task-3", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-gone")));
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

    assertDoesNotThrow(() -> PhaseOperations.phaseOne(service,
        PhaseOperation.COMPLETE_USER_TASK, "mod", "Process", null, new Object(),
        PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1")));
    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(service, PhaseOperation.CANCEL_USER_TASK, "mod",
            "Process", null, new Object(), PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "utask-1", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_USER_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-gone")));
    assertTrue(failure.getMessage().contains("utask-gone"));

    PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_USER_TASK, "mod", "Process",
        null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1"));
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("utask-2");
    PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_USER_TASK, "mod", "Process",
        null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-2",
            PhaseTwoCall.ARG_BPMN_ERROR_CODE, "APPROVAL_WITHDRAWN"));
    assertEquals("APPROVAL_WITHDRAWN", engine.getErroredTasks().getFirst().errorCode());

    // repeating both fails now: whether the user task was finished meanwhile or
    // the engine is unreachable looks the same to this adapter, so the outbox decides
    assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_USER_TASK, "mod",
            "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1")));
    assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_USER_TASK, "mod",
            "Process", null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "utask-2", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "X")));

  }

  @Test
  @DisplayName("phase two completes/cancels open tasks, and a failure reaches the outbox")
  public void phaseTwoReportsFailuresInsteadOfDroppingThem() {

    engine.getOpenTaskIds().add("task-4");
    PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_TASK, "mod", "Process", null,
        "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-4"));
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("task-5");
    PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_TASK, "mod", "Process", null,
        "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-5",
            PhaseTwoCall.ARG_BPMN_ERROR_CODE, "PAYMENT_FAILED"));
    assertEquals(1, engine.getErroredTasks().size());
    assertEquals("PAYMENT_FAILED", engine.getErroredTasks().getFirst().errorCode());

    // repeating both operations fails now: the API answers the same way whether the task
    // was finished meanwhile or the engine is unreachable, and this adapter must not
    // assume the harmless case - the outbox repeats and finally blocks the entry
    final var completion = assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-4")));
    assertTrue(completion.getMessage().contains("task-4"), completion.getMessage());
    assertTrue(completion.getMessage().contains("no typed errors"), completion.getMessage());
    assertNotNull(completion.getCause(), "the engine's answer has to stay readable");

    assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_TASK, "mod",
            "Process", null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-5",
                PhaseTwoCall.ARG_BPMN_ERROR_CODE, "X")));

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
        () -> PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, "42",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-9")));

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

  @Test
  @DisplayName("This adapter says that it cannot locate workflows, so a migration setup is refused")
  public void workflowsCannotBeLocated() {

    // the optimistic ACTIVE above is right while this is the only configured BPMS and
    // a guess as soon as it is not. Saying so is what makes the platform refuse the
    // second case while it boots, instead of routing operations by list order
    assertFalse(service.canLocateWorkflows());

  }

}
