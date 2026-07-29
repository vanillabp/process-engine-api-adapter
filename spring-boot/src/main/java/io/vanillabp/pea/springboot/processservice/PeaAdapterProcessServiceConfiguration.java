package io.vanillabp.pea.springboot.processservice;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import dev.bpmcrafters.processengineapi.process.StartProcessApi;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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

    // full startup config validation is a later story - this single warning is the
    // safety net against accidentally running the volatile mock in production
    log.warn(
        """
            The IN-MEMORY MOCK is the active Process-Engine-API implementation: all workflow state is \
            VOLATILE and lost on shutdown! To plug a real engine, define beans implementing the \
            Process-Engine-API interfaces (e.g. dev.bpmcrafters.processengineapi.process.StartProcessApi, \
            ...deploy.DeploymentApi) - the mock backs off automatically.""");
    return new InMemoryProcessEngine();

  }

  /**
   * Only the Process-Engine-APIs the adapter actually uses are injected (currently
   * {@link StartProcessApi}); upcoming stories add theirs when they consume them.
   */
  @Bean
  public MigratableProcessService<?> peaMigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties,
      final StartProcessApi startProcessApi) {

    final var adapterId = properties
        .getObject()
        .getAdapters()
        .entrySet()
        .stream()
        .filter(adapter -> PeaAdapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new PeaProcessService<>(adapterId, startProcessApi);

  }

}
