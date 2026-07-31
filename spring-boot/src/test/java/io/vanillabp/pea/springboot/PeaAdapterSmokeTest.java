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
 * Smoke test proving the Process-Engine-API adapter is discovered by the VanillaBP Spring
 * Boot integration: the context boots without any BPMN files, a deployment service is
 * created for the configured adapter id {@code pea}, and the default in-memory mock engine
 * is present with no recorded invocations. No Docker and no network involved.
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

    // element-bean convention: one AdapterDeploymentService bean per adapter
    // (never a List bean) so several adapter types can coexist
    final var deploymentService = context.getBean(AdapterDeploymentService.class);
    Assertions.assertEquals("pea", deploymentService.getAdapterId());
    Assertions.assertEquals(PeaAdapter.ADAPTER_TYPE, deploymentService.getAdapterType());

    // the mock engine is present and untouched
    Assertions.assertNotNull(inMemoryProcessEngine);
    Assertions.assertTrue(inMemoryProcessEngine.getInvocations().isEmpty(),
        "no Process-Engine-API invocations are expected during a plain boot");

  }

}
