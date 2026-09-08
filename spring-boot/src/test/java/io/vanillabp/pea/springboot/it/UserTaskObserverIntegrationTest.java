package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
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
 * The user-task observer seam on Spring Boot, end to end against the in-memory engine: beans
 * of the application are collected at startup and every delivery of every user-task
 * subscription reaches them - the ones a {@code @WorkflowTask} method claims and the ones it
 * does not - as does the termination the engine reports, with the reason it gave. An observer
 * which throws costs neither the task nor the observers behind it.
 */
@SpringBootTest(
    classes = UserTaskObserverIntegrationTest.UserTaskObserverApplication.class,
    properties = {
        "vanillabp.adapters.pea.type=process-engine-api", "vanillabp.adapters.pea.name-clash-avoidance=none", "vanillabp.prioritized-adapters=pea", "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/observer"
    })
@ExtendWith(SuppressOutputExtension.class)
public class UserTaskObserverIntegrationTest {

  private static final String MODULE = "pea-test-module";

  private static final String PROCESS = "PeaObserverProcess";

  // the test's nested classes are excluded from Spring's component scan
  // (TestTypeExcludeFilter) - configuration and workflow service are imported explicitly
  @SpringBootApplication
  @Import({
      TestPersistenceConfiguration.class, UserTaskObserverConfiguration.class, ObserverWorkflowService.class
  })
  public static class UserTaskObserverApplication {
  }

  public static class ObserverAggregate {

    String id;

    String results;

  }

  /**
   * What an observer was told, in the order it was told.
   */
  public static class ObservationLog {

    final List<PeaUserTaskObservation> delivered = new ArrayList<>();

    final List<PeaUserTaskObservation> terminated = new ArrayList<>();

    void clear() {

      delivered.clear();
      terminated.clear();

    }

  }

  /**
   * A bean of the application watching along - the shape a cockpit extension registers.
   */
  public static class RecordingObserver implements PeaUserTaskObserver {

    private final ObservationLog log;

    RecordingObserver(
        final ObservationLog log) {

      this.log = log;

    }

    @Override
    public void userTaskDelivered(
        final PeaUserTaskObservation observation) {

      log.delivered.add(observation);

    }

    @Override
    public void userTaskTerminated(
        final PeaUserTaskObservation observation) {

      log.terminated.add(observation);

    }

  }

  /**
   * Registered FIRST, so what the two behind it see proves that a broken observer stops
   * nothing.
   */
  @Order(1)
  public static class ThrowingObserver implements PeaUserTaskObserver {

    @Override
    public void userTaskDelivered(
        final PeaUserTaskObservation observation) {

      throw new IllegalStateException("boom-observer");

    }

    @Override
    public void userTaskTerminated(
        final PeaUserTaskObservation observation) {

      throw new IllegalStateException("boom-observer");

    }

  }

  @Configuration
  public static class UserTaskObserverConfiguration {

    static final Map<String, ObserverAggregate> AGGREGATES = new ConcurrentHashMap<>();

    static final ObservationLog FIRST = new ObservationLog();

    static final ObservationLog SECOND = new ObservationLog();

    private static ObserverAggregate copyOf(
        final ObserverAggregate aggregate) {

      final var copy = new ObserverAggregate();
      copy.id = aggregate.id;
      copy.results = aggregate.results;
      return copy;

    }

    @Bean
    @Order(1)
    PeaUserTaskObserver throwingObserver() {

      return new ThrowingObserver();

    }

    @Bean
    @Order(2)
    PeaUserTaskObserver firstRecordingObserver() {

      return new RecordingObserver(FIRST);

    }

    @Bean
    @Order(3)
    PeaUserTaskObserver secondRecordingObserver() {

      return new RecordingObserver(SECOND);

    }

    @Bean
    AggregatePersistenceAware<ObserverAggregate> observerPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<ObserverAggregate> getAggregateClass() {
          return ObserverAggregate.class;
        }

