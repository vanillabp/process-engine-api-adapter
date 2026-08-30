package io.vanillabp.pea.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.vanillabp.integration.test.utils.FreePortUtil;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Process-Engine-API adapter's documented features, run end to end on a BOOTED
 * Quarkus application against the in-memory mock engine.
 * <p>
 * This duplicates what the Spring Boot suite proves, and the duplication is the
 * point: the adapter's platform-neutral core being correct says nothing about a
 * platform's glue ever calling it. Coverage is measured per platform for exactly
 * that reason, so the core lines Quarkus never reaches name the features Quarkus
 * never runs - deploying at boot, starting a workflow through the two-phase outbox,
 * delivering and completing a task, correlating a message and answering the viewer
 * API.
 * <p>
 * Everything is observed through the application's own <code>introspect/...</code>
 * endpoints, because a prod-mode test runs the application in a forked JVM. The
 * JaCoCo agent is forwarded into it, otherwise the run would prove the features and
 * count as nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaWorkflowLifecycleTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.pea.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("pea-e2e-module/processes/pea-e2e.bpmn", "pea-e2e-module/processes/pea-e2e.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage - check the quarkus parent POM for
      // the systemPropertyVariables feeding 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(FreePortUtil.getFreePort()),
          // the application runs in a forked JVM, so its own log is the only place a
          // failure inside it can be read afterwards
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "pea-e2e-application.log")
              .toAbsolutePath()
              .toString()));

  private static final String MODULE = "pea-e2e-module";

  private static final String PROCESS = "PeaE2eProcess";

  private static RequestSpecification api() {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort());

  }

  private static List<String> strings(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("$", String.class);

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(
      final String path) {

    return api()
        .post(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  private static void postWithoutResponse(
      final String path) {

    api()
        .post(path)
        .then()
        .statusCode(204);

  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> startedInstances() {

    return api()
        .get("introspect/started-instances")
        .then()
        .statusCode(200)
        .extract()
        .as(List.class);

  }

  private static void await(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  /**
   * Two seconds is what the outbox needs to dispatch (poll interval 0.5 s) - long
   * enough to make "nothing was dispatched" a statement rather than a race.
   */
  private static void awaitNothingElseHappens() throws InterruptedException {

    Thread.sleep(2000);

  }

  @SuppressWarnings("unchecked")
  private static List<String> modes(
      final Map<String, Object> reported,
      final String moment) {

    return (List<String>) reported.get(moment);

  }

  /**
   * What the adapter promises about a task operation names two moments of the caller's
   * transaction: while it is open nothing may advance the process, and by the time its
   * commit returned the check which can still abort it has run. The check is registered
   * with the platform's pre-commit hook and therefore runs during the commit, so neither
   * moment on its own carries the promise.
   * <p>
   * A probe of the engine before that check is deliberately not asserted: the platform
   * routes a task operation by the delivery record of that task where it has one, so the
   * awareness probe this adapter offers may never be called at all.
   */
  private static void assertThePreflightRanBeforeTheCommitReturned(
      final Map<String, Object> reported) {

    final var whileOpen = modes(reported, "modesWhileTheTransactionWasOpen");
    assertFalse(
        whileOpen.contains("SYNC"),
        "nothing may advance the process while the caller's transaction is open but got: "
            + whileOpen);

    final var whenCommitted = modes(reported, "modesWhenTheCommitReturned");
    assertTrue(
        whenCommitted.contains("PREFLIGHT_CHECK"),
        "the PREFLIGHT_CHECK has to have run by the time the commit returned, later it has "
            + "nothing left to abort, but got: "
            + whenCommitted);

  }

  @BeforeEach
  public void resetRecordings() {

    postWithoutResponse("introspect/reset");

  }

  @Test
  @DisplayName("The deployment pipeline deploys the module's BPMN and subscribes per task definition at boot")
  public void deploymentAndSubscriptionsAtBoot() {

    assertEquals(MODULE, api()
        .get("introspect/workflow-module")
        .then()
        .statusCode(200)
        .extract()
        .asString());

    assertEquals(List.of("pea-e2e.bpmn"), strings("introspect/deployments"));

    assertEquals(
        List.of("e2eApprove", "e2eAsync", "e2eError", "e2eFails", "e2eHappy"),
        strings("introspect/subscriptions"),
        "every task definition of the deployed BPMN needs a subscription, user tasks included");

    // The subscription names what a delivery has to carry - here the
    // aggregate-ID variable alone, since no handler declares a @TaskParam
    assertEquals(
        List.of("id"),
        strings("introspect/subscriptions/e2eHappy/payload"),
        "an empty list would mean 'hand me the complete payload of the process instance'");

  }

  @Test
  @DisplayName("startWorkflow runs PREFLIGHT_CHECK in the transaction and creates the instance after the commit")
  public void startWorkflowIsTwoPhase() throws Exception {

    final var insideTransaction = post("introspect/workflows/q-start-1");
    assertEquals(1, insideTransaction.get("preflightStarts"));
    assertEquals(0, insideTransaction.get("syncStarts"));
    assertEquals(0, insideTransaction.get("startedInstances"));

    await(
        () -> startedInstances()
            .stream()
            .anyMatch(variables -> "q-start-1".equals(variables.get("id"))),
        "the workflow to be started after the commit");

    // the technical aggregate-ID variable plus the attributes shared with the
    // engine travel, nothing else (see decision 1 in the repository's DECISIONS.md)
    final var variables = startedInstances()
        .stream()
        .filter(candidate -> "q-start-1".equals(candidate.get("id")))
        .findFirst()
        .orElseThrow();
    assertTrue(variables.containsKey("results"));
    assertNull(variables.get("results"));
    assertFalse(
        variables.containsKey("secret"),
        "a @NoSyncWithBPMS attribute must never travel but the payload was: "
            + variables);

  }

  @Test
  @DisplayName("A rollback after startWorkflow never creates an instance")
  public void rollbackNeverStartsAWorkflow() throws Exception {

    postWithoutResponse("introspect/workflows/q-start-2/rollback");

    awaitNothingElseHappens();
    assertTrue(
        startedInstances()
            .stream()
            .noneMatch(variables -> "q-start-2".equals(variables.get("id"))),
        "a rolled-back transaction must never start a workflow");
    assertEquals(Map.of("exists", false), object("introspect/aggregates/q-start-2"));

  }

  @Test
  @DisplayName("A failed PREFLIGHT_CHECK rolls the caller's transaction back and never reaches phase two")
  public void failedPreflightRollsTheTransactionBack() throws Exception {

    postWithoutResponse("introspect/engine/fail-preflight/"
        + PROCESS);

    final var failed = post("introspect/workflows/q-start-3");
    assertEquals("IllegalStateException", failed.get("exception"));
    final var message = failed
        .get("message")
        .toString();
    assertTrue(message.contains("Preflight check"), "the engine's reason has to reach the caller: "
        + message);
    assertTrue(message.contains(PROCESS), "the message has to name the workflow: "
        + message);

    // the transaction rolled back, so neither the aggregate nor an outbox entry
    // survived - phase two can never follow
    assertEquals(Map.of("exists", false), object("introspect/aggregates/q-start-3"));
    awaitNothingElseHappens();
    assertTrue(
        startedInstances()
            .stream()
            .noneMatch(variables -> "q-start-3".equals(variables.get("id"))));

  }

  @Test
  @DisplayName("A failed phase two is retried by the outbox and creates the instance exactly once")
  public void failedPhaseTwoIsRetried() throws Exception {

    postWithoutResponse("introspect/engine/fail-next-sync/"
        + PROCESS);

    post("introspect/workflows/q-start-4");

    await(
        () -> strings("introspect/execution-modes/startProcess")
            .stream()
            .filter("SYNC"::equals)
            .count() >= 2,
        "the outbox to retry the failed phase-two dispatch");
    assertEquals(
        1,
        startedInstances()
            .stream()
            .filter(variables -> "q-start-4".equals(variables.get("id")))
            .count(),
        "the retry has to create the instance exactly once");

  }

  @Test
  @DisplayName("A delivered task runs the handler, commits it and completes the task with the aggregate state")
  public void deliveredTaskRunsAndCompletes() {

    postWithoutResponse("introspect/aggregates/q-task-1");
    postWithoutResponse("introspect/tasks/task-1/deliver/e2eHappy/q-task-1");

    assertEquals("happy", object("introspect/aggregates/q-task-1").get("results"));
    assertEquals(List.of("task-1"), strings("introspect/completed-tasks"));
    assertEquals(
        List.of("SYNC"),
        strings("introspect/execution-modes/completeTask/task-1"),
        "the completion advances the process and therefore carries SYNC");

    final var payload = object("introspect/tasks/task-1/completion-payload");
    assertEquals("q-task-1", payload.get("id"));
    assertEquals("happy", payload.get("results"), "a gateway behind the task can only branch on what travelled");
    assertFalse(payload.containsKey("secret"), "a @NoSyncWithBPMS attribute must never travel");

  }

  @Test
  @DisplayName("A TaskException completes the task by BPMN error and keeps the aggregate change")
  public void taskExceptionCompletesByBpmnError() {

    postWithoutResponse("introspect/aggregates/q-task-2");
    postWithoutResponse("introspect/tasks/task-2/deliver/e2eError/q-task-2");

    assertEquals("error-raised", object("introspect/aggregates/q-task-2").get("results"));
    assertEquals(List.of("task-2:PAYMENT_FAILED"), strings("introspect/errored-tasks"));
    assertEquals("error-raised", object("introspect/tasks/task-2/completion-payload").get("results"));

  }

  @Test
  @DisplayName("A technical exception fails the task and rolls the aggregate change back")
  public void technicalExceptionFailsTheTask() {

    postWithoutResponse("introspect/aggregates/q-task-3");
    postWithoutResponse("introspect/tasks/task-3/deliver/e2eFails/q-task-3");

    assertNull(object("introspect/aggregates/q-task-3").get("results"), "the handler's change was rolled back");
    assertEquals(List.of("task-3"), strings("introspect/failed-tasks"));
    assertTrue(strings("introspect/completed-tasks").isEmpty(), "a failed task must not be completed");

  }

  @Test
  @DisplayName("A @TaskId handler leaves the task open, completeTask finishes it after the commit")
  public void asyncTaskStaysOpenAndIsCompletedLater() throws Exception {

    postWithoutResponse("introspect/aggregates/q-task-4");
    postWithoutResponse("introspect/tasks/task-4/deliver/e2eAsync/q-task-4");

    final var aggregate = object("introspect/aggregates/q-task-4");
    assertEquals("async-open", aggregate.get("results"));
    assertEquals("task-4", aggregate.get("taskId"));
    assertTrue(strings("introspect/open-tasks").contains("task-4"));
    assertTrue(strings("introspect/completed-tasks").isEmpty());

    assertThePreflightRanBeforeTheCommitReturned(post("introspect/tasks/task-4/complete/q-task-4"));

    await(
        () -> strings("introspect/completed-tasks").contains("task-4"),
        "the SYNC completion to be dispatched after the commit");
    assertTrue(strings("introspect/execution-modes/completeTask/task-4").contains("SYNC"));

  }

  @Test
  @DisplayName("cancelTask sends the BPMN error after the commit")
  public void cancelTaskSendsTheBpmnErrorAfterTheCommit() throws Exception {

    postWithoutResponse("introspect/aggregates/q-task-5");
    postWithoutResponse("introspect/tasks/task-5/deliver/e2eAsync/q-task-5");

    post("introspect/tasks/task-5/cancel/q-task-5/PAYMENT_FAILED");

    await(
        () -> strings("introspect/errored-tasks").contains("task-5:PAYMENT_FAILED"),
        "the SYNC cancellation to be dispatched after the commit");
    assertEquals(List.of("SYNC"), strings("introspect/execution-modes/completeTaskByError/task-5"));

  }

  @Test
  @DisplayName("Completing an unknown task raises the guiding TaskNotFoundException")
  public void unknownTaskRaisesTheGuidingException() {

    postWithoutResponse("introspect/aggregates/q-task-6");

    final var failed = post("introspect/tasks/no-such-task/complete/q-task-6");
    assertEquals("TaskNotFoundException", failed.get("exception"));
    assertTrue(
        failed
            .get("message")
            .toString()
            .contains("no-such-task"),
        "the message has to name the task but was: "
            + failed.get("message"));

  }

  @Test
  @DisplayName("A user task is notified on creation and completed after the commit")
  public void userTaskNotificationAndCompletion() throws Exception {

    postWithoutResponse("introspect/aggregates/q-user-1");
    postWithoutResponse("introspect/tasks/utask-1/deliver/e2eApprove/q-user-1");

    final var aggregate = object("introspect/aggregates/q-user-1");
    assertEquals("usertask-created", aggregate.get("results"));
    assertEquals("utask-1", aggregate.get("taskId"));
    assertTrue(strings("introspect/completed-tasks").isEmpty(), "a notification must not complete the task");

    assertThePreflightRanBeforeTheCommitReturned(post("introspect/user-tasks/utask-1/complete/q-user-1"));

    await(
        () -> strings("introspect/completed-tasks").contains("utask-1"),
        "the SYNC user-task completion to be dispatched after the commit");

  }

  @Test
  @DisplayName("cancelUserTask sends the BPMN error after the commit")
  public void cancelUserTaskSendsTheBpmnError() throws Exception {

    postWithoutResponse("introspect/aggregates/q-user-2");
    postWithoutResponse("introspect/tasks/utask-2/deliver/e2eApprove/q-user-2");

    post("introspect/user-tasks/utask-2/cancel/q-user-2/APPROVAL_WITHDRAWN");

    await(
        () -> strings("introspect/errored-tasks").contains("utask-2:APPROVAL_WITHDRAWN"),
        "the SYNC user-task cancellation to be dispatched after the commit");

  }

  @Test
  @DisplayName("correlateMessage dispatches after the commit only, keyed on the aggregate id")
  public void correlateMessageDispatchesAfterTheCommit() throws Exception {

    postWithoutResponse("introspect/aggregates/q-msg-1");

    final var insideTransaction = post("introspect/messages/E2ePaymentReceived/correlate/q-msg-1");
    assertEquals(
        0,
        insideTransaction.get("correlationsInsideTransaction"),
        "no preflight is even possible here - the API's command classes are final (GAPS entry 11)");

    await(
        () -> strings("introspect/correlated-messages").contains("E2ePaymentReceived:q-msg-1"),
        "the correlation to be dispatched after the commit");

  }

  @Test
  @DisplayName("A correlation id becomes the correlation key and a rollback correlates nothing")
  public void correlationIdAndRollback() throws Exception {

    postWithoutResponse("introspect/aggregates/q-msg-2");

    post("introspect/messages/E2eItemShipped/correlate/q-msg-2/item-7");
    await(
        () -> strings("introspect/correlated-messages").contains("E2eItemShipped:item-7"),
        "the correlation-id correlation to be dispatched");

    postWithoutResponse("introspect/messages/E2eNeverSent/correlate-and-rollback/q-msg-2");
    awaitNothingElseHappens();
    assertFalse(
        strings("introspect/correlated-messages")
            .stream()
            .anyMatch(message -> message.startsWith("E2eNeverSent")),
        "a rolled-back transaction must never correlate");

  }

  @Test
  @DisplayName("startWorkflowByMessage publishes the aggregate state, never message content")
  public void startWorkflowByMessagePublishesTheAggregateState() throws Exception {

    postWithoutResponse("introspect/messages/E2eOrderPlaced/start/q-msg-3");

    await(
        () -> startedInstances()
            .stream()
            .anyMatch(variables -> "q-msg-3".equals(variables.get("id"))),
        "the start-by-message to be dispatched after the commit");

    final var variables = startedInstances()
        .stream()
        .filter(candidate -> "q-msg-3".equals(candidate.get("id")))
        .findFirst()
        .orElseThrow();
    assertTrue(variables.containsKey("results"));
    assertFalse(variables.containsKey("secret"));

  }

  @Test
  @DisplayName("sendSignal broadcasts after the commit")
  public void sendSignalBroadcastsAfterTheCommit() throws Exception {

    postWithoutResponse("introspect/signals/E2eSomethingHappened");

    await(
        () -> strings("introspect/signals").contains("E2eSomethingHappened"),
        "the signal to be broadcast after the commit");

  }

  @Test
  @DisplayName("aggregateChanged is answered with the adapter's guiding message (GAPS entry 18)")
  public void aggregateChangedIsNotSupported() {

    postWithoutResponse("introspect/aggregates/q-changed-1");

    final var failed = post("introspect/aggregate-changed/q-changed-1");
    assertEquals("UnsupportedOperationException", failed.get("exception"));
    final var message = failed
        .get("message")
        .toString();
    assertTrue(message.contains(PROCESS), "the message has to name the workflow but was: "
        + message);
    assertTrue(message.contains("GAPS.md entry 18"), "the message has to point at the deviation but was: "
        + message);

  }

  @Test
  @DisplayName("The viewer API reports the definition and version the boot deployed")
  public void viewerApiReportsTheDeployedVersion() throws Exception {

    post("introspect/workflows/q-viewer-1");
    await(
        () -> startedInstances()
            .stream()
            .anyMatch(variables -> "q-viewer-1".equals(variables.get("id"))),
        "the workflow to be started");

    final var definitions = strings("introspect/process-definitions/q-viewer-1");
    assertEquals(1, definitions.size(), "call activities are not reported (GAPS.md)");
    // the adapter composes a definition id of its own, the API has none
    final var reported = definitions
        .getFirst()
        .split("\\|", -1);
    final var definitionId = reported[0]
        + "|"
        + reported[1];
    assertTrue(reported[0].endsWith(MODULE), "the definition id is scoped by the workflow module: "
        + definitionId);
    assertEquals(PROCESS, reported[1]);
    assertEquals(PROCESS, reported[2]);
    // the version is the deployment key - the only version information the API offers
    assertNotNull(reported[3]);
    assertFalse(reported[3].isBlank(), "the deployment key is the version");

    final var xml = api()
        .get("introspect/bpmn-xml/{id}", definitionId)
        .then()
        .statusCode(200)
        .extract()
        .asString();
    assertTrue(xml.contains(PROCESS), "the BPMN served has to be the one deployed at boot");

    final var history = object("introspect/workflow-history/q-viewer-1");
    assertEquals(definitionId, history.get("processDefinitionId"));
    assertNull(history.get("elementsHistory"), "the API has no history at all (GAPS.md)");

  }

  @Test
  @DisplayName("A duplicate delivery converges and a completion failing after the commit is redelivered")
  public void atLeastOnceResidual() {

    postWithoutResponse("introspect/aggregates/q-task-7");
    postWithoutResponse("introspect/tasks/task-7/deliver/e2eHappy/q-task-7");
    postWithoutResponse("introspect/tasks/task-7/deliver/e2eHappy/q-task-7");

    assertEquals("happy", object("introspect/aggregates/q-task-7").get("results"), "the handler is idempotent");
    assertEquals(2, strings("introspect/completed-tasks").size());

    postWithoutResponse("introspect/reset");
    postWithoutResponse("introspect/aggregates/q-task-8");
    postWithoutResponse("introspect/tasks/task-8/fail-next-completion");
    postWithoutResponse("introspect/tasks/task-8/deliver/e2eHappy/q-task-8");

    // the completion failed AFTER the local commit - the aggregate keeps its state
    assertEquals("happy", object("introspect/aggregates/q-task-8").get("results"));
    assertTrue(strings("introspect/completed-tasks").isEmpty());

    // the engine redelivers eventually and the second delivery converges
    postWithoutResponse("introspect/tasks/task-8/deliver/e2eHappy/q-task-8");
    assertEquals("happy", object("introspect/aggregates/q-task-8").get("results"));
    assertEquals(List.of("task-8"), strings("introspect/completed-tasks"));

  }

}
