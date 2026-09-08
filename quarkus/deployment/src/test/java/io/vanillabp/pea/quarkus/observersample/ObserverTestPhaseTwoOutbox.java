package io.vanillabp.pea.quarkus.observersample;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A {@link PhaseTwoOutbox} stub - this test delivers user tasks directly through the mock
 * engine and never starts a workflow, so any usage fails loudly.
 */
@ApplicationScoped
public class ObserverTestPhaseTwoOutbox implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    throw new UnsupportedOperationException("no outbox in this test");

  }

}
