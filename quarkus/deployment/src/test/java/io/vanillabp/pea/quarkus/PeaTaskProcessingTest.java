package io.vanillabp.pea.quarkus;

import java.util.Map;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.quarkus.tasksample.TaskAggregate;
import io.vanillabp.pea.quarkus.tasksample.TaskWorkflowService;
import jakarta.inject.Inject;

/**
 * Task processing on Quarkus: the adapter subscribes per task
 * definition at boot and a delivered task dispatches through the core's
 * transaction runner (JTA transaction + activated request context) into the
 * {@code @WorkflowTask} handler - the aggregate mutation is committed BEFORE the
 * task completion reaches the mock engine. The full outcome matrix runs on Spring
 * Boot ({@code TaskProcessingIntegrationTest}); this test proves the Quarkus glue.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaTaskProcessingTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.pea.quarkus.tasksample")
          .addAsResource("tasks/application.yaml", "application.yaml")
          .addAsResource(
              "pea-test-module/processes/tasks/pea-quarkus-task.bpmn",
              "pea-test-module/processes/tasks/pea-quarkus-task.bpmn")
          .addAsResource("META-INF/workflow-module", "META-INF/workflow-module"));

  @Inject
  InMemoryProcessEngine inMemoryProcessEngine;

  @BeforeEach
  public void clearState() {

    TaskWorkflowService.AGGREGATES.clear();
    inMemoryProcessEngine.clearTaskRecordings();

  }

  @Test
  public void subscriptionOpenedAtBoot() {

    final var subscribed = inMemoryProcessEngine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();
    Assertions.assertEquals(
        java.util.List.of("quarkusHappy"),
        subscribed,
        "expected one subscription per task definition of the deployed BPMN");

    // The subscription names what the delivery has to carry - here the
    // aggregate-ID variable alone, since the handler declares no @TaskParam. The
    // 'fetch-variables: all' of the yaml belongs to another task definition and must
    // not reach this subscription
    Assertions.assertEquals(
        java.util.Set.of("id"),
        inMemoryProcessEngine
            .getSubscriptions()
            .getFirst()
            .payloadDescription(),
        "an empty set would mean 'hand me the complete payload of the process instance'");

  }

  @Test
  public void deliveredTaskRunsHandlerAndCompletes() {

    final var aggregate = new TaskAggregate();
    aggregate.id = "q-4711";
    TaskWorkflowService.AGGREGATES.put(aggregate.id, aggregate);

    inMemoryProcessEngine.deliverTask(
        "task-q-1",
        "quarkusHappy",
        "QuarkusTaskProcess",
        Map.of("id", "q-4711"));

    // the mutation was committed (visible via the copying store) ...
    Assertions.assertEquals("happy", TaskWorkflowService.AGGREGATES.get("q-4711").results);
    // ... and the task was completed afterwards
    Assertions.assertEquals(
        java.util.List.of(new InMemoryProcessEngine.CompletedTask("task-q-1")),
        inMemoryProcessEngine.getCompletedTasks());

  }

}
