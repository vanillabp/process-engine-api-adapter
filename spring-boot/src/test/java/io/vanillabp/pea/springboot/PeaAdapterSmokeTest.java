package io.vanillabp.pea.springboot;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.springboot.processservice.PeaAdapterProcessServiceConfiguration;

/**
 * The VanillaBP Spring Boot integration finds the Process-Engine-API adapter. The context
 * boots without any BPMN files. A deployment service exists for the configured adapter id
 * {@code pea}. The default in-memory mock engine is there and has recorded no calls. The
 * test needs no Docker and no network.
 */
@SpringBootTest(
    classes = {
        PeaAdapterConfiguration.class, PeaAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, TestPersistenceConfiguration.class, TestOutboxConfiguration.class
    })
@ExtendWith(SuppressOutputExtension.class)
public class PeaAdapterSmokeTest {

  @Autowired
  private ApplicationContext context;

  @Autowired
  private InMemoryProcessEngine inMemoryProcessEngine;

  @Test
  public void testAdapterDiscoveredWithMockEngine() {

    // One AdapterDeploymentService bean per adapter, never a List bean, so that several
    // adapter types can live side by side
    final var deploymentService = context.getBean(AdapterDeploymentService.class);
    Assertions.assertEquals("pea", deploymentService.getAdapterId());
    Assertions.assertEquals(PeaAdapter.ADAPTER_TYPE, deploymentService.getAdapterType());

    Assertions.assertNotNull(inMemoryProcessEngine);
    Assertions.assertTrue(inMemoryProcessEngine.getInvocations().isEmpty(),
        "no Process-Engine-API invocations are expected during a plain boot");

  }

}
