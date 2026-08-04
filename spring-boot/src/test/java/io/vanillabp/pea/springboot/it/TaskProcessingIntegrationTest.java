package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import dev.bpmcrafters.processengineapi.ExecutionMode;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Task processing against the extended in-memory mock (story 21c), asserted at
 * the Process-Engine-API boundary: the adapter subscribes per task definition at
 * startup, delivered tasks dispatch through the core (new local transaction which
 * commits BEFORE the completion command), the three outcomes map to
 * {@code completeTask} / {@code completeTaskByError} / {@code failTask} (all
 * carrying {@code ExecutionMode.SYNC}), duplicate deliveries converge and a
 * failing completion after the local commit is the documented at-least-once
 * residual.
 */
@SpringBootTest(
    classes = TaskProcessingIntegrationTest.TaskProcessingApplication.class,
    properties = {
        "vanillabp.adapters.pea.type=process-engine-api", "vanillabp.prioritized-adapters=pea", "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/tasks"
    })
@ExtendWith(SuppressOutputExtension.class)
public class TaskProcessingIntegrationTest {

  private static final String MODULE = "pea-test-module";

  private static final String PROCESS = "PeaTaskProcess";

  // the test's nested classes are excluded from Spring's component scan
  // (TestTypeExcludeFilter) - configuration and workflow service are imported
  // explicitly
  @SpringBootApplication
  @Import({
      TestPersistenceConfiguration.class, TaskProcessingConfiguration.class, PeaTaskWorkflowService.class
  })
  public static class TaskProcessingApplication {
  }

  public static class PeaTaskAggregate {

    String id;

    String results;

    String taskId;

    void appendResult(
        final String result) {

      results = results == null
          ? result
          : results
              + "|"
              + result;

    }

  }

  /**
   * In-memory persistence copying aggregates on save/load - only saved state
   * survives, so the rollback assertions are meaningful.
   */
  @Configuration
  public static class TaskProcessingConfiguration {

    static final Map<String, PeaTaskAggregate> AGGREGATES = new ConcurrentHashMap<>();

    private static PeaTaskAggregate copyOf(
        final PeaTaskAggregate aggregate) {

      final var copy = new PeaTaskAggregate();
      copy.id = aggregate.id;
      copy.results = aggregate.results;
      copy.taskId = aggregate.taskId;
      return copy;

    }

    @Bean
    AggregatePersistenceAware<PeaTaskAggregate> peaTaskPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<PeaTaskAggregate> getAggregateClass() {
          return PeaTaskAggregate.class;
        }

