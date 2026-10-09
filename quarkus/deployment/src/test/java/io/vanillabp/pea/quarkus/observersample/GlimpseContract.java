package io.vanillabp.pea.quarkus.observersample;

import java.util.List;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Registers the contract of {@link Glimpse} while the application starts. An observer of the
 * default priority runs before VanillaBP deploys, so the adapter knows the extension's
 * variables when it opens its subscriptions. That is how the Business Cockpit registers its
 * contracts as well.
 */
@ApplicationScoped
public class GlimpseContract {

  void register(
      @Observes final StartupEvent event,
      final ExtensionHandlers handlers) {

    handlers
        .register(HandlerContract
            .of("glimpsing", Glimpse.class)
            .lookupKeys(annotation -> List.of(((Glimpse) annotation).element()))
            .coreParameters(CoreHandlerParameter.TASK_PARAM)
            .build());

  }

}
