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
 * Smoke test proving the Process-Engine-API adapter is discovered by the VanillaBP Quarkus
 * integration on the CDI side: the application boots with the extension loaded, a
 * {@code ProcessService} is generated for the sample aggregate (which requires the adapter
 * to be announced and its process-service bean produced), and the default in-memory mock
 * engine is present with no recorded invocations. No Docker and no network involved.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaAdapterSmokeTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.pea.quarkus.sample")
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module", "META-INF/workflow-module"));

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  @Inject
  InMemoryProcessEngine inMemoryProcessEngine;

  @Test
  public void testAdapterDiscoveredWithMockEngine() {

    // the ProcessService could only be built if the Process-Engine-API adapter was
    // discovered and its process-service bean produced
    Assertions.assertNotNull(sampleProcessService);

    // the mock engine is present and untouched
    Assertions.assertNotNull(inMemoryProcessEngine);
    Assertions.assertTrue(inMemoryProcessEngine.getInvocations().isEmpty(),
        "no Process-Engine-API invocations are expected during a plain boot");

  }

}
