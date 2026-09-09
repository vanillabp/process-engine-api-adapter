package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.observation.PeaUserTaskObservation;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObservers;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;

/**
 * The observer seam of the user-task subscription: who is told about a delivery, in which
 * order, what the observation carries where the delivery leaves something open, and what an
 * observer which throws costs the task and the other observers.
 * <p>
 * The end-to-end proof that a bean of the application arrives here runs on both platforms
 * ({@code UserTaskObserverIntegrationTest} on Spring Boot,
 * {@code PeaUserTaskObserverTest} on Quarkus).
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaUserTaskObserverTest {

  /**
   * Writes down what it was told, under the name it was registered with.
   */
  static class RecordingObserver implements PeaUserTaskObserver {

    private final String name;

    private final List<String> log;

    final List<PeaUserTaskObservation> delivered = new ArrayList<>();

    final List<PeaUserTaskObservation> terminated = new ArrayList<>();

    RecordingObserver(
        final String name,
        final List<String> log) {

      this.name = name;
      this.log = log;

    }

    @Override
    public void userTaskDelivered(
        final PeaUserTaskObservation observation) {

      delivered.add(observation);
      log.add(name
          + ":delivered:"
          + observation.taskId());

    }

    @Override
    public void userTaskTerminated(
        final PeaUserTaskObservation observation) {

      terminated.add(observation);
      log.add(name
          + ":terminated:"
          + observation.taskId());

    }

  }

  /**
   * The one which is broken - both directions, the way an extension is broken which nobody
   * of this application wrote.
   */
  static class ThrowingObserver implements PeaUserTaskObserver {

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

  /**
   * The core's runtime entry point of {@link PeaUserTaskHandlerTest}, plus the one thing
   * these tests vary: whether the BPMN process has a workflow aggregate at all.
   */
  static class ObserverTestInvoker extends PeaUserTaskHandlerTest.RecordingInvoker {

    boolean aggregateKnown = true;

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      if (!aggregateKnown) {
        throw new IllegalStateException(
            "no workflow aggregate for '%s'".formatted(bpmnProcessId));
      }
      return "id";

    }

  }

  private final ObserverTestInvoker invoker = new ObserverTestInvoker();

  private final List<String> callLog = new ArrayList<>();

  private PeaUserTaskHandler handler(
      final List<String> bpmnProcessIds,
      final PeaUserTaskObserver... observers) {

    return PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(bpmnProcessIds)
        .workflowTaskInvoker(invoker)
        .observers(PeaUserTaskObservers.of("pea", List.of(observers)))
        .build();

  }

  @Test
  @DisplayName("Every observer sees the delivery, in the order it was handed over")
  public void everyObserverSeesTheDelivery() {

    final var first = new RecordingObserver("first", callLog);
    final var second = new RecordingObserver("second", callLog);

    handler(List.of("OnlyProcess"), first, second)
        .accept(
            new TaskInformation("utask-1", Map.of()).withReason(TaskInformation.CREATE),
            Map.of("id", "4711", "amount", 12));

    assertEquals(List.of("first:delivered:utask-1", "second:delivered:utask-1"), callLog);

    final var observation = first.delivered.getFirst();
    assertEquals("pea", observation.adapterId());
    assertEquals("test-module", observation.workflowModuleId());
    assertEquals("OnlyProcess", observation.bpmnProcessId());
    assertEquals("approve", observation.taskDefinition());
    assertEquals("4711", observation.workflowAggregateId());
    assertEquals("utask-1", observation.taskId());
    assertEquals(TaskInformation.CREATE, observation.reason());
    assertEquals(Map.of("id", "4711", "amount", 12), observation.payload());
    // the same observation, not a second one built per observer
    assertSame(observation, second.delivered.getFirst(), "expected one observation for all");

  }

  @Test
  @DisplayName("A delivery no @WorkflowTask method claims reaches the observers anyway")
  public void unclaimedDeliveryStillReachesTheObservers() {

    invoker.handlerExists = false;
    final var observer = new RecordingObserver("only", callLog);

    handler(List.of("OnlyProcess"), observer)
        .accept(new TaskInformation("utask-2", Map.of()), Map.of("id", "4712"));

    assertEquals(1, observer.delivered.size(), "a task list shows a task the application has no code for");
    assertEquals("4712", observer.delivered.getFirst().workflowAggregateId());
    assertNull(invoker.invokedBpmnProcessId, "the application's handler must not be invoked");

  }

  @Test
  @DisplayName("A throwing observer changes nothing for the task and nothing for the next observer")
  public void aThrowingObserverIsHarmless() {

    final var after = new RecordingObserver("after", callLog);

    handler(List.of("OnlyProcess"), new ThrowingObserver(), after)
        .accept(new TaskInformation("utask-3", Map.of()), Map.of("id", "4713"));

    assertEquals(1, after.delivered.size(), "the next observer is called anyway");
    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId, "the notification ran anyway");

  }

  @Test
  @DisplayName("Without a workflow aggregate the observation carries no aggregate id")
  public void withoutAWorkflowAggregateNoAggregateId() {

    invoker.aggregateKnown = false;
    final var observer = new RecordingObserver("only", callLog);

    handler(List.of("OnlyProcess"), observer)
        .accept(new TaskInformation("utask-4", Map.of()), Map.of("something", "else"));

    assertNull(observer.delivered.getFirst().workflowAggregateId());
    assertEquals("utask-4", observer.delivered.getFirst().taskId());

  }

  @Test
  @DisplayName("A variable the subscription did not ask for leaves the aggregate id open")
  public void anUnfetchedAggregateIdLeavesItOpen() {

    final var observer = new RecordingObserver("only", callLog);

    handler(List.of("OnlyProcess"), observer)
        .accept(new TaskInformation("utask-5", Map.of()), Map.of("something", "else"));

    assertNull(observer.delivered.getFirst().workflowAggregateId());

  }

  @Test
  @DisplayName("A termination carries the engine's reason and no payload")
  public void terminationCarriesTheReason() {

    final var first = new RecordingObserver("first", callLog);
    final var second = new RecordingObserver("second", callLog);

    handler(List.of("OnlyProcess"), first, new ThrowingObserver(), second)
        .terminated(
            new TaskInformation("utask-6", Map.of("bpmnProcessId", "OnlyProcess"))
                .withReason(TaskInformation.DELETE));

    assertEquals(List.of("first:terminated:utask-6", "second:terminated:utask-6"), callLog);

    final var observation = second.terminated.getFirst();
    assertEquals(TaskInformation.DELETE, observation.reason());
    assertEquals("OnlyProcess", observation.bpmnProcessId());
    assertEquals("approve", observation.taskDefinition());
    assertNull(observation.workflowAggregateId(), "a termination carries no payload");
    assertTrue(observation.payload().isEmpty());

  }

  @Test
  @DisplayName("A termination of an ambiguous subscription names no BPMN process")
  public void ambiguousTerminationNamesNoProcess() {

    final var observer = new RecordingObserver("only", callLog);

    handler(List.of("ProcessA", "ProcessB"), observer)
        .terminated(new TaskInformation("utask-7", Map.of()).withReason(TaskInformation.COMPLETE));

    final var observation = observer.terminated.getFirst();
    assertNull(observation.bpmnProcessId(), "two processes behind one form reference and no meta");
    assertEquals(TaskInformation.COMPLETE, observation.reason());
    assertNotNull(observation.workflowModuleId(), "what a report needs to place the task at all");

  }

  @Test
  @DisplayName("Without an observer no observation is built")
  public void withoutAnObserverNothingIsBuilt() {

    final PeaUserTaskObservers nobody = PeaUserTaskObservers.of("pea", List.of());

    assertTrue(nobody.isEmpty());
    assertTrue(nobody.names().isEmpty());
    nobody.delivered(
        "utask-nobody", () -> fail("an application without an observer pays nothing for the seam"));
    nobody.terminated(
        "utask-nobody", () -> fail("an application without an observer pays nothing for the seam"));

    // and a handler built without any observer at all behaves like one built with an empty
    // list - the platform modules hand over what they found, which may be nothing
    PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(List.of("OnlyProcess"))
        .workflowTaskInvoker(invoker)
        .build()
        .accept(new TaskInformation("utask-8", Map.of()), Map.of("id", "4718"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId);

  }

  @Test
  @DisplayName("An unroutable delivery reaches the observers before it fails")
  public void anUnroutableDeliveryStillReachesTheObservers() {

    final var observer = new RecordingObserver("only", callLog);

    // two BPMN processes behind one form reference and no meta entry naming the process:
    // the application's notification cannot be routed, a task list still shows the task
    handler(List.of("ProcessA", "ProcessB"), observer)
        .accept(new TaskInformation("utask-10", Map.of()), Map.of("id", "4720"));

    final var observation = observer.delivered.getFirst();
    assertNull(observation.bpmnProcessId());
    assertNull(observation.workflowAggregateId(), "without a process there is no aggregate to name");
    assertEquals("utask-10", observation.taskId());
    assertNull(invoker.invokedBpmnProcessId, "the notification cannot be routed and does not run");

  }

  @Test
  @DisplayName("A failure while describing the task costs the application nothing")
  public void aFailingDescriptionCostsTheApplicationNothing() {

    final var observers = PeaUserTaskObservers.of("pea", List.of(new RecordingObserver("only", callLog)));
    observers.delivered("utask-11", () -> {
      throw new IllegalStateException("boom-description");
    });

    assertTrue(callLog.isEmpty(), "there was nothing to hand anybody");

    // and the same failure inside a delivery leaves the notification untouched: the
    // observation is built lazily, so a broken description is caught where it happens
    final var handler = PeaUserTaskHandler
        .builder()
        .adapterId("pea")
        .workflowModuleId("test-module")
        .externalFormReference("approve")
        .bpmnProcessIds(List.of("OnlyProcess"))
        .workflowTaskInvoker(invoker)
        .observers(PeaUserTaskObservers.of("pea", List.of(new PeaUserTaskObserver() {

          @Override
          public void userTaskDelivered(
              final PeaUserTaskObservation observation) {
            // the observation was built, so this one is the observer's own failure
            throw new IllegalStateException("boom-observer");
          }

          @Override
          public void userTaskTerminated(
              final PeaUserTaskObservation observation) {
          }

        })))
        .build();
    handler.accept(new TaskInformation("utask-12", Map.of()), Map.of("id", "4721"));

    assertEquals("OnlyProcess", invoker.invokedBpmnProcessId, "the notification ran anyway");

  }

  @Test
  @DisplayName("An observation names what it is about")
  public void anObservationNamesWhatItIsAbout() {

    final var observation = new PeaUserTaskObservation(
        "pea", "test-module", null, "approve", null, new TaskInformation("utask-9", Map.of()), null);

    assertTrue(observation.payload().isEmpty(), "no payload is an empty one, never null");
    assertTrue(observation.meta().isEmpty());
    assertNull(observation.reason(), "an engine naming no reason is not a reason of its own");

  }

}