        @Override
        public ObserverAggregate save(
            final ObserverAggregate aggregate) {
          AGGREGATES.put(aggregate.id, copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final ObserverAggregate aggregate) {
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
        public ObserverAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    @Bean
    DataSource observerDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource observerDataSource) {

      return new DataSourceTransactionManager(observerDataSource);

    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = ObserverAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class ObserverWorkflowService {

    /**
     * The claimed user task. Its sibling <code>peaUnclaimed</code> has no method here on
     * purpose: a task list shows it anyway.
     */
    @WorkflowTask(taskDefinition = "peaObserved")
    public void peaObservedNotification(
        final ObserverAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.results = "notified:"
          + taskId;

    }

  }

  @Autowired
  private InMemoryProcessEngine engine;

  @BeforeEach
  public void seedAggregates() {

    UserTaskObserverConfiguration.AGGREGATES.clear();
    UserTaskObserverConfiguration.FIRST.clear();
    UserTaskObserverConfiguration.SECOND.clear();
    engine.clearTaskRecordings();
    final var aggregate = new ObserverAggregate();
    aggregate.id = "5001";
    UserTaskObserverConfiguration.AGGREGATES.put(aggregate.id, aggregate);

  }

  @Test
  @DisplayName("Both user-task subscriptions are opened, the claimed one and the unclaimed one")
  public void bothSubscriptionsOpened() {

    final var subscribed = engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();
    assertTrue(
        subscribed.containsAll(List.of("peaObserved", "peaUnclaimed")),
        "expected a subscription per user task but got: "
            + subscribed);

  }

  @Test
  @DisplayName("A delivered user task reaches every observer, fully resolved")
  public void deliveryReachesEveryObserver() {

    engine.deliverTask("obs-1", "peaObserved", PROCESS, Map.of("id", "5001"));

    assertEquals(1, UserTaskObserverConfiguration.FIRST.delivered.size());
    assertEquals(1, UserTaskObserverConfiguration.SECOND.delivered.size());

    final var observation = UserTaskObserverConfiguration.FIRST.delivered.getFirst();
    assertEquals("pea", observation.adapterId());
    assertEquals(MODULE, observation.workflowModuleId());
    assertEquals(PROCESS, observation.bpmnProcessId());
    assertEquals("peaObserved", observation.taskDefinition());
    assertEquals("5001", observation.workflowAggregateId());
    assertEquals("obs-1", observation.taskId());
    assertEquals(Map.of("id", "5001"), observation.payload());

    // the throwing observer runs first and changes nothing: the notification ran too
    assertEquals(
        "notified:obs-1",
        UserTaskObserverConfiguration.AGGREGATES.get("5001").results,
        "the @WorkflowTask notification has to have run despite the failing observer");

  }

  @Test
  @DisplayName("A user task no @WorkflowTask method claims reaches the observers all the same")
  public void unclaimedDeliveryReachesTheObservers() {

    engine.deliverTask("obs-2", "peaUnclaimed", PROCESS, Map.of("id", "5001"));

    final var observation = UserTaskObserverConfiguration.SECOND.delivered.getFirst();
    assertEquals("peaUnclaimed", observation.taskDefinition());
    assertEquals("5001", observation.workflowAggregateId());
    assertNull(
        UserTaskObserverConfiguration.AGGREGATES.get("5001").results,
        "no method claims this task, so nothing of the application ran");

  }

  @Test
  @DisplayName("A terminated user task reaches the observers with the engine's reason")
  public void terminationCarriesTheReason() {

    engine.deliverTask("obs-3", "peaObserved", PROCESS, Map.of("id", "5001"));
    engine.terminateTask("obs-3", "peaObserved", PROCESS, TaskInformation.DELETE);

    assertEquals(1, UserTaskObserverConfiguration.FIRST.terminated.size());
    assertEquals(1, UserTaskObserverConfiguration.SECOND.terminated.size());

    final var observation = UserTaskObserverConfiguration.SECOND.terminated.getFirst();
    assertEquals("obs-3", observation.taskId());
    assertEquals(PROCESS, observation.bpmnProcessId());
    assertEquals("peaObserved", observation.taskDefinition());
    assertEquals(
        TaskInformation.DELETE,
        observation.reason(),
        "the TaskTerminationHandler overload is what carries this - Consumer<String> drops it");
    assertNull(observation.workflowAggregateId(), "a termination carries no payload");
    assertTrue(observation.payload().isEmpty());

  }

}
