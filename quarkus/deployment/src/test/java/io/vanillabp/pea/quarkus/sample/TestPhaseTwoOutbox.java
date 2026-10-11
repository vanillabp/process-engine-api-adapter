package io.vanillabp.pea.quarkus.sample;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A stub of {@link PhaseTwoOutbox} for smoke tests which boot WITHOUT a database. This
 * adapter sends everything through phase two. The platform wants the outbox RESOLVABLE at
 * startup, not only at the first start. The smoke tests never start workflows, so any use of
 * the stub fails loudly.
 */
@ApplicationScoped
public class TestPhaseTwoOutbox implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    throw new UnsupportedOperationException("no outbox in this test");

  }

}
