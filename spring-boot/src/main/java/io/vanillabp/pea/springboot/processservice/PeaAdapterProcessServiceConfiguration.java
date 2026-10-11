package io.vanillabp.pea.springboot.processservice;


import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.springboot.PeaAdapterBeanRegistrar;
import io.vanillabp.pea.springboot.VanillaBpPeaProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides the {@link MigratableProcessService} beans of the Process-Engine-API adapter. The
 * {@link io.vanillabp.spi.process.ProcessService} beans of the VanillaBP Spring Boot
 * integration pick them up.
 * <p>
 * The Process-Engine-API implementation comes in here. By default this class adds an
 * {@link InMemoryProcessEngine} bean, guarded by {@link ConditionalOnMissingBean}. So tests
 * and early applications run against the mock.
 * A real Process-Engine-API implementation can replace it later simply by defining an own
 * bean of the respective API interfaces.
 */
@AutoConfiguration
@Slf4j
@Import(PeaAdapterBeanRegistrar.class)
@EnableConfigurationProperties(VanillaBpPeaProperties.class)
public class PeaAdapterProcessServiceConfiguration {

  /**
   * Spring Boot builds the class to read the bean methods below.
   */
  public PeaAdapterProcessServiceConfiguration() {

  }

  /**
   * The default Process-Engine-API implementation, an in-memory mock. An application with a
   * real Process-Engine-API implementation replaces it with a bean of its own.
   *
   * @return The mock engine used by default
   */
  @Bean
  @ConditionalOnMissingBean
  public InMemoryProcessEngine peaInMemoryProcessEngine() {

    // No property picks the engine, so a startup check has nothing to read. This warning
    // is the only place where the mock says that it runs. An application which misses it
    // loses its workflows at the next shutdown.
    log.warn(
        """
            The IN-MEMORY MOCK is the active Process-Engine-API implementation: all workflow state is \
            VOLATILE and lost on shutdown! To plug a real engine, define beans implementing the \
            Process-Engine-API interfaces (e.g. dev.bpmcrafters.processengineapi.process.StartProcessApi, \
            ...deploy.DeploymentApi) - the mock backs off automatically.""");
    return new InMemoryProcessEngine();

  }


}
