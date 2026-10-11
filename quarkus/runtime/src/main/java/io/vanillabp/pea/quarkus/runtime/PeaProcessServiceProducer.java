package io.vanillabp.pea.quarkus.runtime;

import java.util.List;
import java.util.Map;

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.correlation.SignalApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.processservice.PeaProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

/**
 * Provides the {@link PeaProcessService} of this adapter as a CDI bean. The process service
 * beans which the VanillaBP Quarkus integration generates pick it up. The adapter id is
 * resolved from the configuration (first adapter of type {@code process-engine-api}).
 * <p>
 * The interfaces of the Process-Engine-API are injected, one for each interface the API
 * splits its commands into. By default they are all the default bean
 * {@link io.vanillabp.pea.mock.InMemoryProcessEngine}, the mock. The signal API may be
 * missing. Then signals are the operation this adapter refuses.
 */
@ApplicationScoped
public class PeaProcessServiceProducer {

  /**
   * Quarkus builds this bean to call the producer below. It keeps no state. What the producer
   * returns is a bean of its own and lives as long as the application does.
   */
  public PeaProcessServiceProducer() {

  }


  /**
   * What the platform hands the adapter, built the same way for both services of an
   * adapter id.
   *
   * @param adapterId The configured adapter id the collaborators belong to
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param scoping How an identifier is kept apart from the one of another workflow module
   * @param aggregateSync Which aggregate values travel to the engine
   * @param preCommitRegistrar Where a phase-one check is run right before the commit
   * @param workflowEndedInvoker The core's notification of a workflow which ended
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the BPMS started
   * @return The collaborators of that adapter id
   */
  static AdapterCollaborators collaboratorsOf(
      final String adapterId,
      final WorkflowTaskRegistry workflowTaskRegistry,
      final NameClashAvoidanceSupport scoping,
      final WorkflowAggregateSync aggregateSync,
      final PreCommitRegistrar preCommitRegistrar,
      final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return AdapterCollaboratorsSupport
        .collaborators(
            adapterId, workflowTaskRegistry, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar,
            workflowEndedInvoker, bpmsInitiatedStartInvoker);

  }

  /**
   * One process service per configured adapter id of this type, as the element beans the
   * platform collects.
   *
   * @param properties The platform's own configuration, which is where the adapter ids come
   *          from
   * @param startProcessApi Where a workflow is started
   * @param serviceTaskCompletionApi Where a service task is completed or failed
   * @param userTaskCompletionApi Where a user task is completed or canceled
   * @param correlationApi Where a message is correlated
   * @param signalApi Where a signal is broadcast, absent where the engine has no such API
   * @param deployedProcessesRegistry The per-id record shared with the deployment services
   * @param aggregateSync Which aggregate values travel to the engine
   * @param scoping How an identifier is kept apart from the one of another workflow module
   * @param preCommitRegistrar Where a phase-one check is run right before the commit
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param workflowEndedInvoker The core's notification of a workflow which ended, if the
   *          application has a method for it
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the BPMS started,
   *          which this engine cannot report (GAPS entry 16)
   * @return The process services, one per configured adapter id
   */
  @Produces
  public List<MigratableProcessService<Object>> peaMigratableProcessServices(
      final MigrationAdapterProperties properties,
      final StartProcessApi startProcessApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi,
      final CorrelationApi correlationApi,
      @Any final Instance<SignalApi> signalApi,
      final PeaDeployedProcessesRegistry deployedProcessesRegistry,
      final WorkflowAggregateSync aggregateSync,
      final NameClashAvoidanceSupport scoping,
      final PreCommitRegistrar preCommitRegistrar,
      final WorkflowTaskRegistry workflowTaskRegistry,
      @Any final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      @Any final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    // ONE bean of type List, with one instance PER configured adapter id of this type. A
    // CDI producer cannot give N element beans for N ids which are only known at runtime.
    // Where the platform collects the beans, it flattens List beans next to element beans.
    // The ids always come from the core properties of the platform.
    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> PeaAdapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .<MigratableProcessService<Object>>map(
            adapterId -> {
              final var processService = new PeaProcessService<>(
                  adapterId, startProcessApi, serviceTaskCompletionApi, userTaskCompletionApi, correlationApi, deployedProcessesRegistry
                      .forAdapter(adapterId), collaboratorsOf(
                          adapterId, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar,
                          workflowEndedInvoker, bpmsInitiatedStartInvoker));
              // Optional. With an engine which has no SignalApi, signals are not supported,
              // and the process service says so when asked.
              processService
                  .setSignalApi(
                      signalApi.isResolvable()
                          ? signalApi.get()
                          : null);
              return processService;
            })
        .toList();

  }

}
