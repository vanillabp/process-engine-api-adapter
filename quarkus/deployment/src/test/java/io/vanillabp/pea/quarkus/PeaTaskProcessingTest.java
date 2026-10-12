package io.vanillabp.pea.quarkus;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Task processing on Quarkus. The adapter opens one subscription per task definition at
 * boot. A delivered task goes through the transaction runner of the core (a JTA transaction
 * and an active request context) into the {@code @WorkflowTask} handler. The test checks
 * that the change to the aggregate was committed and that the task completion reached the
 * mock engine. It does not check the order of the two. The Spring Boot
 * tests run all the outcomes ({@code TaskProcessingIntegrationTest}). This test proves the
 * Quarkus code which connects the parts.
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
        List.of("quarkusHappy"),
        subscribed,
        "expected one subscription per task definition of the deployed BPMN");

    // The subscription names what the delivery has to carry. Here that is only the variable
    // with the aggregate ID, because the handler declares no @TaskParam.
    Assertions.assertEquals(
        Set.of("id"),
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

    // The change was committed, which the store shows because it keeps copies ...
    Assertions.assertEquals("happy", TaskWorkflowService.AGGREGATES.get("q-4711").results);
    // ... and the task was completed
    Assertions.assertEquals(
        List.of(new InMemoryProcessEngine.CompletedTask("task-q-1")),
        inMemoryProcessEngine.getCompletedTasks());

  }

}
