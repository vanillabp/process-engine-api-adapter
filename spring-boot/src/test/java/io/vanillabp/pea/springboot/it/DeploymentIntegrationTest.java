package io.vanillabp.pea.springboot.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;

/**
 * Integration test proving that BPMN resources of a workflow module are deployed to the
 * Process-Engine-API on startup: the full VanillaBP Spring Boot integration boots, the
 * deployment lifecycle reads the module's BPMN (StAX parsing of the executable process id)
 * and deploys it through the mock {@code DeploymentApi}. No Docker and no network involved.
 */
@SpringBootTest(
    classes = DeploymentIntegrationTestApplication.class,
    properties = {
        "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/deploy"
    })
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentIntegrationTest {

  @Autowired
  private InMemoryProcessEngine inMemoryProcessEngine;

  @Test
  public void bpmnResourcesAreDeployedOnStartup() {

    // exactly one deployment bundle was deployed during boot...
    Assertions.assertEquals(1, inMemoryProcessEngine.getDeployments().size());
    final var deployment = inMemoryProcessEngine.getDeployments().get(0);

    // ...containing the module's BPMN file...
    Assertions.assertEquals(1, deployment.resources().size());
    Assertions.assertTrue(
        deployment.resources().get(0).getName().endsWith("pea-deploy-test.bpmn"),
        "the deployed resource is the module's BPMN file");

    // ...and it was deployed via the Process-Engine-API DeploymentApi
    Assertions.assertTrue(
        inMemoryProcessEngine.getInvocations().stream().anyMatch(invocation -> "deploy".equals(invocation.method())),
        "a DeploymentApi.deploy invocation was recorded");

    // no workflow was started during a plain boot
    Assertions.assertTrue(inMemoryProcessEngine.getStartedInstances().isEmpty());

  }

}
