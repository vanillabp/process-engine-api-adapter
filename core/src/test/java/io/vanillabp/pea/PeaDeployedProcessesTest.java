package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;

/**
 * What an extension may ask the adapter about the models it deployed: a process by its
 * workflow module and BPMN process id, and its user tasks by the BPMN element id a modeller
 * wrote and by the external form reference the adapter subscribes them under.
 * <p>
 * The two indexes are built from the recorded models rather than being filled on the side, so
 * the cases which matter are the ones where the models change: a second process bringing the
 * same form reference, and a redeployment which drops a user task.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaDeployedProcessesTest {

  private static final String MODULE = "test-module";

  private static PeaBpmnModel model(
      final String bpmnProcessId,
      final String processName,
      final List<BpmnTaskSpec> userTasks) {

    return new PeaBpmnModel(
        bpmnProcessId
            + ".bpmn", bpmnProcessId
                .getBytes(StandardCharsets.UTF_8), bpmnProcessId, processName, List.of(), userTasks);

  }

  @Test
  @DisplayName("A deployed process answers with its name and with its user tasks")
  public void aProcessAnswersItsNameAndItsUserTasks() {

    final var testee = new PeaDeployedProcesses();
    testee.record(
        MODULE,
        model("Approval", "Loan approval", List.of(
            BpmnTaskSpec.userTask("t_decide", "decideForm", "Decide about the loan"))),
        "deployment-1");

    final var process = testee.deployedVersionOf(MODULE, "Approval");
    assertEquals("Approval", process.bpmnProcessId());
    assertEquals("Loan approval", process.processName());
    assertEquals("deployment-1", process.deploymentKey());

    final var userTask = process.userTaskByElementId("t_decide");
    assertEquals("decideForm", userTask.taskDefinition());
    assertEquals("Decide about the loan", userTask.name());
    assertEquals(List.of(userTask), process.userTasksByFormReference("decideForm"));
    assertNull(process.userTaskByElementId("t_unknown"));
    assertTrue(process.userTasksByFormReference("unknownForm").isEmpty());

  }

  @Test
  @DisplayName("A user task is found by its element id and by its form reference")
  public void aUserTaskIsFoundByBothKeys() {

    final var testee = new PeaDeployedProcesses();
    testee.record(
        MODULE,
        model("Approval", "Loan approval", List.of(
            BpmnTaskSpec.userTask("t_decide", "decideForm", "Decide about the loan"),
            BpmnTaskSpec.userTask("t_inform", "informForm", null))),
        "deployment-1");

    assertEquals(
        List.of("Approval"),
        testee
            .byUserTaskElementId(MODULE, "t_inform")
            .stream()
            .map(PeaDeployedProcesses.DeployedProcess::bpmnProcessId)
            .toList());
    assertEquals(
        List.of("Approval"),
        testee
            .byUserTaskFormReference(MODULE, "decideForm")
            .stream()
            .map(PeaDeployedProcesses.DeployedProcess::bpmnProcessId)
            .toList());
    assertTrue(
        testee.byUserTaskElementId("other-module", "t_decide").isEmpty(),
        "the index is asked per workflow module");
    assertTrue(testee.byUserTaskFormReference(MODULE, "noSuchForm").isEmpty());

  }

  @Test
  @DisplayName("Two processes sharing a form reference are both answered")
  public void aFormReferenceIsNotUniqueInsideAModule() {

    final var testee = new PeaDeployedProcesses();
    testee.record(
        MODULE,
        model("Approval", "Loan approval", List.of(
            BpmnTaskSpec.userTask("t_decide", "sharedForm", "Decide"))),
        "deployment-1");
    testee.record(
        MODULE,
        model("Renewal", "Loan renewal", List.of(
            BpmnTaskSpec.userTask("t_decide", "sharedForm", "Decide again"))),
        "deployment-1");

    assertEquals(
        List.of("Approval", "Renewal"),
        testee
            .byUserTaskFormReference(MODULE, "sharedForm")
            .stream()
            .map(PeaDeployedProcesses.DeployedProcess::bpmnProcessId)
            .sorted()
            .toList(),
        "a form reference may be shown by more than one process of a module");
    assertEquals(
        2,
        testee.byUserTaskElementId(MODULE, "t_decide").size(),
        "nothing keeps two processes from using the same element id either");

  }

  @Test
  @DisplayName("A redeployment replaces what the index says about a process")
  public void aRedeploymentReplacesTheIndex() {

    final var testee = new PeaDeployedProcesses();
    testee.record(
        MODULE,
        model("Approval", "Loan approval", List.of(
            BpmnTaskSpec.userTask("t_decide", "decideForm", "Decide"))),
        "deployment-1");
    testee.record(
        MODULE,
        model("Approval", "Loan approval v2", List.of(
            BpmnTaskSpec.userTask("t_check", "checkForm", "Check"))),
        "deployment-2");

    assertTrue(
        testee.byUserTaskElementId(MODULE, "t_decide").isEmpty(),
        "the user task the new model does not carry any more is gone from the index");
    assertTrue(testee.byUserTaskFormReference(MODULE, "decideForm").isEmpty());
    assertEquals(1, testee.byUserTaskFormReference(MODULE, "checkForm").size());
    assertEquals("Loan approval v2", testee.deployedVersionOf(MODULE, "Approval").processName());

  }

  @Test
  @DisplayName("A user task without a form reference is still found by its element id")
  public void aUserTaskWithoutAFormReferenceIsIndexedByItsElementIdOnly() {

    final var testee = new PeaDeployedProcesses();
    testee.record(
        MODULE,
        model("Approval", null, List.of(new BpmnTaskSpec("t_decide", null, true, "Decide"))),
        "deployment-1");

    assertEquals(1, testee.byUserTaskElementId(MODULE, "t_decide").size());
    assertNull(testee.deployedVersionOf(MODULE, "Approval").processName());

  }

  @Test
  @DisplayName("The definition id is what the viewer API addresses a process by")
  public void aProcessIsAlsoFoundByItsDefinitionId() {

    final var testee = new PeaDeployedProcesses();
    testee.record(MODULE, model("Approval", "Loan approval", List.of()), "deployment-1");

    assertEquals(
        "Approval",
        testee.byDefinitionId(PeaDeployedProcesses.definitionId(MODULE, "Approval")).bpmnProcessId());
    assertNull(testee.byDefinitionId("no-such|definition"));

  }

}
