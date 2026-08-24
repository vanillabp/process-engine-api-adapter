package io.vanillabp.pea.springboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * Provides a {@link PhaseTwoOutbox} stub for smoke tests booting WITHOUT a database:
 * the PEA adapter requires a two-phase commit for starting workflows, and the platform
 * wants the outbox RESOLVABLE at startup, validated eagerly.
 * The smoke tests never start workflows - any usage of the stub fails loudly. Tests
 * with a real database (e.g. the deployment integration test) must NOT import this
 * configuration: they use the platform's default outbox.
 */
@Configuration
public class TestOutboxConfiguration {

  @Bean
  public PhaseTwoOutbox testPhaseTwoOutbox() {

    return call -> {
      throw new UnsupportedOperationException("no outbox in this test");
    };

  }

  /**
   * The unit of work the application brings: these contexts have no
   * transactional persistence at all, and a two-phase adapter needs a transaction to write
   * the aggregate and the outbox entry in. A pass-through runner is enough - nothing is
   * persisted here, what these tests pin is the wiring.
   *
   * @return The runner
   */
  @Bean
  public io.vanillabp.integration.spi.TransactionRunner testTransactionRunner() {

    return new io.vanillabp.integration.spi.TransactionRunner() {

      @Override
      public <T> T requireNew(
          final java.util.function.Supplier<T> work) {
        return work.get();
      }

      @Override
      public <T> T inCurrent(
          final java.util.function.Supplier<T> work) {
        return work.get();
      }

      @Override
      public boolean isRollbackOnly() {
        return false;
      }

    };

  }

}
