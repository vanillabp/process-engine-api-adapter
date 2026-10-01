package io.vanillabp.pea;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * What a boot says about a user task which reaches no <code>@WorkflowTask</code> method: one
 * line per BPMN process the application claims, at INFO, and nothing at all for a process it
 * does not claim. The boot goes on in every case, which is where this engine parts with
 * Camunda 8.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaUnservedUserTasksTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  /**
   * The core of an application whose <code>@WorkflowTask</code> methods are named after the
   * given task definitions and element ids, and which claims every process it is asked about.
   */
  static class CoreWithMethodsFor extends PeaDeploymentServiceTest.PermissiveInvoker {

    private final List<String> served;

    private final boolean claimsTheProcess;

    CoreWithMethodsFor(
        final boolean claimsTheProcess,
        final String... served) {

      this.claimsTheProcess = claimsTheProcess;
      this.served = List.of(served);

    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return served.contains(taskDefinitionOrActivityId);

    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      if (claimsTheProcess) {
        return "loanId";
      }
      // what the core answers about a process no @WorkflowService class registered
      throw new IllegalStateException("No @WorkflowService class is registered for BPMN process");

    }

  }

  /**
   * @param userTasks The user tasks of the process, as BPMN
   * @return The file carrying them
   */
  private static ByteArrayInputStream model(
      final String userTasks) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, userTasks);
    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));

  }

  private static ByteArrayInputStream aUserTaskWithAFormReference() {

    return model("""
            <bpmn:userTask id="approve" name="Approve the loan">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="approveTheLoan" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

  private static ByteArrayInputStream aUserTaskWithoutAFormReference() {

    return model("""
            <bpmn:userTask id="approve" name="Approve the loan">
              <bpmn:extensionElements>
                <zeebe:userTask />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

  /**
   * Runs the stages a model passes through while a workflow module boots, and answers what they
   * wrote.
   */
  private String wire(
      final CapturedOutput output,
      final PeaDeploymentServiceTest.PermissiveInvoker core,
      final ByteArrayInputStream bpmn) {

    final var service = new PeaDeploymentService("pea", engine, TestCollaborators.of(core), engine, engine);
    final var before = output.getAll().length();
    final var read = service.readBpmn(MODULE, FILE, bpmn, true);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, read.get(0).getValue());
    service.wireBpmn(MODULE, FILE, PROCESS, context.getModels().get(0), context);
    return output.getAll().substring(before);

  }

  /**
   * What stands in front of the given text on its own line, which is where the level of a log
   * line is.
   */
  private static String theLineCarrying(
      final String logged,
      final String text) {

    final var at = logged.indexOf(text);
    Assertions.assertTrue(at >= 0, () -> "'%s' was never written: %s".formatted(text, logged));
    return logged.substring(logged.lastIndexOf('\n', at) + 1, at);

  }

  @Test
  @DisplayName("A form reference no method names is reported, with both ways to wire a method")
  public void theFormReferenceNoMethodNamesIsReported(
      final CapturedOutput output) {

    final var logged = wire(
        output,
        new CoreWithMethodsFor(true, "somethingElse"),
        aUserTaskWithAFormReference());

    Assertions
        .assertTrue(
            logged.contains("user task 'approve' (named 'Approve the loan' in the model)"),
            () -> "the element and what a modeller recognises it by: "
                + logged);
    Assertions
        .assertTrue(
            logged.contains("whose external form reference is 'approveTheLoan'"),
            () -> "the reference, which is the task definition of a user task here: "
                + logged);
    Assertions
        .assertTrue(
            logged.contains("@WorkflowTask(taskDefinition = \"approveTheLoan\")") && logged
                .contains("@WorkflowTask(id = \"approve\")"),
            () -> "both keys a method may be wired by, because either of them serves it: "
                + logged);
    Assertions
        .assertTrue(
            logged.contains("the delivery is dropped again"),
            () -> "and that the engine does offer this task, which is what tells it from one "
                + "without a reference: "
                + logged);

  }

  @Test
  @DisplayName("The line is an INFO: the model may be meant that way and the boot goes on")
  public void theLineIsAnInfo(
      final CapturedOutput output) {

    final var logged = wire(
        output,
        new CoreWithMethodsFor(true, "somethingElse"),
        aUserTaskWithAFormReference());

    final var level = theLineCarrying(logged, "BPMN process 'LoanApproval' of workflow module");
    Assertions.assertTrue(level.contains("INFO"), () -> "an INFO and not a warning: "
        + level);
    Assertions
        .assertTrue(
            logged.contains("wired 0 task(s) of BPMN process 'LoanApproval'"),
            () -> "and the wiring ran to its end, so nothing was refused: "
                + logged);

  }

  @Test
  @DisplayName("A user task naming no external form reference is reported, and the way out is the reference")
  public void aUserTaskWithoutAReferenceIsReported(
      final CapturedOutput output) {

    final var logged = wire(
        output,
        new CoreWithMethodsFor(true, "approveTheLoan", "approve"),
        aUserTaskWithoutAFormReference());

    Assertions
        .assertTrue(
            logged.contains("which names no external form reference"),
            () -> "the finding, although a method of this application would be there to serve it: "
                + logged);
    Assertions
        .assertTrue(
            logged.contains("Set 'External form reference' (zeebe:formDefinition externalReference)"),
            () -> "what to change in the model, which is the only way to reach such a task: "
                + logged);
    Assertions
        .assertFalse(
            logged.contains("@WorkflowTask(taskDefinition ="),
            () -> "and not a task definition the model never named: "
                + logged);

  }

  @Test
  @DisplayName("A user task a method serves is not reported, by either of its two keys")
  public void aServedUserTaskIsNotReported(
      final CapturedOutput output) {

    final var byTheReference = wire(
        output,
        new CoreWithMethodsFor(true, "approveTheLoan"),
        aUserTaskWithAFormReference());
    Assertions
        .assertFalse(
            byTheReference.contains("reach no @WorkflowTask method"),
            () -> "which is the ordinary case and says nothing: "
                + byTheReference);

    final var byTheElementId = wire(
        output,
        new CoreWithMethodsFor(true, "approve"),
        aUserTaskWithAFormReference());
    Assertions
        .assertFalse(
            byTheElementId.contains("reach no @WorkflowTask method"),
            () -> "the element id is the second key a method is wired by, and asking by the "
                + "reference alone would report a task which is served: "
                + byTheElementId);

  }

  @Test
  @DisplayName("A process no @WorkflowService class claims is not reported about")
  public void anUnclaimedProcessSaysNothing(
      final CapturedOutput output) {

    final var logged = wire(
        output,
        new CoreWithMethodsFor(false, "somethingElse"),
        aUserTaskWithAFormReference());

    Assertions
        .assertFalse(
            logged.contains("reach no @WorkflowTask method"),
            () -> "no method of this application was meant to serve its tasks, so there is nothing "
                + "to ask of the reader: "
                + logged);

  }

  @Test
  @DisplayName("A user task without a form reference becomes no spec and no subscription")
  public void aUserTaskWithoutAReferenceIsNoSpec() {

    final var service = new PeaDeploymentService(
        "pea", engine, TestCollaborators.of(new CoreWithMethodsFor(true, "approve")), engine, engine);

    final var model = service
        .readBpmn(MODULE, FILE, aUserTaskWithoutAFormReference(), true)
        .get(0)
        .getValue();

    Assertions
        .assertTrue(
            model.userTasks().isEmpty(),
            () -> "there is no name to subscribe under, so such a task is no task of the core's");
    Assertions.assertEquals(1, model.userTasksWithoutAFormReference().size());
    Assertions.assertEquals("approve", model.userTasksWithoutAFormReference().get(0).activityId());
    Assertions
        .assertEquals(
            "Approve the loan",
            model.userTasksWithoutAFormReference().get(0).name(),
            () -> "carrying the name the modeller wrote, because the report names the element the "
                + "way a modeller finds it");
    Assertions
        .assertNull(
            model.userTasksWithoutAFormReference().get(0).taskDefinition(),
            () -> "and no task definition, because the model names none");

  }

}
