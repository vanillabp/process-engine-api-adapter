package io.vanillabp.pea.springboot;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * Unit tests of {@link PeaDeploymentService}: the StAX-based BPMN parsing of
 * {@code readBpmn} (executable-process id extraction) and {@code deployResources} deploying
 * the module's resources through the (mock) Process-Engine-API {@code DeploymentApi}.
 */
public class PeaDeploymentServiceTest {

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final PeaDeploymentService service = new PeaDeploymentService("pea", engine);

  private static ByteArrayInputStream bpmn(
      final String xml) {

    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));

  }

  @Test
  public void readBpmnExtractsSingleExecutableProcessId() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="OnlyProcess" isExecutable="true"/>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "one.bpmn", bpmn(xml), true);

    Assertions.assertEquals(1, models.size());
    Assertions.assertEquals("OnlyProcess", models.get(0).getKey());
    final var model = models.get(0).getValue();
    Assertions.assertEquals("OnlyProcess", model.bpmnProcessId());
    Assertions.assertEquals("one.bpmn", model.filename());
    Assertions.assertTrue(new String(model.resource(), StandardCharsets.UTF_8).contains("OnlyProcess"));

  }

  @Test
  public void readBpmnReturnsOnlyExecutableProcesses() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="Executable1" isExecutable="true"/>
          <bpmn:process id="NonExecutable" isExecutable="false"/>
          <bpmn:process id="NoFlag"/>
          <bpmn:process id="Executable2" isExecutable="true"/>
        </bpmn:definitions>
        """;

    final var models = service.readBpmn("mod", "several.bpmn", bpmn(xml), true);

    Assertions.assertEquals(2, models.size());
    Assertions.assertEquals("Executable1", models.get(0).getKey());
    Assertions.assertEquals("Executable2", models.get(1).getKey());

  }

  @Test
  public void readBpmnWithoutExecutableProcessReturnsEmpty() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
          <bpmn:process id="Nope" isExecutable="false"/>
        </bpmn:definitions>
        """;

    Assertions.assertTrue(service.readBpmn("mod", "none.bpmn", bpmn(xml), true).isEmpty());

  }

  @Test
  public void readBpmnWrapsParseErrorsInBpmnParseException() {

    final var broken = "<bpmn:definitions><unclosed>";

    Assertions.assertThrows(
        BpmnParseException.class,
        () -> service.readBpmn("mod", "broken.bpmn", bpmn(broken), true));

  }

  @Test
  public void deployResourcesDeploysEachFileOnceThroughTheDeploymentApi() {

    final var context = new PeaProcessingContext("mod");
    // two executable processes in the same file must be deployed as ONE resource...
    context.getModels().add(new PeaBpmnModel("a.bpmn", "a-bytes".getBytes(StandardCharsets.UTF_8), "P1"));
    context.getModels().add(new PeaBpmnModel("a.bpmn", "a-bytes".getBytes(StandardCharsets.UTF_8), "P2"));
    // ...and a second file as another resource
    context.getModels().add(new PeaBpmnModel("b.bpmn", "b-bytes".getBytes(StandardCharsets.UTF_8), "P3"));

    service.deployResources("mod", context);

    Assertions.assertEquals(1, engine.getDeployments().size(), "exactly one deployment bundle expected");
    final var deployment = engine.getDeployments().get(0);
    Assertions.assertEquals(2, deployment.resources().size(), "each BPMN file deployed exactly once");
    Assertions.assertTrue(
        deployment.resources().stream().anyMatch(resource -> "a.bpmn".equals(resource.getName())));
    Assertions.assertTrue(
        deployment.resources().stream().anyMatch(resource -> "b.bpmn".equals(resource.getName())));
    Assertions.assertNull(deployment.tenantId(), "module-as-tenant is not expressible - deployed to default tenant");

  }

  @Test
  public void deployResourcesWithoutModelsDeploysNothing() {

    service.deployResources("mod", null);
    service.deployResources("mod", new PeaProcessingContext("mod"));

    Assertions.assertTrue(engine.getDeployments().isEmpty());

  }

}
