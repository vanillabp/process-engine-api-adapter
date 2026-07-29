package io.vanillabp.pea.quarkus.runtime;

import io.quarkus.arc.DefaultBean;
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

  @Produces
  @Singleton
  @DefaultBean
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

}
