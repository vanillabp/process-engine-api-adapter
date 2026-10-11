package io.vanillabp.pea.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.quarkus.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Proves that the deployment pipeline of the platform runs this adapter on Quarkus. The BPMN
 * resource below the configured <code>resources-location</code> is read at boot
 * ({@code readBpmn}, {@code prepareBpmn}, {@code wireBpmn}). Then {@code deployResources}
 * reaches the mock engine through the {@code DeploymentApi}, and the mock records a
 * deployment with the BPMN file in it. No Docker and no network are needed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaDeploymentPipelineTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.pea.quarkus.sample")
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addAsResource(
              "pea-test-module/processes/deploy/pea-deploy-test.bpmn",
              "pea-test-module/processes/deploy/pea-deploy-test.bpmn")
          .addAsResource("META-INF/workflow-module", "META-INF/workflow-module"));

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  @Inject
  InMemoryProcessEngine inMemoryProcessEngine;

  @Test
  public void deploymentReachedTheMockEngine() {

    Assertions.assertNotNull(sampleProcessService);

    final var deployments = inMemoryProcessEngine.getDeployments();
    Assertions.assertEquals(
        1,
        deployments.size(),
        "expected the deployment pipeline to deploy the workflow module's BPMN bundle at boot");
    final var resources = deployments.getFirst().resources();
    Assertions.assertEquals(1, resources.size());
    Assertions.assertEquals("pea-deploy-test.bpmn", resources.getFirst().getName());

  }

}
