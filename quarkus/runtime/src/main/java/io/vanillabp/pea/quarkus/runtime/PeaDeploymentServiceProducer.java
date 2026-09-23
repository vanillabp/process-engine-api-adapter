package io.vanillabp.pea.quarkus.runtime;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.deployment.PeaDeploymentService;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
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

  /**
   * Quarkus builds the bean to call the producer below. It keeps no state: what the
   * producer returns is a bean of its own and lives as long as the application does.
   */
  public PeaDeploymentServiceProducer() {

  }

  /**
   * One deployment service per configured adapter id of this type, as the list the platform
   * looks the beans up as.
   *
   * @param properties The platform's own configuration, which is where the adapter ids come
   *          from
   * @param deploymentApi Where a module's resources are handed to the engine
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param taskSubscriptionApi Where the subscriptions for the deployed tasks are opened
   * @param serviceTaskCompletionApi What a delivered service task is completed through
   * @param deployedProcessesRegistry The per-id record shared with the process services
   * @param scoping How an identifier is kept apart from the one of another workflow module
   * @param aggregateSync Which aggregate values travel to the engine
   * @param preCommitRegistrar Where a phase-one check is run right before the commit
   * @param workflowEndedInvoker The core's notification of a workflow which ended, if the
   *          application has a method for it
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the BPMS started,
   *          which this engine cannot report (GAPS entry 16)
   * @param userTaskObservers Who watches the delivered user tasks
   * @return The deployment services, one per configured adapter id
   */
  @Produces
  @Singleton
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  public List<AdapterDeploymentService<Object, Object>> peaAdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      final DeploymentApi deploymentApi,
      final WorkflowTaskRegistry workflowTaskRegistry,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final PeaDeployedProcessesRegistry deployedProcessesRegistry,
      final NameClashAvoidanceSupport scoping,
      final WorkflowAggregateSync aggregateSync,
      final PreCommitRegistrar preCommitRegistrar,
      @Any final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      @Any final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker,
      @Any final Instance<PeaUserTaskObserver> userTaskObservers) {

    // who watches the user tasks this adapter is delivered: CDI beans of the application,
    // resolved once and handed to every configured adapter id - see decision 9 in the
    // repository's DECISIONS.md. ArC removes a bean nothing injects, which is why the
    // extension declares this type unremovable
    final var observers = userTaskObservers
        .stream()
        .toList();

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
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
              adapterId, deploymentApi, PeaProcessServiceProducer
                  .collaboratorsOf(
                      adapterId, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar, workflowEndedInvoker,
                      bpmsInitiatedStartInvoker), taskSubscriptionApi, serviceTaskCompletionApi, deployedProcessesRegistry
                          .forAdapter(adapterId));
          // What each subscription asks the engine for, resolvable down to
          // task level
          deploymentService.setFetchVariablesResolver((
              workflowModuleId,
              bpmnProcessId,
              taskDefinition) -> overlay.fetchVariablesFor(
                  workflowModuleId, bpmnProcessId, taskDefinition, adapterId));
          deploymentService.setUserTaskObservers(observers);
          return deploymentService;
        })
        .toList();

  }

}
