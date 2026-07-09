package io.vanillabp.pea.springboot.deployment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.deployment.PeaDeploymentService;

/**
 * Registers one {@link PeaDeploymentService} per configured Process-Engine-API adapter id.
 * Mirrors the dummy adapter template: walk all workflow modules, collect every prioritized
 * adapter id whose configured type is {@code process-engine-api} and build exactly one
 * deployment service per id.
 */
@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
public class PeaAdapterDeploymentConfiguration {

  @Bean
  public List<AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext>> peaDeploymentServices(
      final WorkflowModules allWorkflowModules,
      final MigrationAdapterProperties properties) {

    final List<AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext>> deploymentServices = new ArrayList<>();
    final Set<String> adaptersBuilt = new HashSet<>();

    allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .forEach(workflowModuleId -> properties
            .getPrioritizedAdaptersFor(workflowModuleId)
            .stream()
            .filter(adapterId -> properties
                .getAdapters()
                .get(adapterId)
                .equals(PeaAdapter.ADAPTER_TYPE))
            .forEach(adapterId -> {

              if (adaptersBuilt.contains(adapterId)) {
                return;
              }

              deploymentServices.add(new PeaDeploymentService(adapterId));
              adaptersBuilt.add(adapterId);

            }));

    return deploymentServices;

  }

}