        @Override
        public PeaTaskAggregate save(
            final PeaTaskAggregate aggregate) {
          AGGREGATES.put(aggregate.id, copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final PeaTaskAggregate aggregate) {
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
        public PeaTaskAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    @Bean
    DataSource peaTaskDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource peaTaskDataSource) {

      return new DataSourceTransactionManager(peaTaskDataSource);

    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = PeaTaskAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class PeaTaskWorkflowService {

    private final ProcessService<PeaTaskAggregate> processService;

    public PeaTaskWorkflowService(
        final ProcessService<PeaTaskAggregate> processService) {

      this.processService = processService;

    }

    public PeaTaskAggregate completeAsyncTask(
        final PeaTaskAggregate aggregate,
        final String taskId) {

      return processService.completeTask(aggregate, taskId);

    }

    public PeaTaskAggregate cancelAsyncTask(
        final PeaTaskAggregate aggregate,
        final String taskId,
        final String bpmnErrorCode) {

      return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

    }

    @WorkflowTask
    public void peaHappy(
        final PeaTaskAggregate aggregate) {

      // idempotent: keyed on aggregate state, not on call count
      if ((aggregate.results == null) || !aggregate.results.contains("happy")) {
        aggregate.appendResult("happy");
      }

    }

    @WorkflowTask
    public void peaError(
        final PeaTaskAggregate aggregate) {

      aggregate.appendResult("error-raised");
      throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

    }

    @WorkflowTask
    public void peaFails(
        final PeaTaskAggregate aggregate) {

      aggregate.appendResult("must-never-be-visible");
      throw new IllegalStateException("boom-pea");

    }

    @WorkflowTask
    public void peaAsync(
        final PeaTaskAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.taskId = taskId;
      aggregate.appendResult("async-open");

    }

  }

  @Autowired
  private InMemoryProcessEngine engine;

  private PeaTaskAggregate seed(
      final String id) {

    final var aggregate = new PeaTaskAggregate();
    aggregate.id = id;
    TaskProcessingConfiguration.AGGREGATES.put(id, aggregate);
    return aggregate;

  }

  private PeaTaskAggregate stored(
      final String id) {

    return TaskProcessingConfiguration.AGGREGATES.get(id);

  }

  @BeforeEach
  public void seedAggregates() {

    TaskProcessingConfiguration.AGGREGATES.clear();
    // keep the startup subscriptions - only the per-test recordings are cleared
    engine.clearTaskRecordings();

  }

  @Test
  @DisplayName("The adapter subscribes per task definition at startup")
  public void subscriptionsOpened() {

    final var subscribed = engine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();
    assertTrue(subscribed.containsAll(List.of("peaHappy", "peaError", "peaFails", "peaAsync")),
        "expected all task definitions subscribed but got: "
            + subscribed);

  }

  @Test
  @DisplayName("Normal return completes the task with ExecutionMode.SYNC after the local commit")
  public void happyPathCompletesSync() {

    seed("4711");
    engine.deliverTask("task-1", "peaHappy", PROCESS, Map.of("id", "4711"));

    assertEquals("happy", stored("4711").results);
    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-1")),
        engine.getCompletedTasks());
    // the completion command carries SYNC - the phase-two shape (advances the
    // process after the local commit)
    final var completionMode = engine.invocations
        .stream()
        .filter(invocation -> "completeTask".equals(invocation.method()))
        .map(InMemoryProcessEngine.Invocation::executionMode)
        .findFirst()
        .orElseThrow();
    assertEquals(ExecutionMode.SYNC, completionMode);

  }

  @Test
  @DisplayName("TaskException completes the task by BPMN error and COMMITS the aggregate changes")
  public void taskExceptionCompletesByError() {

    seed("4712");
    engine.deliverTask("task-2", "peaError", PROCESS, Map.of("id", "4712"));

    assertEquals("error-raised", stored("4712").results);
    assertEquals(1, engine.getErroredTasks().size());
    final var errored = engine.getErroredTasks().getFirst();
    assertEquals("task-2", errored.taskId());
    assertEquals("PAYMENT_FAILED", errored.errorCode());

  }

  @Test
  @DisplayName("A technical exception fails the task and rolls back the aggregate changes")
  public void technicalExceptionFailsTask() {

    seed("4713");
    engine.deliverTask("task-3", "peaFails", PROCESS, Map.of("id", "4713"));

    // the handler ran (recorded by the fail) but its mutation was rolled back
    assertNull(stored("4713").results);
    assertEquals(1, engine.getFailedTasks().size());
    assertEquals("task-3", engine.getFailedTasks().getFirst().taskId());
    assertTrue(engine.getCompletedTasks().isEmpty(), "a failed task must not be completed");

  }

  @Test
  @DisplayName("A @TaskId method leaves the task open (no completion command at all)")
  public void asyncTaskStaysOpen() {

    seed("4714");
    engine.deliverTask("task-4", "peaAsync", PROCESS, Map.of("id", "4714"));

    assertEquals("task-4", stored("4714").taskId);
    assertEquals("async-open", stored("4714").results);
    assertTrue(engine.getCompletedTasks().isEmpty());
    assertTrue(engine.getErroredTasks().isEmpty());
    assertTrue(engine.getFailedTasks().isEmpty());

  }

  @Autowired
  private PeaTaskWorkflowService taskWorkflowService;

  @Autowired
  private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  private java.util.List<ExecutionMode> completionModes(
      final String method,
      final String taskId) {

    return engine.invocations
        .stream()
        .filter(invocation -> method.equals(invocation.method()))
        .filter(invocation -> {
          final var command = invocation.command();
          return (command instanceof dev.bpmcrafters.processengineapi.task.CompleteTaskCmd complete && taskId
              .equals(complete
                  .getTaskId())) || (command instanceof dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd byError && taskId
                      .equals(byError.getTaskId()));
        })
        .map(InMemoryProcessEngine.Invocation::executionMode)
        .toList();

  }

  private void awaitUntil(
      final java.util.function.Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 15000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("completeTask runs the PREFLIGHT_CHECK in phase one and the SYNC completion after the commit")
  public void completeTaskPreflightThenSyncAfterCommit() throws Exception {

    seed("4721");
    engine.deliverTask("task-c1", "peaAsync", PROCESS, Map.of("id", "4721"));
    assertTrue(engine.getOpenTaskIds().contains("task-c1"), "the async task stays open");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = stored("4721");
      aggregate.appendResult("completing");
      taskWorkflowService.completeAsyncTask(aggregate, "task-c1");
      // INSIDE the transaction: probes/preflight ran (PREFLIGHT_CHECK, never
      // advancing), but NO SYNC completion happened yet
      assertTrue(
          completionModes("completeTask", "task-c1")
              .stream()
              .allMatch(mode -> mode == ExecutionMode.PREFLIGHT_CHECK),
          "before the commit only PREFLIGHT_CHECK commands may run");
    });

    // the SYNC completion is dispatched through the outbox AFTER the commit
    awaitUntil(
        () -> completionModes("completeTask", "task-c1").contains(ExecutionMode.SYNC),
        "the SYNC completion to be dispatched after the commit");
    awaitUntil(
        () -> engine
            .getCompletedTasks()
            .contains(new InMemoryProcessEngine.CompletedTask("task-c1")),
        "the task to be completed");

  }

