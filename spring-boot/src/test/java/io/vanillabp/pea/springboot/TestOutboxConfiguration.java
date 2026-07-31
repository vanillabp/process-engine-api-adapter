package io.vanillabp.pea.springboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;

/**
 * Provides a {@link PhaseTwoOutbox} stub for smoke tests booting WITHOUT a database:
 * the PEA adapter requires a two-phase commit for starting workflows, so since story
 * 26i an outbox has to be RESOLVABLE at startup (the platform validates eagerly).
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

}
