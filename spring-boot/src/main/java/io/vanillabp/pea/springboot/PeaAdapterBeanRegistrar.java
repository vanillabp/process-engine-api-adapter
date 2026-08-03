package io.vanillabp.pea.springboot;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Registers the Process-Engine-API adapter's per-adapter-id beans: for EACH configured
 * adapter id of type {@code process-engine-api} one {@link PeaProcessService}
 * <i>element</i> bean and one {@link PeaDeploymentService} <i>element</i> bean are
 * registered - never beans of type {@code List<...>}: the platform collects element
 * beans via {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is a
 * CONSTRUCTOR parameter of each instance. The bean suppliers are lazy: the
 * Process-Engine-API beans (by default the in-memory mock) are resolved through the
 * {@code SupplierContext} at bean-creation time.
 */
public class PeaAdapterBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        PeaAdapter.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "Pea_ProcessService_%s".formatted(adapterId),
              PeaProcessService.class,
              spec -> spec.supplier(supplierContext -> new PeaProcessService<>(
                  adapterId, supplierContext.bean(StartProcessApi.class))));

          registry.registerBean(
              "Pea_DeploymentService_%s".formatted(adapterId),
              PeaDeploymentService.class,
              spec -> spec.supplier(supplierContext -> new PeaDeploymentService(
                  adapterId, supplierContext.bean(DeploymentApi.class), supplierContext
                      .bean(
                          io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker.class), supplierContext
                              .bean(dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi.class), supplierContext
                                  .bean(dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi.class))));

        });

  }

}
