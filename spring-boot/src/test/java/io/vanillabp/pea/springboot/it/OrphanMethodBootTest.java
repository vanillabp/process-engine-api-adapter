package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.pea.springboot.PeaAdapterConfiguration;
import io.vanillabp.pea.springboot.TestOutboxConfiguration;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.pea.springboot.processservice.PeaAdapterProcessServiceConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The direction of the wiring check this adapter never called: a {@code @WorkflowTask}
 * method whose task definition appears in no BPMN of the module. Since story 158 the core
 * runs that check itself, once every adapter of the module deployed, so an adapter which
 * only reports what it deployed still gets it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OrphanMethodBootTest {

  /**
   * The beans this boot needs and nothing else: no component scan, so the workflow
   * services of the other tests in this package stay out, and no auto-configured outbox
   * competes with the double.
   */
  private static final Class<?>[] SOURCES = {
      PeaAdapterConfiguration.class, PeaAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, DeploymentAutoConfiguration.class, TestPersistenceConfiguration.class, TestOutboxConfiguration.class, OrphanMethodConfiguration.class, OrphanMethodWorkflowService.class
  };

  public static class OrphanMethodAggregate {

    String id;

    public String getId() {
      return id;
    }

  }

  /**
   * Enough persistence for the aggregate to be claimed: the boot never gets far enough to
   * load or save anything, it fails while the module is being wired.
   */
  @Configuration
  public static class OrphanMethodConfiguration {

    @Bean
    AggregatePersistenceAware<OrphanMethodAggregate> orphanMethodPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<OrphanMethodAggregate> getAggregateClass() {
          return OrphanMethodAggregate.class;
        }

        @Override
        public OrphanMethodAggregate save(
            final OrphanMethodAggregate aggregate) {
          throw new UnsupportedOperationException("no persistence in this test");
        }

        @Override
        public Object getAggregateId(
            final OrphanMethodAggregate aggregate) {
          return aggregate.id;
        }

        @Override
        public String getAggregateIdName() {
          return "id";
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public OrphanMethodAggregate loadById(
            final Object aggregateId) {
          throw new UnsupportedOperationException("no persistence in this test");
        }

      };

    }

  }

  /**
   * One method serving the task of <code>pea-orphan-test.bpmn</code> and one whose task
   * definition nobody modelled - a typo, or a method left behind after a model change.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = OrphanMethodAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "PeaOrphanProcess"))
  public static class OrphanMethodWorkflowService {

    @WorkflowTask
    public void orphanModelled() {

    }

    @WorkflowTask(taskDefinition = "activityNobodyModelled")
    public void orphanTypo() {

    }

  }

  @Test
  @DisplayName("A @WorkflowTask method matching no task of the module aborts the boot")
  public void orphanWorkflowTaskMethodAbortsBoot() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(SOURCES)
            .run(
                "--vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/orphan")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("orphanTypo"), "unexpected message: "
        + message);
    assertTrue(message.contains("activityNobodyModelled"), "unexpected message: "
        + message);
    assertTrue(message.contains("fix the annotation"), "unexpected message: "
        + message);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
