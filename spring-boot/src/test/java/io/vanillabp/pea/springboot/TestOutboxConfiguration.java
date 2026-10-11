package io.vanillabp.pea.springboot;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * A {@link PhaseTwoOutbox} stub for smoke tests which boot without a database. The PEA
 * adapter sends everything through phase two, and the platform checks at startup that it
 * finds an outbox. The smoke tests never start a workflow, so any use of the stub fails
 * loudly. Tests with a real database must not import this configuration. They use the
 * default outbox of the platform. The deployment integration test is one of them.
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
   * The transaction runner an application normally brings. These contexts have no
   * persistence with transactions at all. But a two-phase adapter needs a transaction to
   * write the aggregate and the outbox entry in. A runner which just calls the work is
   * enough. Nothing is stored here, and these tests only check the wiring.
   *
   * @return The runner
   */
  @Bean
  public TransactionRunner testTransactionRunner() {

    return new TransactionRunner() {

      @Override
      public <T> T requireNew(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public <T> T inCurrent(
          final Supplier<T> work) {
        return work.get();
      }

      @Override
      public boolean isRollbackOnly() {
        return false;
      }

    };

  }

}
