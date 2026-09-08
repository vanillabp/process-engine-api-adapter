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
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.quarkus.observersample.ObserverAggregate;
import io.vanillabp.pea.quarkus.observersample.ObserverWorkflowService;
import io.vanillabp.pea.quarkus.observersample.RecordingUserTaskObserver;
import jakarta.inject.Inject;

/**
 * The user-task observer seam on Quarkus: a CDI bean of {@link PeaUserTaskObserver} is found
 * by the extension (which declares the type unremovable, since nothing of the application
 * injects it), handed to the deployment services while they are produced, and told about
 * every delivery of every user-task subscription plus the terminations the engine reports.
 * A second observer which throws costs neither the task nor the recording one.
 * <p>
 * The full matrix - the order observers are called in, what an observation carries where the
 * delivery leaves something open - runs in the core's {@code PeaUserTaskObserverTest} and on
 * Spring Boot; this test proves the Quarkus glue.
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

  @BeforeEach
  public void clearState() {

    ObserverWorkflowService.AGGREGATES.clear();
    RecordingUserTaskObserver.DELIVERED.clear();
    RecordingUserTaskObserver.TERMINATED.clear();
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

    // the observer which throws is registered next to the recording one and changes
    // nothing: the notification ran too
    Assertions.assertEquals(
        "notified:q-obs-1",
        ObserverWorkflowService.AGGREGATES.get("q-5001").results);

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
