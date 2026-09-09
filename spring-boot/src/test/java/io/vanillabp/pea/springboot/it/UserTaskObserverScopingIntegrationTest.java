package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * What an observer is handed under <code>name-clash-avoidance: use-prefix</code>, where the
 * deployed bytes carry scoped identifiers and the engine reports them: the observation names
 * the PLAIN BPMN process and the PLAIN task definition, which are what the application's BPMN
 * and its configuration use (decision 2 in the repository's DECISIONS.md).
 * <p>
 * The same values reach the core, so the <code>&#64;WorkflowTask</code> notification of the
 * scoped delivery runs - the core's registries are keyed by the plain identifiers.
 */
@SpringBootTest(
    classes = UserTaskObserverScopingIntegrationTest.ScopedObserverApplication.class,
    properties = {
        "vanillabp.adapters.pea.type=process-engine-api", "vanillabp.adapters.pea.name-clash-avoidance=use-prefix", "vanillabp.prioritized-adapters=pea", "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/observer"
    })
@ExtendWith(SuppressOutputExtension.class)
public class UserTaskObserverScopingIntegrationTest {

  private static final String MODULE = "pea-test-module";

  private static final String PROCESS = "PeaObserverProcess";

  private static final String TASK_DEFINITION = "peaObserved";

  /**
   * What the engine knows the process as: the workflow module in front, joined by the
   * platform's separator.
   */
  private static final String SCOPED_PROCESS = MODULE + NameClashAvoidanceSupport.SEPARATOR + PROCESS;

  /**
   * And the task definition, which is scoped per BPMN process by default.
   */
  private static final String SCOPED_TASK_DEFINITION = MODULE + NameClashAvoidanceSupport.SEPARATOR + PROCESS + NameClashAvoidanceSupport.SEPARATOR + TASK_DEFINITION;

  @SpringBootApplication
  @Import({
      TestPersistenceConfiguration.class, ScopedObserverConfiguration.class, ScopedObserverWorkflowService.class
  })
  public static class ScopedObserverApplication {
  }

  public static class ScopedAggregate {

    String id;

    String results;

  }

  @Configuration
  public static class ScopedObserverConfiguration {

    static final Map<String, ScopedAggregate> AGGREGATES = new ConcurrentHashMap<>();

    static final List<PeaUserTaskObservation> DELIVERED = new ArrayList<>();

    static final List<PeaUserTaskObservation> TERMINATED = new ArrayList<>();

    private static ScopedAggregate copyOf(
        final ScopedAggregate aggregate) {

      final var copy = new ScopedAggregate();
      copy.id = aggregate.id;
      copy.results = aggregate.results;
      return copy;

    }

    @Bean
    PeaUserTaskObserver scopedRecordingObserver() {

      return new PeaUserTaskObserver() {

        @Override
        public void userTaskDelivered(
            final PeaUserTaskObservation observation) {
          DELIVERED.add(observation);
        }

        @Override
        public void userTaskTerminated(
            final PeaUserTaskObservation observation) {
          TERMINATED.add(observation);
        }

      };

    }

    @Bean
    AggregatePersistenceAware<ScopedAggregate> scopedPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<ScopedAggregate> getAggregateClass() {
          return ScopedAggregate.class;
        }

        @Override
        public ScopedAggregate save(
            final ScopedAggregate aggregate) {
          AGGREGATES.put(aggregate.id, copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final ScopedAggregate aggregate) {
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
        public ScopedAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    @Bean
    DataSource scopedObserverDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource scopedObserverDataSource) {

      return new DataSourceTransactionManager(scopedObserverDataSource);

    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = ScopedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class ScopedObserverWorkflowService {

    @WorkflowTask(taskDefinition = TASK_DEFINITION)
    public void peaObservedNotification(
        final ScopedAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.results = "notified:"
          + taskId;

    }

  }

  @Autowired
  private InMemoryProcessEngine engine;

  @BeforeEach
  public void seedAggregates() {

    ScopedObserverConfiguration.AGGREGATES.clear();
    ScopedObserverConfiguration.DELIVERED.clear();
    ScopedObserverConfiguration.TERMINATED.clear();
    engine.clearTaskRecordings();
    final var aggregate = new ScopedAggregate();
    aggregate.id = "6001";
    ScopedObserverConfiguration.AGGREGATES.put(aggregate.id, aggregate);

  }

  @Test
  @DisplayName("The subscription is opened under the SCOPED task definition")
  public void subscriptionOpenedUnderTheScopedKey() {

    final var subscribed = engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();
    assertTrue(
        subscribed.contains(SCOPED_TASK_DEFINITION),
        "expected the scoped subscription key but got: "
            + subscribed);

  }

  @Test
  @DisplayName("A scoped delivery is observed with the plain BPMN process and task definition")
  public void aScopedDeliveryIsObservedPlain() {

    engine.deliverTask("scoped-1", SCOPED_TASK_DEFINITION, SCOPED_PROCESS, Map.of("id", "6001"));

    assertEquals(1, ScopedObserverConfiguration.DELIVERED.size());
    final var observation = ScopedObserverConfiguration.DELIVERED.getFirst();
    assertEquals(MODULE, observation.workflowModuleId());
    assertEquals(PROCESS, observation.bpmnProcessId(), "the observation names the plain process");
    assertEquals(
        TASK_DEFINITION,
        observation.taskDefinition(),
        "the observation names the plain task definition, not the subscription's key");
    assertEquals("6001", observation.workflowAggregateId());

    // the same plain values reach the core, which is what makes the notification run at all
    assertEquals("notified:scoped-1", ScopedObserverConfiguration.AGGREGATES.get("6001").results);

  }

  @Test
  @DisplayName("A scoped termination is observed with the plain identifiers too")
  public void aScopedTerminationIsObservedPlain() {

    engine.deliverTask("scoped-2", SCOPED_TASK_DEFINITION, SCOPED_PROCESS, Map.of("id", "6001"));
    engine.terminateTask("scoped-2", SCOPED_TASK_DEFINITION, SCOPED_PROCESS, TaskInformation.DELETE);

    assertEquals(1, ScopedObserverConfiguration.TERMINATED.size());
    final var observation = ScopedObserverConfiguration.TERMINATED.getFirst();
    assertEquals(PROCESS, observation.bpmnProcessId());
    assertEquals(TASK_DEFINITION, observation.taskDefinition());
    assertEquals(TaskInformation.DELETE, observation.reason());

  }

}
