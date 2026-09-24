package io.vanillabp.pea.quarkus.runtime;

import io.quarkus.arc.DefaultBean;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Produces the default Process-Engine-API implementation: an in-memory mock. The bean is a
 * {@link DefaultBean}, so an application bringing a real Process-Engine-API implementation
 * (providing beans of the respective API interfaces) transparently replaces it.
 * <p>
 * The single {@link InMemoryProcessEngine} instance implements all Process-Engine-API
 * interfaces the adapter needs, therefore it is injectable wherever any of these interfaces
 * is required.
 */
@ApplicationScoped
@Slf4j
public class PeaProcessEngineProducer {

  /**
   * Quarkus builds the bean to call the producer below. It keeps no state: what the
   * producer returns is a bean of its own and lives as long as the application does.
   */
  public PeaProcessEngineProducer() {

  }

  /**
   * The engine an application which brought none runs on. It warns while it does, because
   * everything it holds is gone with the process and nothing else says so.
   *
   * @return The in-memory fake, serving every Process-Engine-API interface at once
   */
  @Produces
  @Singleton
  @DefaultBean
  public InMemoryProcessEngine peaInMemoryProcessEngine() {

    // no property picks the engine, so there is no configuration a startup check could
    // read. This warning is the only place where the mock says that it is the one
    // running, and an application which misses it loses its workflows at the next
    // shutdown
    log.warn(
        """
            The IN-MEMORY MOCK is the active Process-Engine-API implementation: all workflow state is \
            VOLATILE and lost on shutdown! To plug a real engine, define beans implementing the \
            Process-Engine-API interfaces (e.g. dev.bpmcrafters.processengineapi.process.StartProcessApi, \
            ...deploy.DeploymentApi) - the mock backs off automatically.""");
    return new InMemoryProcessEngine();

  }


  /**
   * The per-adapter-id record of what this application version deployed - shared
   * between the deployment services (which fill it) and the process services
   * (which serve the viewer API from it).
   *
   * @return The registry
   */
  @Produces
  @Singleton
  public PeaDeployedProcessesRegistry peaDeployedProcessesRegistry() {

    return new PeaDeployedProcessesRegistry();

  }

}
