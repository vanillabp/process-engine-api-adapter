package io.vanillabp.pea.springboot;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.pea.springboot.processservice.PeaAdapterProcessServiceConfiguration;

/**
 * Discovery test of the per-adapter-id bean convention (adapter-config-model story
 * 26d): TWO adapter ids of type {@code process-engine-api} yield one
 * {@code PeaProcessService} and one {@code PeaDeploymentService} element bean PER
 * configured id (both sharing the in-memory mock engine). No Docker and no network
 * involved.
 */
@SpringBootTest(
    classes = {
        PeaAdapterConfiguration.class, PeaAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, TestPersistenceConfiguration.class, TestOutboxConfiguration.class
    },
    properties = {
        "vanillabp.prioritized-adapters=pea,pea-two", "vanillabp.adapters.pea-two.type=process-engine-api", "vanillabp.workflow-modules.pea-test-module.adapters.pea-two.resources-location=classpath*:pea-test-module/processes/none"
    })
@ExtendWith(SuppressOutputExtension.class)
public class PeaTwoAdapterIdsDiscoveryTest {

  @Autowired
  private ApplicationContext context;

  @Test
  public void perIdBeansAreRegisteredForBothIds() {

    final var processServiceIds = context
        .getBeanProvider(MigratableProcessService.class)
        .stream()
        .map(processService -> ((MigratableProcessService<?>) processService).getAdapterId())
        .collect(Collectors.toSet());
    Assertions.assertEquals(Set.of("pea", "pea-two"), processServiceIds);

    final var deploymentServiceIds = context
        .getBeanProvider(AdapterDeploymentService.class)
        .stream()
        .map(deploymentService -> ((AdapterDeploymentService<?, ?>) deploymentService).getAdapterId())
        .collect(Collectors.toSet());
    Assertions.assertEquals(Set.of("pea", "pea-two"), deploymentServiceIds);

  }

}