  @Test
  @DisplayName("A rollback after completeTask yields NO SYNC completion - the task stays open")
  public void rollbackAfterCompleteTaskYieldsNoSync() throws Exception {

    seed("4722");
    engine.deliverTask("task-c2", "peaAsync", PROCESS, Map.of("id", "4722"));

    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          taskWorkflowService.completeAsyncTask(stored("4722"), "task-c2");
          throw new RuntimeException("test rollback");
        }));

    Thread.sleep(1500);
    assertTrue(
        completionModes("completeTask", "task-c2")
            .stream()
            .noneMatch(mode -> mode == ExecutionMode.SYNC),
        "a rolled-back transaction must never yield a SYNC completion");
    assertTrue(engine.getOpenTaskIds().contains("task-c2"), "the task stays open");

  }

  @Test
  @DisplayName("cancelTask sends the SYNC completeTaskByError with the error code after the commit")
  public void cancelTaskSendsByErrorAfterCommit() throws Exception {

    seed("4723");
    engine.deliverTask("task-c3", "peaAsync", PROCESS, Map.of("id", "4723"));

    transactionTemplate.executeWithoutResult(status -> taskWorkflowService
        .cancelAsyncTask(stored("4723"), "task-c3", "PAYMENT_FAILED"));

    awaitUntil(
        () -> engine
            .getErroredTasks()
            .stream()
            .anyMatch(errored -> "task-c3".equals(errored.taskId()) && "PAYMENT_FAILED".equals(errored.errorCode())),
        "the SYNC cancellation to be dispatched after the commit");
    assertEquals(
        List.of(ExecutionMode.SYNC),
        completionModes("completeTaskByError", "task-c3"));

  }

  @Test
  @DisplayName("completeTask of an unknown task raises the guiding TaskNotFoundException")
  public void completeUnknownTaskRaisesGuidingException() {

    seed("4724");

    final var exception = org.junit.jupiter.api.Assertions.assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> taskWorkflowService
            .completeAsyncTask(stored("4724"), "no-such-task")));
    assertTrue(exception.getMessage().contains("no-such-task"));

  }

  @Test
  @DisplayName("A duplicate delivery converges idempotently (at-least-once)")
  public void duplicateDeliveryConverges() {

    seed("4715");
    engine.deliverTask("task-5", "peaHappy", PROCESS, Map.of("id", "4715"));
    engine.deliverTask("task-5", "peaHappy", PROCESS, Map.of("id", "4715"));

    // the handler ran twice but the aggregate converged (idempotent by design)
    assertEquals("happy", stored("4715").results);
    assertEquals(2, engine.getCompletedTasks().size());

  }

  @Test
  @DisplayName("A failing completion after the local commit is the tolerated at-least-once residual")
  public void failingCompletionIsToleratedResidual() {

    seed("4716");
    engine.failNextCompletionFor("task-6");

    // the completion fails AFTER the local commit - the handler must not crash
    // and the aggregate keeps the committed state
    engine.deliverTask("task-6", "peaHappy", PROCESS, Map.of("id", "4716"));
    assertEquals("happy", stored("4716").results);
    assertTrue(engine.getCompletedTasks().isEmpty());

    // the engine redelivers eventually - the second delivery converges and
    // completes
    engine.deliverTask("task-6", "peaHappy", PROCESS, Map.of("id", "4716"));
    assertEquals("happy", stored("4716").results);
    assertEquals(1, engine.getCompletedTasks().size());

  }

}
