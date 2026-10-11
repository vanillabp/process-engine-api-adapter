package io.vanillabp.pea.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.processservice.PeaProcessService;
import io.vanillabp.pea.wiring.PeaFetchVariables;

/**
 * Registers the beans of the Process-Engine-API adapter, once for each adapter id. Each
 * configured adapter id of type {@code process-engine-api} gets one {@link PeaProcessService}
 * bean and one {@link PeaDeploymentService} bean. These are single beans, never a bean of
 * type {@code List<...>}. The platform collects them one by one through
 * {@code ObjectProvider.stream()}.
 * <p>
 * The adapter ids are only known from the configuration at runtime. So the beans are
 * registered in code, through {@link BeanRegistrar} and
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}. Each instance gets its
 * adapter id as a constructor parameter. The bean suppliers are lazy. They ask the
 * {@code SupplierContext} for the Process-Engine-API beans only when the bean is created. By
 * default these beans are the in-memory mock.
 */
public class PeaAdapterBeanRegistrar implements BeanRegistrar {

  /**
   * Spring Boot builds the class when the auto-configuration imports it. It holds nothing:
   * what the adapter ids are is read from the environment while registering.
   */
  public PeaAdapterBeanRegistrar() {

  }

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    // One registry for all adapter ids. For each id it hands out the record of deployed
    // processes, which the deployment service and the process service share. The viewer
    // API has no other source of definitions and BPMN XML, see GAPS.md.
    registry.registerBean(
        "Pea_DeployedProcessesRegistry",
        PeaDeployedProcessesRegistry.class,
        spec -> spec.supplier(supplierContext -> new PeaDeployedProcessesRegistry()));

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        PeaAdapter.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "Pea_ProcessService_%s".formatted(adapterId),
              PeaProcessService.class,
              spec -> spec.supplier(supplierContext -> {
                final var processService = new PeaProcessService<>(
                    adapterId, supplierContext.bean(StartProcessApi.class), supplierContext
                        .bean(ServiceTaskCompletionApi.class), supplierContext
                            .bean(UserTaskCompletionApi.class), supplierContext
                                .bean(
                                    CorrelationApi.class), supplierContext
                                        .bean(PeaDeployedProcessesRegistry.class)
                                        .forAdapter(adapterId), AdapterBeanRegistrarSupport
                                            .collaborators(supplierContext, adapterId));
                // A SignalApi is optional. Without one, signals are not supported, and the
                // process service says so when somebody sends one.
                processService.setSignalApi(
                    supplierContext
                        .beanProvider(SignalApi.class)
                        .getIfAvailable());
                return processService;
              }));

          registry.registerBean(
              "Pea_DeploymentService_%s".formatted(adapterId),
              PeaDeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                // The removed key is refused first, at every level it may be set at. The
                // subscriptions no longer read it, and staying silent would hide that.
                PeaFetchVariables.rejectTheRemovedKey(
                    adapterId, supplierContext.bean(VanillaBpPeaProperties.class).fetchVariablesKeys(adapterId));
                final var deploymentService = new PeaDeploymentService(
                    adapterId, supplierContext.bean(DeploymentApi.class), AdapterBeanRegistrarSupport
                        .collaborators(supplierContext, adapterId), supplierContext
                            .bean(
                                TaskSubscriptionApi.class), supplierContext
                                    .bean(
                                        ServiceTaskCompletionApi.class), supplierContext
                                            .bean(
                                                PeaDeployedProcessesRegistry.class)
                                            .forAdapter(adapterId));
                // The observers of the user tasks this adapter receives. They are beans of
                // the application, in the order Spring gives any collected beans. Every
                // adapter id gets the same list, see decision 9 in the repository's
                // DECISIONS.md.
                deploymentService.setUserTaskObservers(
                    supplierContext
                        .beanProvider(PeaUserTaskObserver.class)
                        .orderedStream()
                        .toList());
                return deploymentService;
              }));

        });

  }

}
