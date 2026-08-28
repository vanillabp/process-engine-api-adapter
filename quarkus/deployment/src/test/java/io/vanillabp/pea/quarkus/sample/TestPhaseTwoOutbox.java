package io.vanillabp.pea.quarkus.sample;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A {@link PhaseTwoOutbox} stub for smoke tests booting WITHOUT a database: the
 * Process-Engine-API adapter sends everything through phase two, and the
 * platform wants the outbox RESOLVABLE at startup rather than at the first start. The
 * smoke tests never start workflows - any usage of the stub fails loudly.
 */
@ApplicationScoped
public class TestPhaseTwoOutbox implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    throw new UnsupportedOperationException("no outbox in this test");

  }

}
