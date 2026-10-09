package io.vanillabp.pea;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.wiring.PeaFetchVariables;

/**
 * What a task subscription of this adapter asks the engine for, asserted where
 * it becomes visible: the {@code SubscribeForTaskCmd} the adapter hands the
 * Process-Engine-API. A subscription naming the empty set asks for the complete payload
 * of the process instance, which is what the named set avoids.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaFetchVariablesTest {

  private static final String MODULE = "mod";

  /**
   * Two processes whose tasks share the task definition <code>approve</code> - so ONE
   * subscription serves both.
   */
  private static final String TWO_PROCESSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:serviceTask id="ApproveLoan">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
        <bpmn:process id="Cards" isExecutable="true">
          <bpmn:serviceTask id="ApproveCard">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String USER_TASK_PROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
        <bpmn:process id="UTProcess" isExecutable="true">
          <bpmn:userTask id="ut1">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:formDefinition externalReference="utApprove" />
            </bpmn:extensionElements>
          </bpmn:userTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  /**
   * The service under test, with the given core standing in for both halves of the task
   * SPI: this service derives its subscriptions while wiring and hands the deliveries of
   * those subscriptions back at runtime.
   */
  private PeaDeploymentService deploymentService(
      final PeaDeploymentServiceTest.PermissiveInvoker core) {

    return new PeaDeploymentService("pea", engine, TestCollaborators.of(core), engine, engine);

  }

  /**
   * A core answering the given aggregate-ID variable per BPMN process and the given
   * <code>&#64;TaskParam</code> names per task definition - the two questions the
   * derivation asks it.
   */
  private static PeaDeploymentServiceTest.PermissiveInvoker invoker(
      final Function<String, String> aggregateIdNames,
      final Function<String, List<String>> taskParameters) {

    return new PeaDeploymentServiceTest.PermissiveInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        final var name = aggregateIdNames.apply(bpmnProcessId);
        if (name == null) {
          throw new IllegalStateException("no workflow service serves '%s'".formatted(bpmnProcessId));
        }
        return name;

      }

      @Override
      public Collection<String> taskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return taskParameters.apply(taskDefinitionOrActivityId);

      }

    };

  }

  private PeaProcessingContext wire(
      final PeaDeploymentService service,
      final String filename,
      final String xml) {

    PeaProcessingContext context = null;
    for (final var entry : service
        .readBpmn(MODULE, filename, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), true)) {
      context = service.prepareBpmn(MODULE, context, filename, entry.getKey(), entry.getValue());
    }
    return context;

  }

  private Set<String> payloadOf(
      final String taskDescriptionKey) {

    return engine
        .getSubscriptions()
        .stream()
        .filter(subscription -> subscription.taskDescriptionKey().equals(taskDescriptionKey))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no subscription for '%s'".formatted(taskDescriptionKey)))
        .payloadDescription();

  }

  @Test
  @DisplayName("A subscription names the aggregate-ID variable instead of asking for everything")
  public void theSubscriptionNamesWhatItReads() {

    final var service = deploymentService(invoker(bpmnProcessId -> "id", taskDefinition -> List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    Assertions.assertEquals(
        Set.of("id"),
        payloadOf("approve"),
        "an empty set means 'hand me everything' - and everything is what the adapter used to get");

  }

  @Test
  @DisplayName("A subscription serving two processes asks for the union of both")
  public void theUnionCoversEverythingTheSubscriptionServes() {

    final var service = deploymentService(invoker(
        bpmnProcessId -> "Loans".equals(bpmnProcessId)
            ? "loanId"
            : "cardId",
        taskDefinition -> "approve".equals(taskDefinition)
            ? List.of("region")
            : List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    Assertions.assertEquals(
        Set.of("cardId", "loanId", "region"),
        payloadOf("approve"),
        "one subscription serves a task definition across the processes of the module, so two "
            + "processes disagreeing about the aggregate-ID variable is no conflict");

  }

  @Test
  @DisplayName("A @TaskParam naming a variable no model mentions travels with the delivery")
  public void aDeclaredParameterIsAskedFor() {

    final var service = deploymentService(invoker(bpmnProcessId -> "id", taskDefinition -> List.of("bigPayload")));

    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    Assertions.assertEquals(
        Set.of("bigPayload", "id"),
        payloadOf("approve"),
        "the name comes from the annotation on the method, and this adapter has no BPMN model to "
            + "guess it from");

  }

  @Test
  @DisplayName("A @TaskParam of a method wired by the element id travels too")
  public void aParameterOfAnIdWiredMethodIsAskedFor() {

    // this core answers names for the ELEMENT id and nothing for the task definition, which
    // is what a method carrying @WorkflowTask(id = ...) looks like: it never mentions the
    // name the engine delivers under. Asked with the task definition alone, its variables
    // stayed unfetched and the delivery failed on the parameter it declares
    final var service = deploymentService(invoker(
        bpmnProcessId -> "id",
        taskDefinitionOrElementId -> "ApproveLoan".equals(taskDefinitionOrElementId)
            ? List.of("amount")
            : List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    Assertions.assertEquals(
        Set.of("amount", "id"),
        payloadOf("approve"),
        "the subscription serves the element 'ApproveLoan' as well, so what its method declares "
            + "has to be asked for");

  }

  @Test
  @DisplayName("A user task wired by the element id is asked for its parameters as well")
  public void aParameterOfAnIdWiredUserTaskMethodIsAskedFor() {

    final var service = deploymentService(invoker(
        bpmnProcessId -> "id",
        taskDefinitionOrElementId -> "ut1".equals(taskDefinitionOrElementId)
            ? List.of("decision")
            : List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "ut.bpmn", USER_TASK_PROCESS));

    Assertions.assertEquals(
        Set.of("decision", "id"),
        payloadOf("utApprove"),
        "a notification carries a payload like every other delivery, and it is derived the same "
            + "way");

  }

  /**
   * A core where an extension reads <code>cockpitOnly</code> for the user task
   * <code>ut1</code> and no workflow method reads anything, noting the keys it was asked
   * with.
   */
  private static PeaDeploymentServiceTest.PermissiveInvoker extensionReading(
      final List<List<String>> askedKeys) {

    return new PeaDeploymentServiceTest.PermissiveInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return "id";

      }

      @Override
      public Collection<String> extensionTaskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final List<String> lookupKeys) {

        askedKeys.add(lookupKeys);
        return "ut1".equals(lookupKeys.getFirst())
            ? List.of("cockpitOnly")
            : List.of();

      }

    };

  }

  @Test
  @DisplayName("A variable only an extension reads travels with the delivery")
  public void aVariableOnlyAnExtensionReadsIsAskedFor() {

    final var askedKeys = new java.util.ArrayList<List<String>>();
    final var service = deploymentService(extensionReading(askedKeys));

    service.startWorkflowProcessing(MODULE, wire(service, "ut.bpmn", USER_TASK_PROCESS));

    Assertions.assertEquals(
        Set.of("cockpitOnly", "id"),
        payloadOf("utApprove"),
        "the engine hands the task to this subscription alone, so it has to ask for what an "
            + "extension reads from the delivery as well");
    Assertions.assertEquals(
        List.of(List.of("ut1", "utApprove")),
        askedKeys,
        "asked with the element id first, which is the order an extension looks a task up in");

  }

  @Test
  @DisplayName("A BPMN process no workflow service claims gets no subscription at all")
  public void aProcessNobodyClaimsGetsNoSubscription() {

    final var service = deploymentService(invoker(bpmnProcessId -> null, taskDefinition -> List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    Assertions.assertTrue(
        engine.getSubscriptions().isEmpty(),
        "a process nobody claims is deployed with its file and left alone");

  }

  @Test
  @DisplayName("A user-task subscription is narrowed the same way")
  public void theUserTaskSubscriptionIsNarrowedToo() {

    final var service = deploymentService(invoker(
        bpmnProcessId -> "id",
        taskDefinition -> "utApprove".equals(taskDefinition)
            ? List.of("decision")
            : List.of()));

    service.startWorkflowProcessing(MODULE, wire(service, "ut.bpmn", USER_TASK_PROCESS));

    Assertions.assertEquals(
        Set.of("decision", "id"),
        payloadOf("utApprove"),
        "a notification carries a payload like every other delivery");

  }

  @Test
  @DisplayName("An engine hands over what the subscription asked for, and the handler says so if a name is missing")
  public void aNameOutsideTheSubscriptionFailsGuiding() {

    final var selection = PeaFetchVariables.Selection.of(List.of("id"));

    Assertions.assertTrue(selection.covers("id"));
    Assertions.assertFalse(selection.covers("bigPayload"));
    Assertions.assertTrue(PeaFetchVariables.Selection.everything().covers("whatever"));
    Assertions
        .assertEquals(
            "all payload variables of the process instance",
            PeaFetchVariables.Selection.everything().describe());
    Assertions.assertEquals(Set.of(), PeaFetchVariables.Selection.everything().payloadDescription());

    final var missing = PeaFetchVariables
        .missingAggregateId("Task", "t-1", "approve", "Loans", "loanId", selection);
    Assertions.assertFalse(missing.contains("fetch-variables"), "the key is gone, so no message names it: "
        + missing);
    Assertions.assertTrue(missing.contains("[id]"), missing);

    final var unfetched = PeaFetchVariables.unfetchedTaskParameter("bigPayload", "approve", selection);
    Assertions.assertFalse(unfetched.contains("fetch-variables"), "the key is gone, so no message names it: "
        + unfetched);
    Assertions.assertTrue(unfetched.contains("workflow aggregate"), "the way out is the aggregate: "
        + unfetched);
    Assertions.assertTrue(
        unfetched.contains("@TaskParam(\"bigPayload\")"),
        "since the subscription asks for every declared name, reaching this message means the "
            + "name is not on the method - and the message says where to put it, but was: "
            + unfetched);

  }

  @Test
  @DisplayName("The removed key fetch-variables ends the start, naming every key which sets it")
  public void theRemovedKeyEndsTheStart() {

    PeaFetchVariables.rejectTheRemovedKey("pea", List.of());

    final var keys = List.of(
        "vanillabp.adapters.pea.fetch-variables",
        "vanillabp.workflow-modules.loans.workflows.Loans.tasks.approve.adapters.pea.fetch-variables");
    final var failure = Assertions.assertThrows(
        IllegalStateException.class,
        () -> PeaFetchVariables.rejectTheRemovedKey("pea", keys));
    final var message = failure.getMessage();
    Assertions.assertTrue(message.contains("does not exist any more"), message);
    keys.forEach(key -> Assertions.assertTrue(message.contains(key), "names "
        + key
        + ": "
        + message));
    Assertions.assertTrue(message.contains("Remove the key"), "says what to do: "
        + message);
    Assertions.assertTrue(message.contains("workflow aggregate"), "says what applies instead: "
        + message);

  }

  @Test
  @DisplayName("The mock hands the handler what the subscription asked for")
  public void theEngineNarrowsThePayload() {

    final var service = deploymentService(invoker(bpmnProcessId -> "id", taskDefinition -> List.of()));
    service.startWorkflowProcessing(MODULE, wire(service, "two.bpmn", TWO_PROCESSES));

    final var subscription = engine
        .getSubscriptions()
        .stream()
        .filter(candidate -> candidate.taskDescriptionKey().equals("approve"))
        .findFirst()
        .orElseThrow();

    Assertions.assertEquals(
        Map.of("id", "4711"),
        subscription.narrow(new LinkedHashMap<>(Map.of("id", "4711", "bigPayload", "x"))),
        "the whole point of naming the variables is that the rest never travels");

  }

}
