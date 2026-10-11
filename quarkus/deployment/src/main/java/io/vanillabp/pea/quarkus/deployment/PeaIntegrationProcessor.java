package io.vanillabp.pea.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.integration.deployment.pipeline.VanillaBpAdapterDeploymentServiceBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.quarkus.deployment.config.PeaProperties;
import io.vanillabp.pea.quarkus.runtime.PeaDeploymentServiceProducer;
import io.vanillabp.pea.quarkus.runtime.PeaProcessEngineProducer;
import io.vanillabp.pea.quarkus.runtime.PeaProcessServiceProducer;

/**
 * The build steps of the Quarkus extension of the Process-Engine-API adapter. They announce
 * the adapter and its process-service bean to the VanillaBP Quarkus integration. They also
 * register the runtime producers, the default mock engine in memory among them.
 */
class PeaIntegrationProcessor {

  private static final String FEATURE = "vanillabp-process-engine-api";

  /**
   * Announces the adapter type and its
   * {@link io.vanillabp.pea.processservice.PeaProcessService} bean to the VanillaBP Quarkus
   * integration.
   *
   * @param properties Build-time properties (forces config root registration)
   * @param featureProducer Feature build item producer
   * @return The build item describing this adapter's process service
   */
  @BuildStep
  VanillaBpMigratableProcessServiceBuildItem buildProcessServices(
      final PeaProperties properties,
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return VanillaBpMigratableProcessServiceBuildItem
        .builder()
        .adapterType(PeaAdapter.ADAPTER_TYPE)
        // The VanillaBP extension registers the announced bean class. So it has to be the
        // producer, not the process-service class of the core.
        .migratableProcessServiceBeanClass(PeaProcessServiceProducer.class.getName())
        .build();

  }

  /**
   * Builds the {@link VanillaBpAdapterDeploymentServiceBuildItem}. The VanillaBP Quarkus
   * integration uses it to find and register the deployment-service bean of this adapter.
   * The deployment pipeline of the platform then runs that bean at runtime: readBpmn &rarr;
   * prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr; startWorkflowProcessing.
   *
   * @return The {@link VanillaBpAdapterDeploymentServiceBuildItem}
   */
  @BuildStep
  VanillaBpAdapterDeploymentServiceBuildItem buildDeploymentServices() {

    return VanillaBpAdapterDeploymentServiceBuildItem
        .builder()
        .adapterType(PeaAdapter.ADAPTER_TYPE)
        .deploymentServiceBeanClass(PeaDeploymentServiceProducer.class.getName())
        .build();

  }

  /**
   * Registers the runtime producers: the Process-Engine-API adapter's process service and
   * the default in-memory mock engine backing it.
   *
   * @return The bean registration build item
   */
  @BuildStep
  AdditionalBeanBuildItem registerProducers() {

    // The VanillaBP extension registers the producers of the process service and of the
    // deployment service, through the build items above. Only the producer of the mock
    // engine is registered here.
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PeaProcessEngineProducer.class)
        .setUnremovable()
        .build();

  }

  /**
   * Keeps the user-task observers of an application alive. ArC removes beans nobody injects.
   * Nothing of the application injects the observers: the deployment services get them while
   * they are produced.
   *
   * @return What must not be removed
   */
  @BuildStep
  UnremovableBeanBuildItem keepUserTaskObservers() {

    return UnremovableBeanBuildItem.beanTypes(PeaUserTaskObserver.class);

  }

}
