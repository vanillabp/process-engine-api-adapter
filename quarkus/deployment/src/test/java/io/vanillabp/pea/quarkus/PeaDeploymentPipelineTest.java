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
 * Proves the platform's runtime deployment pipeline (story 26b) drives the
 * Process-Engine-API adapter on Quarkus: the BPMN resource below the configured
 * <code>resources-location</code> is read at boot
 * ({@code readBpmn}/{@code prepareBpmn}/{@code wireBpmn}) and
 * {@code deployResources} reaches the in-memory mock engine via the
 * {@code DeploymentApi} - the mock records a deployment containing the BPMN file.
 * No Docker and no network involved.
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
