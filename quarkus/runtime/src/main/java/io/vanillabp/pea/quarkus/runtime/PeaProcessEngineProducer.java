package io.vanillabp.pea.quarkus.runtime;

import io.quarkus.arc.DefaultBean;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Produces the default implementation of the Process-Engine-API: a mock which keeps all in
 * memory. The bean is a {@link DefaultBean}. An application which brings a real
 * implementation, as beans of the API interfaces, replaces it without further work.
 * <p>
 * The one {@link InMemoryProcessEngine} instance implements every interface of the API the
 * adapter needs. So it can be injected wherever one of these interfaces is asked for.
 */
@ApplicationScoped
@Slf4j
public class PeaProcessEngineProducer {

  /**
   * Quarkus builds this bean to call the producer below. It keeps no state. What the producer
   * returns is a bean of its own and lives as long as the application does.
   */
  public PeaProcessEngineProducer() {

  }

  /**
   * The engine an application runs on if it brought none. It warns when it is used. All it
   * holds is gone when the process ends, and nothing else says so.
   *
   * @return The in-memory fake, serving every Process-Engine-API interface at once
   */
  @Produces
  @Singleton
  @DefaultBean
  public InMemoryProcessEngine peaInMemoryProcessEngine() {

    // No property picks the engine, so a startup check has no configuration to read. This
    // warning is the only place where the mock says that it is the one running. An
    // application which misses it loses its workflows at the next shutdown.
    log.warn(
        """
            The IN-MEMORY MOCK is the active Process-Engine-API implementation: all workflow state is \
            VOLATILE and lost on shutdown! To plug a real engine, define beans implementing the \
            Process-Engine-API interfaces (e.g. dev.bpmcrafters.processengineapi.process.StartProcessApi, \
            ...deploy.DeploymentApi) - the mock backs off automatically.""");
    return new InMemoryProcessEngine();

  }


  /**
   * The record of what this application version deployed, one per adapter id. The
   * deployment services fill it. The process services answer the viewer API from it.
   *
   * @return The registry
   */
  @Produces
  @Singleton
  public PeaDeployedProcessesRegistry peaDeployedProcessesRegistry() {

    return new PeaDeployedProcessesRegistry();

  }

}
