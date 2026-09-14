package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskType;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * Which subscriptions a workflow module opens for a BPMN process id it DECLARES without
 * deploying a model under it - the old id of a renamed process.
 * <p>
 * The whole point of those subscriptions is the name a task carries. Under
 * <code>use-prefix</code> a task definition is deployed as
 * <code>&lt;module&gt;__&lt;process&gt;__&lt;task&gt;</code>, so the tasks of the workflows
 * under the old id are named after the OLD id and no subscription of the deployed processes
 * asks for them; under every other mode the same task is named like any other and there is
 * nothing to open. Both cases are here, and so is the one an application cannot be helped
 * with: a method wired to a BPMN element id names no task definition, and a name cannot be
 * composed from a model this application no longer has.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaDeclaredProcessSubscriptionsTest {

  private static final String MODULE = "test-module";

  private static final String OLD_ID = "order_approval";

  private static final String DEPLOYED_ID = "OrderApproval";

  private static final String TASK_DEFINITION = "approve";

  private static final String SCOPED_NAME = "test-module__order_approval__approve";

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final RecordingCore core = new RecordingCore();

  private final PeaDeployedProcesses deployedProcesses = new PeaDeployedProcesses();

  /**
   * A core which declares the given task definitions per BPMN process id nothing was
   * deployed under, answers the payload variables a method reads and records where a
   * delivery was routed.
   */
  static class RecordingCore extends PeaDeploymentServiceTest.PermissiveInvoker {

    Map<String, Collection<String>> declaredWithoutAModel = Map.of();

    /** The <code>&#64;TaskParam</code> names per BPMN process id. */
    Map<String, Collection<String>> taskParameters = Map.of();

    String invokedBpmnProcessId;

    @Override
    public Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
        final String workflowModuleId) {

      return MODULE.equals(workflowModuleId)
          ? declaredWithoutAModel
          : Map.of();

    }

    @Override
    public Collection<String> taskParameterNames(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return taskParameters.getOrDefault(bpmnProcessId, List.of());

    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context) {

      invokedBpmnProcessId = bpmnProcessId;
      return WorkflowTaskOutcome.completed();

    }

  }

  private PeaDeploymentService serviceScopedBy(
      final NameClashAvoidance mode) {

    return new PeaDeploymentService(
        "pea", engine, TestCollaborators.of(core, TestScoping.of(mode, MODULE)), engine, engine, deployedProcesses);

  }

  /**
   * A processing context holding one deployed model which serves the task definition under
   * its own (unprefixed) name.
   */
  private static PeaProcessingContext aDeployedModelServing(
      final String taskDefinition) {

    final var context = new PeaProcessingContext(MODULE);
    context
        .getModels()
        .add(new PeaBpmnModel(
            "orders.bpmn", "<bpmn/>".getBytes(StandardCharsets.UTF_8), DEPLOYED_ID, List
                .of(new BpmnTaskSpec("Activity_approve", taskDefinition))));
    return context;

  }

  @Test
  @DisplayName("Prefixed task definitions get a subscription per name the old id's tasks carry")
  public void prefixedTaskDefinitionsGetTheirOwnSubscriptions(
      final CapturedOutput output) {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    final var service = serviceScopedBy(NameClashAvoidance.USE_PREFIX);
    final var context = new PeaProcessingContext(MODULE);

    service.startWorkflowProcessing(MODULE, context);

    assertEquals(
        List.of(SCOPED_NAME, SCOPED_NAME),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .toList(),
        "the tasks of the old id carry that name and nothing else asks for it");
    assertEquals(
        List.of(TaskType.USER, TaskType.EXTERNAL),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskType)
            .toList(),
        "nothing outside the model says which of the two kinds the task was, so both are subscribed");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("declared BPMN process 'order_approval'") && logged.contains(SCOPED_NAME),
        () -> "the start names the id those subscriptions were opened for, and the names: "
            + logged);
    assertTrue(
        logged.contains("subscribed twice"),
        () -> "and says why there are two subscriptions per name: "
            + logged);

  }

  @Test
  @DisplayName("A delivery of the old id's task reaches the methods wired to the old id")
  public void aDeliveryOfTheOldIdIsRoutedToTheOldId() {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    final var service = serviceScopedBy(NameClashAvoidance.USE_PREFIX);
    service.startWorkflowProcessing(MODULE, new PeaProcessingContext(MODULE));

    // the engine names no BPMN process, which is what every engine behind this API does
    // today (GAPS.md, entry 6) - the subscription itself says which process it serves
    engine.deliverTask("task-1", SCOPED_NAME, TaskType.EXTERNAL, null, Map.of("id", "4711"));

    assertEquals(OLD_ID, core.invokedBpmnProcessId);
    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-1")),
        engine.getCompletedTasks());

  }

  @Test
  @DisplayName("An unprefixed task definition needs no subscription of its own")
  public void anUnprefixedTaskDefinitionNeedsNothing(
      final CapturedOutput output) {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    final var service = serviceScopedBy(NameClashAvoidance.NONE);

    service.startWorkflowProcessing(MODULE, aDeployedModelServing(TASK_DEFINITION));

    assertEquals(
        List.of(TASK_DEFINITION),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .toList(),
        "the deployed process already asks for that name, and a second subscription for it "
            + "would serve nothing");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("served by the subscriptions of the deployed processes"),
        () -> "the start says that the old id needs nothing of its own: "
            + logged);
    assertTrue(
        logged.contains("attributed to a deployed process"),
        () -> "and what that costs, because the two cannot be told apart: "
            + logged);

  }

  @Test
  @DisplayName("A shared subscription asks for the payload variables the old id's methods read")
  public void aSharedSubscriptionAsksForWhatTheOldIdReads() {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    core.taskParameters = Map.of(DEPLOYED_ID, List.of("region"), OLD_ID, List.of("legacyScore"));
    final var service = serviceScopedBy(NameClashAvoidance.NONE);

    service.startWorkflowProcessing(MODULE, aDeployedModelServing(TASK_DEFINITION));

    assertEquals(
        java.util.Set.of("id", "legacyScore", "region"),
        engine.getSubscriptions().getFirst().payloadDescription(),
        "a method of the old id reads a variable the new model's methods do not, and a task "
            + "delivered without it cannot be dispatched at all");

  }

  @Test
  @DisplayName("A delivery nobody can route names the old id as one of the reasons")
  public void anAmbiguousDeliveryNamesTheOldId() {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    final var service = serviceScopedBy(NameClashAvoidance.NONE);
    final var context = aDeployedModelServing(TASK_DEFINITION);
    // a second deployed process using the same task definition, which is what makes a
    // delivery without the meta entry ambiguous in the first place
    context
        .getModels()
        .add(new PeaBpmnModel(
            "returns.bpmn", "<bpmn/>".getBytes(StandardCharsets.UTF_8), "OrderReturn", List
                .of(new BpmnTaskSpec("Activity_approve", TASK_DEFINITION))));

    service.startWorkflowProcessing(MODULE, context);
    engine.deliverTask("task-2", TASK_DEFINITION, TaskType.EXTERNAL, null, Map.of("id", "4711"));

    assertEquals(1, engine.getFailedTasks().size());
    final var reason = engine.getFailedTasks().getFirst().reason();
    assertTrue(
        reason.contains(OLD_ID) && reason.contains("old id of a renamed process"),
        () -> "a diagnostic naming the deployed processes alone points at processes which are "
            + "all innocent: "
            + reason);
    assertTrue(
        reason.contains("old model keeps being deployed"),
        () -> "and the way out which asks nothing of the engine belongs to it: "
            + reason);

  }

  @Test
  @DisplayName("A declared id whose methods name no task definition is a warning naming both ways out")
  public void aDeclaredIdWithoutATaskDefinitionIsReported(
      final CapturedOutput output) {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of());
    final var service = serviceScopedBy(NameClashAvoidance.USE_PREFIX);

    service.startWorkflowProcessing(MODULE, new PeaProcessingContext(MODULE));

    assertTrue(engine.getSubscriptions().isEmpty(), "there is no name to subscribe for");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("no @WorkflowTask method serving that id names a task definition"),
        () -> "what cannot be reached has to be read before the rename is deployed: "
            + logged);
    assertTrue(
        logged.contains("@WorkflowTask(taskDefinition = ...)"),
        () -> "and the way out is part of it: "
            + logged);
    assertTrue(
        logged.contains("keep deploying the old model under its old id"),
        () -> "as is the way which asks nothing of the engine: "
            + logged);

  }

  @Test
  @DisplayName("A module declaring nothing without a model subscribes as it did before")
  public void aModuleWithoutADeclaredIdSubscribesAsBefore() {

    final var service = serviceScopedBy(NameClashAvoidance.USE_PREFIX);

    service.startWorkflowProcessing(MODULE, aDeployedModelServing(TASK_DEFINITION));

    assertEquals(
        List.of("test-module__OrderApproval__approve"),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .toList(),
        "one subscription for the deployed model and nothing else");

  }

  @Test
  @DisplayName("The viewer API says why it has nothing to show for a declared id")
  public void theViewerApiSaysWhyItHasNothing() {

    core.declaredWithoutAModel = Map.of(OLD_ID, List.of(TASK_DEFINITION));
    final var service = serviceScopedBy(NameClashAvoidance.USE_PREFIX);

    service.startWorkflowProcessing(MODULE, new PeaProcessingContext(MODULE));

    assertTrue(
        deployedProcesses.isDeclaredWithoutDeployment(MODULE, OLD_ID),
        "a caller asking about a workflow of the old id has to be told why the answer is empty");
    assertFalse(
        deployedProcesses.isDeclaredWithoutDeployment(MODULE, DEPLOYED_ID),
        "and a process nobody declared that way must not be explained away");

  }

}
