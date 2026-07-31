package io.vanillabp.pea.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.integration.deployment.pipeline.VanillaBpAdapterDeploymentServiceBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.quarkus.deployment.config.PeaProperties;
import io.vanillabp.pea.quarkus.runtime.PeaDeploymentServiceProducer;
import io.vanillabp.pea.quarkus.runtime.PeaProcessEngineProducer;
import io.vanillabp.pea.quarkus.runtime.PeaProcessServiceProducer;

/**
 * Quarkus extension deployment of the Process-Engine-API adapter. Announces the adapter and
 * its process-service bean to the VanillaBP Quarkus integration and registers the runtime
 * producers (including the default in-memory mock engine).
 */
class PeaIntegrationProcessor {

  private static final String FEATURE = "vanillabp-process-engine-api";

  /**
   * Announces the Process-Engine-API adapter type and its {@link PeaProcessService} bean to
   * the VanillaBP Quarkus integration.
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
        // the announced bean class is registered by the VanillaBP extension - it
        // has to be the producer, not the core process-service class
        .migratableProcessServiceBeanClass(PeaProcessServiceProducer.class.getName())
        .build();

  }

  /**
   * Builds the {@link VanillaBpAdapterDeploymentServiceBuildItem} used by the VanillaBP
   * Quarkus integration to determine and register the deployment-service bean of the
   * Process-Engine-API adapter - consumed by the platform's runtime deployment pipeline
   * (readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
   * startWorkflowProcessing).
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

    // the process-service and deployment-service producers are registered by the
    // VanillaBP extension via the build items above - only the mock-engine producer
    // is registered here
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PeaProcessEngineProducer.class)
        .setUnremovable()
        .build();

  }

}
