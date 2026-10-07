package io.vanillabp.pea;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * One file with two processes. {@code Loans} is claimed by a <code>&#64;WorkflowService</code>
 * of the application. {@code NightlyReview} is claimed by nobody: the application said that
 * something else serves it, otherwise the core would have ended the start before this adapter
 * saw the file.
 * <p>
 * The process nobody claims is deployed with its file and left alone. Its timer start event,
 * which this adapter refuses in a claimed process, ends nothing, and nothing subscribes to its
 * task. The claimed process is wired as before.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaUnclaimedProcessTest {

  private static final String MODULE = "loan-approval";

  private static final String FILE = "loans.bpmn";

  private static final String TWO_PROCESSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0">
        <bpmn:process id="Loans" isExecutable="true">
          <bpmn:serviceTask id="Approve">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="approve" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
        <bpmn:process id="NightlyReview" isExecutable="true">
          <bpmn:startEvent id="EveryNight">
            <bpmn:timerEventDefinition />
          </bpmn:startEvent>
          <bpmn:serviceTask id="Review">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="review" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  /**
   * The processes the core was asked to validate, which is what a claimed process gets.
   */
  private final List<String> validated = new ArrayList<>();

  private final PeaDeploymentService service = new PeaDeploymentService(
      "pea", engine, TestCollaborators.of(new PeaDeploymentServiceTest.PermissiveInvoker() {

        @Override
        public void validateTaskWiring(
            final String workflowModuleId,
            final String bpmnProcessId,
            final Collection<BpmnTaskSpec> tasks) {

          validated.add(bpmnProcessId);

        }

        @Override
        public String resolveWorkflowAggregateIdName(
            final String workflowModuleId,
            final String bpmnProcessId) {

          if ("Loans".equals(bpmnProcessId)) {
            return "id";
          }
          throw new IllegalStateException("No @WorkflowService class is registered for '%s'".formatted(
              bpmnProcessId));

        }

      }), engine, engine);

  private PeaProcessingContext deployAndStart() {

    PeaProcessingContext context = null;
    final var models = service
        .readBpmn(MODULE, FILE, new ByteArrayInputStream(TWO_PROCESSES.getBytes(StandardCharsets.UTF_8)), true);
    for (final var entry : models) {
      context = service.prepareBpmn(MODULE, context, FILE, entry.getKey(), entry.getValue());
    }
    for (final var entry : models) {
      service.wireBpmn(MODULE, FILE, entry.getKey(), entry.getValue(), context);
    }
    service.deployResources(MODULE, context);
    service.startWorkflowProcessing(MODULE, context);
    return context;

  }

  @Test
  @DisplayName("The timer start of a process nobody claims ends nothing, and the file is deployed")
  public void aTimerStartNobodyClaimsEndsNothing() {

    Assertions.assertDoesNotThrow(this::deployAndStart);

    Assertions.assertEquals(1, engine.getDeployments().size(), "the file goes to the engine as a whole");

  }

  @Test
  @DisplayName("Only the claimed process is validated and subscribed for")
  public void onlyTheClaimedProcessIsServed() {

    deployAndStart();

    Assertions.assertEquals(List.of("Loans"), validated, "the core is asked about the claimed process only");
    Assertions.assertEquals(
        List.of("approve"),
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .toList(),
        "nothing subscribes to the task of the process nobody claims");

  }

}
