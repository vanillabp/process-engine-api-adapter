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

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * Task-operation edge cases of the {@link PeaProcessService} at the
 * Process-Engine-API boundary: the awareness probe (PREFLIGHT_CHECK completion),
 * the phase-one existence check aborting the transaction, and phase two reporting a gone
 * task as a failure instead of dropping it (decision 5).
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
    // The probes did not move anything forward.
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

    // The SPI documents this type for a task which no BPMS knows any more. So an
    // application catches the same exception here as when a probe found it out.
    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-gone")));
    assertTrue(failure.getMessage().contains("task-gone"));
    // Cancelling asks the same question about the same task, so the answer is the same.
    assertThrows(
        TaskNotFoundException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.CANCEL_TASK, "mod",
            "Process", null, new Object(), PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "task-gone", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));
    // The preflight checks did not complete anything.
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
        TaskNotFoundException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.COMPLETE_USER_TASK, "mod",
            "Process", null, new Object(),
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-gone")));
    assertTrue(failure.getMessage().contains("utask-gone"));
    assertThrows(
        TaskNotFoundException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.CANCEL_USER_TASK, "mod",
            "Process", null, new Object(), PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID,
                "utask-gone", PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));

    PhaseOperations.phaseTwo(service, PhaseOperation.COMPLETE_USER_TASK, "mod", "Process",
        null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-1"));
    assertEquals(1, engine.getCompletedTasks().size());

    engine.getOpenTaskIds().add("utask-2");
    PhaseOperations.phaseTwo(service, PhaseOperation.CANCEL_USER_TASK, "mod", "Process",
        null, "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-2",
            PhaseTwoCall.ARG_BPMN_ERROR_CODE, "APPROVAL_WITHDRAWN"));
    assertEquals("APPROVAL_WITHDRAWN", engine.getErroredTasks().getFirst().errorCode());

    // Repeating both fails now. To this adapter, a user task which was finished meanwhile
    // looks the same as an engine it cannot reach. So the outbox decides.
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

    // Repeating both operations fails now. The API answers the same way whether the task
    // was finished meanwhile or the engine cannot be reached. This adapter must not assume
    // the harmless case. The outbox repeats the entry and in the end blocks it.
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
  @DisplayName("Canceling a task or a user task raises the error code the deployed model carries")
  public void bothCancelOperationsScopeTheErrorCode() {

    // The errorCode of a bpmn:error gets the prefix like every other identifier of the
    // module. So both cancel operations have to translate the code the application named.
    // The engine accepts an unknown code without a word. So with a plain code the workflow
    // would skip the error path it should take.
    final var scoped = new PeaProcessService<Object>(
        "pea", engine, engine, engine, engine, null, TestCollaborators.of(
            new PeaDeploymentServiceTest.PermissiveInvoker(),
            TestScoping.of(NameClashAvoidance.USE_PREFIX, "mod")));
    final var prefix = "mod"
        + NameClashAvoidanceSupport.SEPARATOR;

    engine.getOpenTaskIds().add("task-10");
    PhaseOperations.phaseTwo(scoped, PhaseOperation.CANCEL_TASK, "mod", "Process", null,
        "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "task-10",
            PhaseTwoCall.ARG_BPMN_ERROR_CODE, "PAYMENT_FAILED"));

    engine.getOpenTaskIds().add("utask-10");
    PhaseOperations.phaseTwo(scoped, PhaseOperation.CANCEL_USER_TASK, "mod", "Process", null,
        "42", PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "utask-10",
            PhaseTwoCall.ARG_BPMN_ERROR_CODE, "APPROVAL_WITHDRAWN"));

    assertEquals(2, engine.getErroredTasks().size());
    assertEquals(prefix
        + "PAYMENT_FAILED", engine.getErroredTasks().get(0).errorCode());
    assertEquals(prefix
        + "APPROVAL_WITHDRAWN", engine.getErroredTasks().get(1).errorCode());

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
    // The task is still open. The completion did not happen, and now somebody knows it.
    assertTrue(engine.getOpenTaskIds().contains("task-9"));
    assertTrue(engine.getCompletedTasks().isEmpty());

  }

  @Test
  @DisplayName("The re-dispatch probe is never optimistic - unlike the election's workflow awareness")
  public void redispatchProbeIsNeverOptimistic() {

    // The election answers optimistically, because the Process-Engine-API cannot query
    // workflows at all (GAPS 11). Correlation has to keep working.
    assertEquals(WorkflowAwareness.ACTIVE, service.awarenessOfWorkflow(SCOPE, null, "42"));

    // The re-dispatch of a START must NOT be optimistic. Skipping a recovered start would
    // LOSE the workflow. Going on only risks the documented duplicate of at-least-once
    // delivery.
    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, service.awarenessOfWorkflowForRedispatch(SCOPE, null, "42"));

  }

  @Test
  @DisplayName("This adapter says that it cannot locate workflows, so a migration setup is refused")
  public void workflowsCannotBeLocated() {

    // The optimistic ACTIVE above is right while this is the only configured BPMS. As soon
    // as there is another one, it is a guess. Because the adapter says so, the platform
    // refuses the second case at boot, instead of routing operations by list order.
    assertFalse(service.canLocateWorkflows());

  }

}
