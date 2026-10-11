package io.vanillabp.pea.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
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
import org.springframework.transaction.support.TransactionTemplate;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.task.CompleteTaskByErrorCmd;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.springboot.TestPersistenceConfiguration;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.TaskNotFoundException;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Task processing against the in-memory mock, checked at the edge of the
 * Process-Engine-API. At startup the adapter subscribes once for each task definition. A
 * delivered task goes through the core in a new local transaction, which commits before the
 * completion command is sent. The three outcomes become {@code completeTask},
 * {@code completeTaskByError} and {@code failTask}, all with {@code ExecutionMode.SYNC}. A
 * task delivered twice ends in the same state. A completion which fails after the local
 * commit is the documented rest of at-least-once delivery.
 */
@SpringBootTest(
    classes = TaskProcessingIntegrationTest.TaskProcessingApplication.class,
    properties = {
        "vanillabp.adapters.pea.type=process-engine-api", "vanillabp.adapters.pea.name-clash-avoidance=none", "vanillabp.prioritized-adapters=pea", "vanillabp.workflow-modules.pea-test-module.adapters.pea.resources-location=classpath*:pea-test-module/processes/tasks"
    })
@ExtendWith(SuppressOutputExtension.class)
public class TaskProcessingIntegrationTest {

  private static final String MODULE = "pea-test-module";

  private static final String PROCESS = "PeaTaskProcess";

  // Spring's component scan leaves out the nested classes of a test (TestTypeExcludeFilter).
  // So the configuration and the workflow service are imported by name.
  @SpringBootApplication
  @Import({
      TestPersistenceConfiguration.class, TaskProcessingConfiguration.class, PeaTaskWorkflowService.class
  })
  public static class TaskProcessingApplication {
  }

  public static class PeaTaskAggregate {

    @Getter
    String id;

    @Getter
    String results;

    String taskId;

    /**
     * Never sent to the engine. Because of this annotation, the class shares everything
     * else (opt-out).
     */
    @NoSyncWithBPMS
    @Getter
    String secret;

