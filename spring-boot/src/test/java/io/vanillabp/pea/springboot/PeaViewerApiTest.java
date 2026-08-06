package io.vanillabp.pea.springboot;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * The viewer/history API (story 26) of the Process-Engine-API adapter. The
 * Process-Engine-API has neither a repository nor a query/history API
 * ({@code GAPS.md} entries 12 and 13), so the adapter answers from what it
 * deployed at boot:
 * <ul>
 * <li>process definitions: the deployed process, versioned by the deployment
 * key;</li>
 * <li>BPMN XML: the deployed resource, byte for byte;</li>
 * <li>history: no elements (the SPI's "not supported by the underlying
 * BPMS").</li>
 * </ul>
 */
public class PeaViewerApiTest {

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
        <bpmn:process id="ViewedProcess" isExecutable="true"/>
      </bpmn:definitions>
      """;

  private record Aggregate(Object id) {
  }

  private static AggregatePersistenceAware<Aggregate> persistence() {

    return new AggregatePersistenceAware<>() {
      @Override
      public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
      }

      @Override
      public Aggregate save(
          final Aggregate aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final Aggregate aggregate) {
        return aggregate.id();
      }
    };

  }

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final PeaDeployedProcesses deployedProcesses = new PeaDeployedProcesses();

  private final PeaDeploymentService deploymentService = new PeaDeploymentService(
      "pea", engine, new PeaDeploymentServiceTest.PermissiveInvoker(), engine, engine, deployedProcesses);

  private final PeaProcessService<Aggregate> processService = new PeaProcessService<>(
      "pea", engine, engine, engine, engine, deployedProcesses);

  private void deploy() {

    final var models = deploymentService.readBpmn(
        "test-module", "viewed.bpmn", new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)), true);
    var context = (io.vanillabp.pea.PeaProcessingContext) null;
    for (final var model : models) {
      context = deploymentService.prepareBpmn(
          "test-module", context, "viewed.bpmn", model.getKey(), model.getValue());
      deploymentService.wireBpmn("test-module", "viewed.bpmn", model.getKey(), model.getValue(), context);
    }
    deploymentService.deployResources("test-module", context);

  }

  @Test
  @DisplayName("Definitions and BPMN XML come from what was deployed at boot")
  public void definitionsAndXmlComeFromTheDeployment() throws Exception {

    deploy();

    final var definitions = processService.getProcessDefinitions(
        "test-module", "ViewedProcess", persistence(), "42", null);

    Assertions.assertEquals(1, definitions.size());
    Assertions.assertEquals("test-module|ViewedProcess", definitions
        .getFirst()
        .id());
    Assertions.assertEquals("ViewedProcess", definitions
        .getFirst()
        .bpmnProcessId());
    // the Process-Engine-API's only version information is the deployment key
    Assertions.assertNotNull(definitions
        .getFirst()
        .version());
    Assertions.assertNull(definitions
        .getFirst()
        .usedByElements());

    try (var xml = processService.getBpmnXml("test-module", "ViewedProcess", definitions
        .getFirst()
        .id())) {
      Assertions.assertEquals(BPMN, new String(xml.readAllBytes(), StandardCharsets.UTF_8));
    }

    Assertions.assertNull(
        processService.getBpmnXml("test-module", "ViewedProcess", "test-module|Unknown"),
        "an unknown definition is answered with null - the core raises the guiding exception");

  }

  @Test
  @DisplayName("The history has no elements - the Process-Engine-API offers none")
  public void historyHasNoElements() {

    deploy();

    final var history = processService.getWorkflowHistory(
        "test-module", "ViewedProcess", persistence(), "42", null);

    Assertions.assertEquals("test-module|ViewedProcess", history.processDefinitionId());
    Assertions.assertNull(history.startTime());
    Assertions.assertNull(history.endTime());
    Assertions.assertNull(history.elementsHistory(), "the SPI expresses 'no element history' as null");

    // secondary history contexts cannot exist without an element history
    Assertions.assertNull(
        processService.getWorkflowHistory("test-module", "ViewedProcess", persistence(), "42", "any-context"));
    Assertions.assertTrue(
        processService
            .getProcessDefinitions("test-module", "ViewedProcess", persistence(), "42", "any-context")
            .isEmpty());

  }

  @Test
  @DisplayName("A process never deployed by this application version is unknown to the adapter")
  public void unknownProcessIsReportedAsUnknown() {

    deploy();

    Assertions.assertTrue(
        processService
            .getProcessDefinitions("test-module", "OtherProcess", persistence(), "42", null)
            .isEmpty());
    Assertions.assertNull(
        processService.getWorkflowHistory("test-module", "OtherProcess", persistence(), "42", null));

  }

}
