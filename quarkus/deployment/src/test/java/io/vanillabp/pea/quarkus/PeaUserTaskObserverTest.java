package io.vanillabp.pea.quarkus;

import java.util.List;
import java.util.Map;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;
import io.vanillabp.pea.deployment.PeaDeployedProcessesRegistry;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObserverFailure;
import io.vanillabp.pea.quarkus.observersample.ObserverAggregate;
import io.vanillabp.pea.quarkus.observersample.ObserverWorkflowService;
import io.vanillabp.pea.quarkus.observersample.RecordingUserTaskObserver;
import io.vanillabp.pea.quarkus.observersample.ThrowingUserTaskObserver;
import jakarta.inject.Inject;

/**
 * The user-task observers on Quarkus. The extension finds a CDI bean of
 * {@link PeaUserTaskObserver}. It declares the type unremovable, because nothing of the
 * application injects it. The deployment services get the bean while they are produced. The
 * bean is told about every delivery of every user-task subscription, and about the
 * terminations the engine reports. A second observer which throws makes the delivery fail
 * towards the engine. The recording observer hears about the task before that happens.
 * <p>
 * The core's {@code PeaUserTaskObserverTest} and the Spring Boot tests run all the cases: the
 * order the observers are called in, and what an observation carries if the delivery leaves
 * something open. This test proves the Quarkus code which connects the parts.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaUserTaskObserverTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.pea.quarkus.observersample")
          .addAsResource("observer/application.yaml", "application.yaml")
          .addAsResource(
              "pea-test-module/processes/observer/pea-quarkus-observer.bpmn",
              "pea-test-module/processes/observer/pea-quarkus-observer.bpmn")
          .addAsResource("META-INF/workflow-module", "META-INF/workflow-module"));

  @Inject
  InMemoryProcessEngine inMemoryProcessEngine;

  @Inject
  PeaDeployedProcessesRegistry deployedProcesses;

  @BeforeEach
  public void clearState() {

    ObserverWorkflowService.AGGREGATES.clear();
    RecordingUserTaskObserver.DELIVERED.clear();
    RecordingUserTaskObserver.TERMINATED.clear();
    ThrowingUserTaskObserver.broken = false;
    inMemoryProcessEngine.clearTaskRecordings();

    final var aggregate = new ObserverAggregate();
    aggregate.id = "q-5001";
    ObserverWorkflowService.AGGREGATES.put(aggregate.id, aggregate);

  }

  @Test
  public void subscriptionsOfBothUserTasksOpened() {

    final var subscribed = inMemoryProcessEngine
        .getSubscriptions()
        .stream()
        .map(InMemoryProcessEngine.ActiveSubscription::taskDescriptionKey)
        .toList();
    Assertions.assertTrue(
        subscribed.containsAll(List.of("quarkusObserved", "quarkusUnclaimed")),
        "expected a subscription per user task but got: "
            + subscribed);

  }

  @Test
  public void deployedProcessesAnswerByBothUserTaskKeys() {

    final var deployed = deployedProcesses.forAdapter("pea");

    final var process = deployed.deployedVersionOf("pea-test-module", "QuarkusObserverProcess");
    Assertions.assertEquals("Observed process", process.processName());

    final var byElementId = process.userTaskByElementId("t_observed");
    Assertions.assertEquals("quarkusObserved", byElementId.taskDefinition());
    Assertions.assertEquals("The observed task", byElementId.name());
    Assertions.assertEquals(List.of(byElementId), process.userTasksByFormReference("quarkusObserved"));

    Assertions.assertEquals(
        List.of("QuarkusObserverProcess"),
        deployed
            .byUserTaskElementId("pea-test-module", "t_unclaimed")
            .stream()
            .map(PeaDeployedProcesses.DeployedProcess::bpmnProcessId)
            .toList());
    Assertions.assertEquals(
        "The unclaimed task",
        deployed
            .byUserTaskFormReference("pea-test-module", "quarkusUnclaimed")
            .getFirst()
            .userTasksByFormReference("quarkusUnclaimed")
            .getFirst()
            .name(),
        "a user task no @WorkflowTask method claims is in the index like every other");

  }

  @Test
  public void aVariableOnlyAnExtensionReadsIsDelivered() {

    Assertions.assertEquals(
        java.util.Set.of("cockpitOnly", "id"),
        inMemoryProcessEngine
            .getSubscriptions()
            .stream()
            .filter(subscription -> subscription.taskDescriptionKey().equals("quarkusUnclaimed"))
            .findFirst()
            .orElseThrow()
            .payloadDescription(),
        "the engine hands the task to this subscription alone, so it asks for what the "
            + "extension reads as well");

    inMemoryProcessEngine.deliverTask(
        "q-obs-7",
        "quarkusUnclaimed",
        "QuarkusObserverProcess",
        Map.of("id", "q-5001", "cockpitOnly", "shown", "nobodyReads", "dropped"));

    Assertions.assertEquals(
        Map.of("id", "q-5001", "cockpitOnly", "shown"),
        RecordingUserTaskObserver.DELIVERED.getFirst().payload());

  }

  @Test
  public void deliveredUserTaskReachesTheObserver() {

    inMemoryProcessEngine.deliverTask(
        "q-obs-1",
        "quarkusObserved",
        "QuarkusObserverProcess",
        Map.of("id", "q-5001"));

    Assertions.assertEquals(1, RecordingUserTaskObserver.DELIVERED.size());
    final var observation = RecordingUserTaskObserver.DELIVERED.getFirst();
    Assertions.assertEquals("pea", observation.adapterId());
    Assertions.assertEquals("pea-test-module", observation.workflowModuleId());
    Assertions.assertEquals("QuarkusObserverProcess", observation.bpmnProcessId());
    Assertions.assertEquals("quarkusObserved", observation.taskDefinition());
    Assertions.assertEquals("q-5001", observation.workflowAggregateId());
    Assertions.assertEquals("q-obs-1", observation.taskId());

    // The observer which throws is registered next to the recording one. Here it does not
    // throw, so the notification ran as on any other delivery.
    Assertions.assertEquals(
        "notified:q-obs-1",
        ObserverWorkflowService.AGGREGATES.get("q-5001").results);

  }

  @Test
  public void throwingObserverFailsTheDelivery() {

    ThrowingUserTaskObserver.broken = true;

    final var failure = Assertions
        .assertThrows(
            PeaUserTaskObserverFailure.class,
            () -> inMemoryProcessEngine.deliverTask(
                "q-obs-4",
                "quarkusObserved",
                "QuarkusObserverProcess",
                Map.of("id", "q-5001")));

    Assertions.assertTrue(
        failure.getMessage().contains(ThrowingUserTaskObserver.class.getName()),
        "the message has to name the observer: "
            + failure.getMessage());
    Assertions.assertTrue(failure.getMessage().contains("q-obs-4"), "and the task");
    Assertions.assertTrue(failure.getMessage().contains("pea-test-module"), "and the workflow module");

    Assertions.assertEquals(
        1,
        RecordingUserTaskObserver.DELIVERED.size(),
        "the other observer was told, whichever order ArC listed the two in");
    Assertions.assertEquals(
        "notified:q-obs-4",
        ObserverWorkflowService.AGGREGATES.get("q-5001").results,
        "the application keeps its notification: the failure goes out after it");

  }

  @Test
  public void unclaimedUserTaskReachesTheObserver() {

    inMemoryProcessEngine.deliverTask(
        "q-obs-2",
        "quarkusUnclaimed",
        "QuarkusObserverProcess",
        Map.of("id", "q-5001"));

    Assertions.assertEquals(1, RecordingUserTaskObserver.DELIVERED.size());
    Assertions.assertEquals(
        "quarkusUnclaimed",
        RecordingUserTaskObserver.DELIVERED.getFirst().taskDefinition());
    Assertions.assertNull(
        ObserverWorkflowService.AGGREGATES.get("q-5001").results,
        "no method claims this task, so nothing of the application ran");

  }

  @Test
  public void terminatedUserTaskReachesTheObserverWithItsReason() {

    inMemoryProcessEngine.deliverTask(
        "q-obs-3",
        "quarkusObserved",
        "QuarkusObserverProcess",
        Map.of("id", "q-5001"));
    inMemoryProcessEngine.terminateTask(
        "q-obs-3", "quarkusObserved", "QuarkusObserverProcess", TaskInformation.COMPLETE);

    Assertions.assertEquals(1, RecordingUserTaskObserver.TERMINATED.size());
    final var observation = RecordingUserTaskObserver.TERMINATED.getFirst();
    Assertions.assertEquals("q-obs-3", observation.taskId());
    Assertions.assertEquals("QuarkusObserverProcess", observation.bpmnProcessId());
    Assertions.assertEquals(TaskInformation.COMPLETE, observation.reason());
    Assertions.assertNull(observation.workflowAggregateId(), "a termination carries no payload");

  }

}
