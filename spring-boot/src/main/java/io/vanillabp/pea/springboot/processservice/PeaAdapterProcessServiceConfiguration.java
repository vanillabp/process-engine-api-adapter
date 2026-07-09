package io.vanillabp.pea.springboot.processservice;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import dev.bpmcrafters.processengineapi.correlation.CorrelationApi;
import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.UserTaskCompletionApi;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Provides the Process-Engine-API adapter's {@link MigratableProcessService} bean picked up
 * by the {@link io.vanillabp.spi.process.ProcessService} beans built by the VanillaBP Spring
 * Boot integration.
 * <p>
 * The Process-Engine-API implementation is injected here. By default a mock-backed
 * {@link InMemoryProcessEngine} bean is contributed (guarded by
 * {@link ConditionalOnMissingBean}), so tests and early applications run against the mock.
 * A real Process-Engine-API implementation can replace it later simply by defining an own
 * bean of the respective API interfaces.
 */
@AutoConfiguration
public class PeaAdapterProcessServiceConfiguration {

  /**
   * The default Process-Engine-API implementation: an in-memory mock. Applications that
   * bring a real Process-Engine-API implementation override this by defining their own
   * bean.
   *
   * @return The mock engine used by default
   */
  @Bean
  @ConditionalOnMissingBean
  public InMemoryProcessEngine peaInMemoryProcessEngine() {

    return new InMemoryProcessEngine();

  }

  @Bean
  public MigratableProcessService<?> peaMigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties,
      final StartProcessApi startProcessApi,
      final CorrelationApi correlationApi,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final UserTaskCompletionApi userTaskCompletionApi) {

    final var adapterId = properties
        .getObject()
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> PeaAdapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new PeaProcessService<>(
        adapterId, startProcessApi, correlationApi, taskSubscriptionApi, serviceTaskCompletionApi, userTaskCompletionApi);

  }

}
