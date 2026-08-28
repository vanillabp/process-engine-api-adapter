package io.vanillabp.pea.quarkus.runtime;

import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the Process-Engine-API adapter's {@link PeaDeploymentService} instances -
 * ONE per configured adapter id of type {@code process-engine-api} (the
 * per-adapter-id shape on Quarkus: a CDI producer cannot yield N element beans for N
 * runtime-configured ids), consumed by the VanillaBP Quarkus integration's runtime
 * deployment pipeline. The {@link DeploymentApi} is injected - by default it
 * resolves to the mock-backed {@link io.vanillabp.pea.mock.InMemoryProcessEngine}
 * default bean.
 * <p>
 * Platform contract: the List's element type is the SPI interface with BOTH type
 * parameters literally {@code Object} - regardless of the adapter's actual model
 * ({@code PeaBpmnModel}) and context ({@code PeaProcessingContext}) classes: CDI's
 * parameterized-type matching of differing type arguments is not reliable across
 * modes, so the platform looks the beans up with the exact type. The pipeline
 * matches models via {@code getModelType()}/{@code getProcessContextType()}, never
 * via the generics. The producer method is {@code @Singleton} (deployment services
 * are not client-proxyable).
 */
@ApplicationScoped
public class PeaDeploymentServiceProducer {

  @Produces
  @Singleton
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  public List<AdapterDeploymentService<Object, Object>> peaAdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      final DeploymentApi deploymentApi,
      final io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskRegistry,
      final dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi taskSubscriptionApi,
      final dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi serviceTaskCompletionApi,
      final io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry deployedProcessesRegistry,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    final var overlay = org.eclipse.microprofile.config.ConfigProvider
        .getConfig()
        .unwrap(io.smallrye.config.SmallRyeConfig.class)
        .getConfigMapping(VanillaBpPeaProperties.class);

    return (List) properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> PeaAdapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .map(adapterId -> {
          final var deploymentService = new PeaDeploymentService(
              adapterId, deploymentApi, workflowTaskRegistry, workflowTaskRegistry, taskSubscriptionApi, serviceTaskCompletionApi, deployedProcessesRegistry
                  .forAdapter(adapterId));
          deploymentService.setScoping(scoping);
          // What each subscription asks the engine for, resolvable down to
          // task level
          deploymentService.setFetchVariablesResolver((
              workflowModuleId,
              bpmnProcessId,
              taskDefinition) -> overlay.fetchVariablesFor(
                  workflowModuleId, bpmnProcessId, taskDefinition, adapterId));
          deploymentService.setWorkflowEndedInvoker(workflowTaskRegistry);
          return deploymentService;
        })
        .toList();

  }

}
