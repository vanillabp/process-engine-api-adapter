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
import io.vanillabp.pea.wiring.PeaFetchVariables;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link PeaDeploymentService} instances of this adapter, ONE per configured
 * adapter id of type {@code process-engine-api}. The deployment pipeline of the VanillaBP
 * Quarkus integration uses them at runtime. The producer returns a list, because a CDI
 * producer cannot give N element beans for N ids which are only known at runtime. The
 * {@link DeploymentApi} is injected. By default it is the default bean
 * {@link io.vanillabp.pea.mock.InMemoryProcessEngine}, the mock.
 * <p>
 * What the platform expects: the element type of the list is the SPI interface with BOTH
 * type parameters set to {@code Object}. That is so even though the adapter has its own model
 * ({@code PeaBpmnModel}) and context ({@code PeaProcessingContext}) classes. CDI does not match
 * parameterized types with different type arguments the same way in every mode. So the
 * platform looks the beans up with the exact type. The pipeline matches models through
 * {@code getModelType()} and {@code getProcessContextType()}, never through the generics.
 * The producer method is {@code @Singleton}, because CDI cannot put a client proxy in front
 * of a deployment service.
 */
@ApplicationScoped
public class PeaDeploymentServiceProducer {

  /**
   * Quarkus builds this bean to call the producer below. It keeps no state. What the producer
   * returns is a bean of its own and lives as long as the application does.
   */
  public PeaDeploymentServiceProducer() {

  }

  /**
   * One deployment service per configured adapter id of this type, in the list the platform
   * looks the beans up by.
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

    // The observers of the user tasks this adapter is delivered are CDI beans of the
    // application. They are resolved once and handed to every configured adapter id, see
    // decision 9 in the repository's DECISIONS.md. ArC removes a bean nothing injects. That
    // is why the extension declares this type unremovable.
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
          // A removed key is refused before anything else, wherever it is set. The
          // subscriptions no longer read it, and saying nothing would hide that.
          PeaFetchVariables.rejectTheRemovedKey(adapterId, overlay.fetchVariablesKeys(adapterId));
          final var deploymentService = new PeaDeploymentService(
              adapterId, deploymentApi, PeaProcessServiceProducer
                  .collaboratorsOf(
                      adapterId, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar, workflowEndedInvoker,
                      bpmsInitiatedStartInvoker), taskSubscriptionApi, serviceTaskCompletionApi, deployedProcessesRegistry
                          .forAdapter(adapterId));
          deploymentService.setUserTaskObservers(observers);
          return deploymentService;
        })
        .toList();

  }

}
