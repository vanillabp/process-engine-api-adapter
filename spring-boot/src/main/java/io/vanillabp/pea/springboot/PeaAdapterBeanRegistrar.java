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

    // ONE registry for all adapter ids: it hands out the per-id record of
    // deployed processes shared by the deployment and the process service (the
    // viewer API's only source of definitions and BPMN XML - see GAPS.md)
    registry.registerBean(
        "Pea_DeployedProcessesRegistry",
        io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry.class,
        spec -> spec.supplier(supplierContext -> new io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry()));

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
                        .bean(dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi.class), supplierContext
                            .bean(dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi.class), supplierContext
                                .bean(
                                    dev.bpmcrafters.processengineapi.correlation.CorrelationApi.class), supplierContext
                                        .bean(io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry.class)
                                        .forAdapter(adapterId), supplierContext
                                            .bean(io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.class));
                processService.setScoping(
                    supplierContext.bean(io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.class));
                // Phase-one checks run right before the transaction of the
                // aggregate commits, in whatever unit of work that is
                processService.setPreCommitRegistrar(
                    supplierContext.bean(io.vanillabp.integration.adapter.spi.PreCommitRegistrar.class));
                // optional: an engine implementation without a SignalApi leaves
                // signals unsupported, which the process service says when asked
                processService.setSignalApi(
                    supplierContext
                        .beanProvider(dev.bpmcrafters.processengineapi.correlation.SignalApi.class)
                        .getIfAvailable());
                return processService;
              }));

          registry.registerBean(
              "Pea_DeploymentService_%s".formatted(adapterId),
              PeaDeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                final var deploymentService = new PeaDeploymentService(
                    adapterId, supplierContext.bean(DeploymentApi.class), supplierContext
                        .bean(
                            io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring.class), supplierContext
                                .bean(
                                    io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker.class), supplierContext
                                        .bean(
                                            dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi.class), supplierContext
                                                .bean(
                                                    dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi.class), supplierContext
                                                        .bean(
                                                            io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry.class)
                                                        .forAdapter(adapterId));
                deploymentService.setScoping(
                    supplierContext.bean(io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.class));
                // What each subscription asks the engine for, resolvable down
                // to task level
                final var overlay = supplierContext.bean(VanillaBpPeaProperties.class);
                deploymentService.setFetchVariablesResolver((
                    workflowModuleId,
                    bpmnProcessId,
                    taskDefinition) -> overlay.fetchVariablesFor(
                        workflowModuleId, bpmnProcessId, taskDefinition, adapterId));
                deploymentService.setWorkflowEndedInvoker(
                    supplierContext
                        .beanProvider(
                            io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker.class)
                        .getIfAvailable());
                return deploymentService;
              }));

        });

  }

}
