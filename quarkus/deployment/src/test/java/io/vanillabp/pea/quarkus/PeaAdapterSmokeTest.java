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
 * A smoke test which proves that the VanillaBP Quarkus integration finds this adapter on the
 * CDI side. The application boots with the extension loaded. A {@code ProcessService} is
 * generated for the sample aggregate, which needs the adapter to be announced and its
 * process-service bean to be produced. The default mock engine is there and has recorded no
 * calls. No Docker and no network are needed.
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

    // The ProcessService can only be built if the adapter was found and its process-service
    // bean was produced.
    Assertions.assertNotNull(sampleProcessService);

    Assertions.assertNotNull(inMemoryProcessEngine);
    Assertions.assertTrue(inMemoryProcessEngine.getInvocations().isEmpty(),
        "no Process-Engine-API invocations are expected during a plain boot");

  }

}