    // The sync model reads JavaBean properties. Only a field with a getter can be shared
    // with the engine at all.

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
   * Persistence in memory which stores a copy of each aggregate. Only saved state
   * survives, so the checks after a rollback mean something.
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
      copy.secret = aggregate.secret;
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
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS),
      secondaryBpmnProcesses = {
          @BpmnProcess(bpmnProcessId = "PeaMessageProcess"), @BpmnProcess(
              bpmnProcessId = "PeaMessageStartProcess")
      })
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

      // Idempotent: it looks at the state of the aggregate, not at how often it ran
      if ((aggregate.results == null) || !aggregate.results.contains("happy")) {
        aggregate.appendResult("happy");
      }
      aggregate.secret = "s3cr3t";

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

    public PeaTaskAggregate completeUserTask(
        final PeaTaskAggregate aggregate,
        final String taskId) {

      return processService.completeUserTask(aggregate, taskId);

    }

    public PeaTaskAggregate correlate(
        final PeaTaskAggregate aggregate,
        final String messageName) {

      return processService.correlateMessage(aggregate, messageName);

    }

    public PeaTaskAggregate correlate(
        final PeaTaskAggregate aggregate,
        final String messageName,
        final String correlationId) {

      return processService.correlateMessage(aggregate, messageName, correlationId);

    }

    public PeaTaskAggregate startByMessage(
        final PeaTaskAggregate aggregate,
        final String messageName) {

      return processService.startWorkflowByMessage(aggregate, messageName);

    }

    public PeaTaskAggregate cancelUserTask(
        final PeaTaskAggregate aggregate,
        final String taskId,
        final String bpmnErrorCode) {

      return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

    }

    @WorkflowTask(taskDefinition = "peaApprove")
    public void peaApproveNotification(
        final PeaTaskAggregate aggregate,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event) {

      aggregate.taskId = taskId;
      aggregate.appendResult("usertask-"
          + event.name().toLowerCase());

    }

    @WorkflowTask
    public void peaAsync(
        final PeaTaskAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.taskId = taskId;
      aggregate.appendResult("async-open");

    }

    /**
     * Wired by the BPMN element id. The engine delivers the task under the task definition
     * {@code peaNobodyNamesThis}, a name this method never mentions. The
     * <code>&#64;TaskParam</code> matters too. The subscription has to ask the engine for
     * that variable. Asking with the task definition alone would not find this method.
     */
    @WorkflowTask(id = "t_wired_by_id")
    public void peaWiredByElementId(
        final PeaTaskAggregate aggregate,
        @TaskParam("amount") final String amount) {

      aggregate.appendResult("by-id-"
          + amount);

    }

    /**
     * The same for a user task. The engine delivers it under the form reference
     * {@code peaFormNobodyNames}, and this method does not name it.
     */
    @WorkflowTask(id = "t_user_wired_by_id")
    public void peaUserTaskWiredByElementId(
        final PeaTaskAggregate aggregate,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event) {

      aggregate.taskId = taskId;
      aggregate.appendResult("usertask-by-id-"
          + event.name().toLowerCase());

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
    // Only the recordings of the last test are cleared. The subscriptions of the startup
    // stay.
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
    // The completion command carries SYNC, as in phase two. It moves the process on after
    // the local commit.
    final var completionMode = engine.invocations
        .stream()
        .filter(invocation -> "completeTask".equals(invocation.method()))
        .map(InMemoryProcessEngine.Invocation::executionMode)
        .findFirst()
        .orElseThrow();
    assertEquals(ExecutionMode.SYNC, completionMode);

  }

  @Test
  @DisplayName("The completion carries the shared aggregate attributes plus the ID variable")
  public void completionCarriesTheAggregateState() {

    seed("4715");
    engine.deliverTask("task-sync", "peaHappy", PROCESS, Map.of("id", "4715"));

    // The @WorkflowTask method changed the aggregate before the task was completed. A
    // gateway right behind this task can only branch on that change if the completion
    // carried it.
    final var payload = engine.getCompletionPayload("task-sync");
    assertEquals("4715", payload.get("id"), "the technical aggregate-ID variable always travels");
    assertEquals("happy", payload.get("results"), "the shared attributes travel");
    assertTrue(
        !payload.containsKey("secret"),
        "a @NoSyncWithBPMS attribute must never travel but the payload was: "
            + payload);

  }

  @Test
  @DisplayName("A BPMN error carries the aggregate state, too (the boundary path may branch on it)")
  public void bpmnErrorCarriesTheAggregateState() {

    seed("4716");
    engine.deliverTask("task-sync-error", "peaError", PROCESS, Map.of("id", "4716"));

    final var payload = engine.getCompletionPayload("task-sync-error");
    assertEquals("4716", payload.get("id"));
    assertEquals("error-raised", payload.get("results"));

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

    // The handler ran, which the failed task shows, but its change was rolled back
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
  private TransactionTemplate transactionTemplate;

  private List<ExecutionMode> completionModes(
      final String method,
      final String taskId) {

    return engine.invocations
        .stream()
        .filter(invocation -> method.equals(invocation.method()))
        .filter(invocation -> {
          final var command = invocation.command();
          return (command instanceof CompleteTaskCmd complete && taskId
              .equals(complete
                  .getTaskId())) || (command instanceof CompleteTaskByErrorCmd byError && taskId
                      .equals(byError.getTaskId()));
        })
        .map(InMemoryProcessEngine.Invocation::executionMode)
        .toList();

  }

  /**
   * How long the test watches the engine double after a rolled-back operation. After that,
   * a call which has not arrived counts as one which never will.
   * <p>
   * One and a half seconds is long compared to what a committed operation takes. The outbox
   * sends an entry right after the commit which wrote it, and the engine here is an object
   * in the same JVM.
   * <p>
   * This is a guard, not a time limit anybody has to beat. Afterwards the test checks that a
   * task stayed open or that a list did not grow. A slow machine only makes the silence
   * longer.
   */
  private static final long UNTIL_A_COMMITTED_CALL_WOULD_HAVE_ARRIVED = 1500;

  private void awaitUntil(
      final Supplier<Boolean> condition,
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
      // The completion must not have moved the process on yet. The PREFLIGHT_CHECK of the
      // adapter is registered with the pre-commit hook and runs during the commit, so it
      // cannot be seen here yet.
      assertFalse(
          completionModes("completeTask", "task-c1").contains(ExecutionMode.SYNC),
          "nothing may advance the process while the caller's transaction is open");
    });

    // The commit has returned, so the check which could still have stopped it has run
    assertTrue(
        completionModes("completeTask", "task-c1").contains(ExecutionMode.PREFLIGHT_CHECK),
        "the PREFLIGHT_CHECK has to have run by the time the commit returned");

    // The outbox sends the SYNC completion after the commit
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

    Assertions.assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          taskWorkflowService.completeAsyncTask(stored("4722"), "task-c2");
          throw new RuntimeException("test rollback");
        }));

    Thread.sleep(UNTIL_A_COMMITTED_CALL_WOULD_HAVE_ARRIVED);
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
  @DisplayName("completeTask of an unknown task raises the guiding TaskNotFoundException, at once")
  public void completeUnknownTaskRaisesGuidingException() {

    seed("4724");

    final var startedAt = System.nanoTime();
    final var exception = Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> taskWorkflowService
            .completeAsyncTask(stored("4724"), "no-such-task")));
    final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertTrue(exception.getMessage().contains("no-such-task"));
    // The preflight completion gives an exact answer, and it runs in the transaction of
    // the caller. So the call fails there and does not wait for anything (decision 27 of
    // the platform's DECISIONS.md).
    assertTrue(
        elapsed.toSeconds() < 5,
        "an unknown task must fail without waiting, but took "
            + elapsed);

  }

  @Test
  @DisplayName("A stale completion aborts the transaction with the documented TaskNotFoundException")
  public void aStaleCompletionRaisesTheGuidingException() {

    seed("4725");
    engine.deliverTask("task-c4", "peaAsync", PROCESS, Map.of("id", "4725"));
    assertEquals("task-c4", stored("4725").taskId, "the handler has to have parked the task");

    // Somebody else finishes the task. This is how a concurrent completion looks from
    // here.
    engine.terminateTask("task-c4", "peaAsync", PROCESS, TaskInformation.COMPLETE);

    // The delivery record of that task still says this adapter holds it open. So the call
    // is routed here without a probe, and the pre-commit check runs into the refusal of
    // the engine. The caller gets the exception type the SPI documents for a task no BPMS
    // knows any more.
    final var exception = Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> taskWorkflowService
            .completeAsyncTask(stored("4725"), "task-c4")));
    assertTrue(exception.getMessage().contains("task-c4"));

  }

  @Test
  @DisplayName("User task: CREATED notification via USER subscription, completeUserTask PREFLIGHT/SYNC ordering")
  public void userTaskNotificationAndCompletion() throws Exception {

    // The adapter subscribed for user tasks by their external form reference
    assertTrue(
        engine
            .getSubscriptions()
            .stream()
            .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
            .anyMatch("peaApprove"::equals),
        "expected a USER subscription for 'peaApprove'");

    seed("4731");
    engine.deliverTask("utask-1", "peaApprove", PROCESS, Map.of("id", "4731"));

    // The CREATED notification ran and committed. The task itself stays open.
    assertEquals("usertask-created", stored("4731").results);
    assertEquals("utask-1", stored("4731").taskId);
    assertTrue(engine.getCompletedTasks().isEmpty(), "a notification must not complete the task");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = stored("4731");
      aggregate.appendResult("approving");
      taskWorkflowService.completeUserTask(aggregate, "utask-1");
      assertFalse(
          completionModes("completeTask", "utask-1").contains(ExecutionMode.SYNC),
          "nothing may advance the process while the caller's transaction is open");
    });

    assertTrue(
        completionModes("completeTask", "utask-1").contains(ExecutionMode.PREFLIGHT_CHECK),
        "the PREFLIGHT_CHECK has to have run by the time the commit returned");

    awaitUntil(
        () -> completionModes("completeTask", "utask-1").contains(ExecutionMode.SYNC),
        "the SYNC user-task completion to be dispatched after the commit");
    awaitUntil(
        () -> engine
            .getCompletedTasks()
            .contains(new InMemoryProcessEngine.CompletedTask("utask-1")),
        "the user task to be completed");

  }

  @Test
  @DisplayName("cancelUserTask sends the SYNC completeTaskByError after the commit")
  public void cancelUserTaskSendsByError() throws Exception {

    seed("4732");
    engine.deliverTask("utask-2", "peaApprove", PROCESS, Map.of("id", "4732"));

    transactionTemplate.executeWithoutResult(status -> taskWorkflowService
        .cancelUserTask(stored("4732"), "utask-2", "APPROVAL_WITHDRAWN"));

    awaitUntil(
        () -> engine
            .getErroredTasks()
            .stream()
            .anyMatch(
                errored -> "utask-2".equals(errored.taskId()) && "APPROVAL_WITHDRAWN".equals(errored.errorCode())),
        "the SYNC user-task cancellation to be dispatched after the commit");

  }

  @Test
  @DisplayName("An unknown user task raises the guiding TaskNotFoundException")
  public void unknownUserTaskRaisesGuidingException() {

    seed("4733");

    final var exception = Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> taskWorkflowService
            .completeUserTask(stored("4733"), "utask-unknown")));
    assertTrue(exception.getMessage().contains("utask-unknown"));

  }

  @Test
  @DisplayName("A stale user-task completion raises the same documented exception")
  public void aStaleUserTaskCompletionRaisesTheGuidingException() {

    seed("4734");
    engine.deliverTask("utask-3", "peaApprove", PROCESS, Map.of("id", "4734"));
    assertEquals("utask-3", stored("4734").taskId, "the notification has to have reported the task");

    engine.terminateTask("utask-3", "peaApprove", PROCESS, TaskInformation.COMPLETE);

    // The same route as for a service task. The record sends the call to this adapter,
    // and the user-task check finds out that the task is gone.
    final var exception = Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> taskWorkflowService
            .completeUserTask(stored("4734"), "utask-3")));
    assertTrue(exception.getMessage().contains("utask-3"));

  }

  @Test
  @DisplayName("correlateMessage dispatches AFTER the commit only (correlation key = aggregate id)")
  public void correlateMessageDispatchesAfterCommit() throws Exception {

    seed("4741");

    transactionTemplate.executeWithoutResult(status -> {
      taskWorkflowService.correlate(stored("4741"), "PeaPaymentReceived");
      // Inside the transaction nothing was correlated yet. A preflight is not even
      // possible, because the command classes are final (GAPS entry 11).
      assertTrue(engine.getCorrelatedMessages().isEmpty());
    });

    awaitUntil(
        () -> engine
            .getCorrelatedMessages()
            .contains(new InMemoryProcessEngine.CorrelatedMessage("PeaPaymentReceived", "4741")),
        "the correlation to be dispatched after the commit");

  }

  @Test
  @DisplayName("A correlation id becomes the correlation key; a rollback yields no correlation")
  public void correlationIdAndRollback() throws Exception {

    seed("4742");

    transactionTemplate.executeWithoutResult(status -> taskWorkflowService
        .correlate(stored("4742"), "PeaItemShipped", "item-7"));
    awaitUntil(
        () -> engine
            .getCorrelatedMessages()
            .contains(new InMemoryProcessEngine.CorrelatedMessage("PeaItemShipped", "item-7")),
        "the correlation-id correlation to be dispatched");

    final var before = engine.getCorrelatedMessages().size();
    Assertions.assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          taskWorkflowService.correlate(stored("4742"), "PeaNeverSent");
          throw new RuntimeException("test rollback");
        }));
    Thread.sleep(UNTIL_A_COMMITTED_CALL_WOULD_HAVE_ARRIVED);
    assertEquals(before, engine.getCorrelatedMessages().size(), "a rollback must not correlate");

  }

  @Test
  @DisplayName("startWorkflowByMessage starts via StartProcessByMessageCmd carrying the aggregate state, never message content")
  public void startWorkflowByMessagePublishesTheAggregateState() throws Exception {

    final var aggregate = new PeaTaskAggregate();
    aggregate.id = "4743";
    TaskProcessingConfiguration.AGGREGATES.put("4743", aggregate);

    transactionTemplate.executeWithoutResult(status -> taskWorkflowService
        .startByMessage(stored("4743"), "PeaOrderPlaced"));

    awaitUntil(
        () -> engine
            .getStartedInstances()
            .stream()
            .anyMatch(instance -> "4743".equals(instance.variables().get("id"))),
        "the start-by-message to be dispatched after the commit");
    final var instance = engine
        .getStartedInstances()
        .stream()
        .filter(candidate -> "4743".equals(candidate.variables().get("id")))
        .findFirst()
        .orElseThrow();
    // No message content travels. What travels is the aggregate-ID variable and the
    // shared state of the aggregate, see decision 1 in the repository's DECISIONS.md.
    // 'results' is still null on a workflow which just started, and the @NoSyncWithBPMS
    // attribute is missing as always.
    final var variables = new HashMap<>(instance.variables());
    assertEquals("4743", variables.remove("id"));
    assertTrue(variables.containsKey("results") && (variables.get("results") == null));
    variables.remove("results");
    assertEquals(Map.of(), variables, "nothing else travels");

    // Like the plain start, the start by message reports the instance the engine created
    final var writtenDown = TaskDeliveryLogReader.of(deliveryLogDataSource);
    awaitUntil(
        () -> !writtenDown.workflowStartsOfAggregate("4743").isEmpty(),
        "the start of the workflow to be written to the delivery log");
    assertEquals(
        instance.instanceId(),
        writtenDown.workflowStartsOfAggregate("4743").getFirst().workflowId(),
        "the instance id the engine answered");

  }

  @Autowired
  private DataSource deliveryLogDataSource;

  /**
   * What the delivery log wrote down about one task. The reader belongs to the platform,
   * so this class names neither the table nor its columns.
   *
   * @param taskId The engine's task id, which is what the adapter reports as the delivery
   * @return The one record of that task
   */
  private TaskDeliveryLogReader.Delivery recordOf(
      final String taskId) {

    final var records = TaskDeliveryLogReader
        .of(deliveryLogDataSource)
        .deliveriesOfTask(taskId);
    assertEquals(1, records.size(), "expected exactly one record for task "
        + taskId);
    return records.getFirst();

  }

  @Test
  @DisplayName("The record of a delivery names the element and the workflow the engine named")
  public void theRecordNamesWhatTheEngineNamed() {

    seed("4741");
    // An engine which fills its meta map with a lot, as the reference adapter of the API
    // for an embedded Camunda 7 does
    engine
        .deliverTask(
            "task-meta", "peaHappy", PROCESS, Map.of("id", "4741"), Map
                .of("activityId", "PeaHappyTask", "processInstanceId", "instance-4741"));

    final var record = recordOf("task-meta");
    assertEquals("PeaHappyTask", record.bpmnElementId(), "the element id the engine named");
    assertEquals("instance-4741", record.workflowId(), "the workflow id the engine named");

    // The user-task side reads the same two keys. It is a handler of its own.
    engine
        .deliverTask(
            "utask-meta", "peaApprove", PROCESS, Map.of("id", "4741"), Map
                .of("activityId", "PeaApproveTask", "processInstanceId", "instance-4741"));

    final var userTaskRecord = recordOf("utask-meta");
    assertEquals(
        "PeaApproveTask",
        userTaskRecord.bpmnElementId(),
        "the user task's element id the engine named");
    assertEquals(
        "instance-4741",
        userTaskRecord.workflowId(),
        "the workflow id of the user-task notification");

  }

  @Test
  @DisplayName("An engine which names neither leaves the workflow id empty, and the model names the element")
  public void anEngineWhichNamesNeitherIsAnsweredByTheModel() {

    seed("4742");
    // The Process-Engine-API does not define the keys a delivered task carries. So an
    // engine which fills none is a normal engine, not a broken one. Only the engine knows
    // the workflow, so the record says nothing about it. That is what the default of the
    // adapter SPI means. The element is different. The models this adapter deployed say
    // which element carries this task definition.
    engine.deliverTask("task-no-meta", "peaHappy", PROCESS, Map.of("id", "4742"));

    final var record = recordOf("task-no-meta");
    assertEquals("t_happy", record.bpmnElementId(), "the element the model names");
    assertNull(record.workflowId(), "no workflow id was named");

  }

  @Autowired
  private TaskDeliveryLog deliveryLog;

  /**
   * Which kind of task the record says an id belongs to.
   * <p>
   * It asks through the same method the BPMS election uses. So it reads what a caller
   * naming that id reads. Only a task still left to the application gets an answer. Those
   * are the only ids an application ever names. Nobody addresses a task any more which
   * this adapter completed itself.
   *
   * @param aggregateId The workflow aggregate the task was delivered for
   * @param taskId The engine's id of the task
   * @return The kind as the record carries it
   */
  private String recordedKindOf(
      final String aggregateId,
      final String taskId) {

    return deliveryLog
        .recordOfTask(MODULE, PROCESS, aggregateId, taskId)
        .orElseThrow(() -> new AssertionError("no open record of task "
            + taskId))
        .taskKind();

  }

  @Test
  @DisplayName("The record says whether an open id is a task or a user task")
  public void theRecordSaysWhichKindAnIdIs() {

    seed("4744");
    // A task left open by a @TaskId method. The application completes it through
    // ProcessService#completeTask. The engine takes the id back through its completion
    // API for service tasks.
    engine.deliverTask("open-task", "peaAsync", PROCESS, Map.of("id", "4744"));
    assertEquals("TASK", recordedKindOf("4744", "open-task"));

    // A user-task notification. The id looks the same but lives in another namespace. The
    // application completes it through ProcessService#completeUserTask. The engine takes it
    // back through its completion API for user tasks.
    engine.deliverTask("open-user-task", "peaApprove", PROCESS, Map.of("id", "4744"));
    assertEquals("USER_TASK", recordedKindOf("4744", "open-user-task"));

  }

  @Test
  @DisplayName("A delivery reaches the @WorkflowTask method wired to its BPMN element id")
  public void aMethodWiredByItsElementIdIsReached() {

    seed("4750");
    // The serving method never mentions the task definition, and this engine names no
    // element of its own. Without the element from the model, the delivery would find no
    // method and fail.
    engine
        .deliverTask("task-by-id", "peaNobodyNamesThis", PROCESS, Map.of("id", "4750", "amount", "42"));

    assertEquals("by-id-42", stored("4750").results);
    assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-by-id")),
        engine.getCompletedTasks());

  }

  @Test
  @DisplayName("The subscription asks for the variables an id-wired method declares")
  public void theSubscriptionAsksForWhatAnIdWiredMethodDeclares() {

    final var payload = engine
        .getSubscriptions()
        .stream()
        .filter(subscription -> "peaNobodyNamesThis".equals(subscription.taskDescriptionKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no subscription for 'peaNobodyNamesThis'"))
        .payloadDescription();

    // The adapter asks with the element id and with the task definition. Asking with the
    // task definition alone misses a method wired by its element id, and its @TaskParam
    // would not be fetched. A parameter which was not fetched fails the delivery here. It
    // does not arrive as null.
    assertEquals(java.util.Set.of("amount", "id"), payload);

  }

  @Test
  @DisplayName("A user task whose handler names the element id is notified instead of skipped")
  public void aUserTaskWiredByItsElementIdIsNotified() {

    seed("4751");
    engine.deliverTask("utask-by-id", "peaFormNobodyNames", PROCESS, Map.of("id", "4751"));

    // Asking with the form reference alone, nobody served this notification. It was skipped
    // without a word, which is worse than a notification which fails.
    assertEquals("usertask-by-id-created", stored("4751").results);
    assertEquals("utask-by-id", stored("4751").taskId);

  }

  @Test
  @DisplayName("A duplicate delivery converges idempotently (at-least-once)")
  public void duplicateDeliveryConverges() {

    seed("4715");
    engine.deliverTask("task-5", "peaHappy", PROCESS, Map.of("id", "4715"));
    engine.deliverTask("task-5", "peaHappy", PROCESS, Map.of("id", "4715"));

    // The handler ran twice, but the aggregate ends in the same state (idempotent by
    // design)
    assertEquals("happy", stored("4715").results);
    assertEquals(2, engine.getCompletedTasks().size());

  }

  @Test
  @DisplayName("A failing completion after the local commit is the tolerated at-least-once residual")
  public void failingCompletionIsToleratedResidual() {

    seed("4716");
    engine.failNextCompletionFor("task-6");

    // The completion fails after the local commit. The handler must not crash, and the
    // aggregate keeps the committed state.
    engine.deliverTask("task-6", "peaHappy", PROCESS, Map.of("id", "4716"));
    assertEquals("happy", stored("4716").results);
    assertTrue(engine.getCompletedTasks().isEmpty());

    // The engine delivers again at some point. The second delivery reaches the same state
    // and completes.
    engine.deliverTask("task-6", "peaHappy", PROCESS, Map.of("id", "4716"));
    assertEquals("happy", stored("4716").results);
    assertEquals(1, engine.getCompletedTasks().size());

  }

}
