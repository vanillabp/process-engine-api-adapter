package io.vanillabp.pea.springboot.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * The BPMN files of a workflow module reach the Process-Engine-API at startup. The full
 * VanillaBP Spring Boot integration boots. The deployment reads the BPMN of the module and
 * deploys it through the {@code DeploymentApi} of the mock. The test needs no Docker and no
 * network.
 */
@SpringBootTest(
    classes = DeploymentIntegrationTestApplication.class,
    properties = {
        "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/deploy",
        // no workflow service of this application claims the process of that file
        "vanillabp.workflow-modules.pea-test-module.workflows.PeaTestProcess.implemented-externally=true"
    })
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentIntegrationTest {

  @Autowired
  private InMemoryProcessEngine inMemoryProcessEngine;

  @Test
  public void bpmnResourcesAreDeployedOnStartup() {

    // Exactly one bundle was deployed during the boot.
    Assertions.assertEquals(1, inMemoryProcessEngine.getDeployments().size());
    final var deployment = inMemoryProcessEngine.getDeployments().get(0);

    // It holds the BPMN file of the module.
    Assertions.assertEquals(1, deployment.resources().size());
    Assertions.assertTrue(
        deployment.resources().get(0).getName().endsWith("pea-deploy-test.bpmn"),
        "the deployed resource is the module's BPMN file");

    // It went through the DeploymentApi of the Process-Engine-API.
    Assertions.assertTrue(
        inMemoryProcessEngine.getInvocations().stream().anyMatch(invocation -> "deploy".equals(invocation.method())),
        "a DeploymentApi.deploy invocation was recorded");

    // A plain boot starts no workflow.
    Assertions.assertTrue(inMemoryProcessEngine.getStartedInstances().isEmpty());

  }

}
