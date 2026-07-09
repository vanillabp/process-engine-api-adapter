package io.vanillabp.pea.quarkus.runtime;

import io.quarkus.arc.DefaultBean;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

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
public class PeaProcessEngineProducer {

  @Produces
  @Singleton
  @DefaultBean
  public InMemoryProcessEngine peaInMemoryProcessEngine() {

    return new InMemoryProcessEngine();

  }

}
