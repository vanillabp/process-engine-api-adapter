package io.vanillabp.pea.springboot.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.pea.springboot.it.TaskProcessingIntegrationTest;

/**
 * Test application booting the full VanillaBP Spring Boot integration with the
 * Process-Engine-API adapter (mock-backed), a JPA aggregate and the gruelbox-based
 * phase-two outbox, so {@code ProcessService#startWorkflow} exercises the real two-phase
 * start: phase one ({@code PREFLIGHT_CHECK}) inside the transaction, phase two
 * ({@code SYNC}) after commit via the outbox.
 */
@SpringBootApplication
public class OutboxTestApplication {

  /**
   * The aggregate of ANOTHER test of this module, which this application never uses.
   * <p>
   * VanillaBP registers the {@code @WorkflowService} classes it finds for the workflow
   * module on the classpath, and every test of this Maven module shares one classpath and
   * one module id, so this application is asked for a persistence of
   * {@code TaskProcessingIntegrationTest}'s aggregate as well. An aggregate whose
   * persistence cannot be determined ends the startup instead
   * of failing at the first task, which is what this bean answers: it is declared FOR that
   * class, so it does not compete with the JPA persistence this application really uses,
   * and every method fails loudly because nothing here may reach it.
   */
  @Bean
  public AggregatePersistenceAware<TaskProcessingIntegrationTest.PeaTaskAggregate> foreignAggregateIsNotPersistedHere() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<TaskProcessingIntegrationTest.PeaTaskAggregate> getAggregateClass() {
        return TaskProcessingIntegrationTest.PeaTaskAggregate.class;
      }

      @Override
      public TaskProcessingIntegrationTest.PeaTaskAggregate save(
          final TaskProcessingIntegrationTest.PeaTaskAggregate aggregate) {
        throw new UnsupportedOperationException("not persisted by the outbox test application");
      }

      @Override
      public Object getAggregateId(
          final TaskProcessingIntegrationTest.PeaTaskAggregate aggregate) {
        throw new UnsupportedOperationException("not persisted by the outbox test application");
      }

      @Override
      public TaskProcessingIntegrationTest.PeaTaskAggregate loadById(
          final Object aggregateId) {
        throw new UnsupportedOperationException("not persisted by the outbox test application");
      }

      @Override
      public Class<?> getAggregateIdType() {
        // the contract's "not determinable" - nothing here owns this aggregate at all
        return null;
      }

    };

  }

}
