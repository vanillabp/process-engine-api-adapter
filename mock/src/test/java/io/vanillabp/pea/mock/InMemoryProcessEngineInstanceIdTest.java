package io.vanillabp.pea.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.ExecutionMode;
import dev.bpmcrafters.processengineapi.process.StartProcessCommand;
import dev.bpmcrafters.processengineapi.task.CompleteTaskCmd;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler;
import dev.bpmcrafters.processengineapi.task.TaskType;

/**
 * The API promises that the <code>instanceId</code> a start answers with is the
 * <code>processInstanceId</code> in the meta of every task of that instance. The in-memory
 * engine keeps that promise, so an adapter tested against it may rely on it.
 */
public class InMemoryProcessEngineInstanceIdTest {

  private static final String PROCESS = "Order";

  private static final String TASK = "review";

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final List<TaskInformation> delivered = new CopyOnWriteArrayList<>();

  private final List<TaskInformation> terminated = new CopyOnWriteArrayList<>();

  /**
   * A start command which names its BPMN process the way the adapter's own command does.
   *
   * @param getBpmnProcessId The process to start
   * @param get The start variables
   * @param executionMode The phase of the start
   */
  public record Start(String getBpmnProcessId, Map<String, Object> get,
                      ExecutionMode executionMode) implements StartProcessCommand {

  }

  @BeforeEach
  public void subscribe() {

    engine
        .subscribeForTask(new SubscribeForTaskCmd(
            Map.of(), TaskType.USER, TASK, Set.of(), (
                task,
                payload) -> delivered.add(task), (TaskTerminationHandler) terminated::add));

  }

  private String start(
      final String aggregateId) {

    return engine
        .startProcess(new Start(PROCESS, Map.of("id", aggregateId, "customer", "Fritz"), ExecutionMode.SYNC))
        .join()
        .getInstanceId();

  }

  private String instanceOf(
      final TaskInformation task) {

    return task.getMeta().get(CommonRestrictions.PROCESS_INSTANCE_ID);

  }

  @Test
  @DisplayName("Every task of an instance names the id the start answered with")
  public void everyTaskNamesTheIdOfTheStart() {

    final var first = start("4711");
    final var second = start("4712");
    assertNotEquals(first, second);

    engine.deliverTask("task-1", TASK, PROCESS, Map.of("id", "4711"));
    engine.deliverTask("task-2", TASK, PROCESS, Map.of("id", "4712", "customer", "Fritz"));
    engine.deliverTask("task-3", TASK, TaskType.USER, PROCESS, Map.of("id", "4711"));

    assertEquals(first, instanceOf(delivered.get(0)));
    assertEquals(second, instanceOf(delivered.get(1)));
    assertEquals(first, instanceOf(delivered.get(2)));

  }

  @Test
  @DisplayName("A task names its instance when it is gone, too")
  public void aTerminationNamesTheInstance() {

    final var instance = start("4711");
    engine.deliverTask("task-1", TASK, PROCESS, Map.of("id", "4711"));
    engine.deliverTask("task-2", TASK, PROCESS, Map.of("id", "4711"));

    engine.completeTask(new CompleteTaskCmd("task-1")).join();
    engine.terminateTask("task-2", TASK, PROCESS, TaskInformation.DELETE);

    assertEquals(instance, instanceOf(terminated.get(0)));
    assertEquals(instance, instanceOf(terminated.get(1)));

  }

  @Test
  @DisplayName("A task of no started instance names none, and a named one wins")
  public void aTaskOfNoStartedInstanceNamesNone() {

    start("4711");
    // never started
    engine.deliverTask("task-1", TASK, PROCESS, Map.of("id", "4799"));
    // another process with the same aggregate: a called process has an instance of its own
    engine.deliverTask("task-2", TASK, "Called", Map.of("id", "4711"));
    // the caller says which instance, like a test of a called process does
    engine
        .deliverTask(
            "task-3", TASK, PROCESS, Map.of("id", "4711"), Map
                .of(CommonRestrictions.PROCESS_INSTANCE_ID, "the-called-instance"));
    // a start which only asked creates no instance
    engine.startProcess(new Start(PROCESS, Map.of("id", "4800"), ExecutionMode.PREFLIGHT_CHECK)).join();
    engine.deliverTask("task-4", TASK, PROCESS, Map.of("id", "4800"));

    assertFalse(delivered.get(0).getMeta().containsKey(CommonRestrictions.PROCESS_INSTANCE_ID));
    assertFalse(delivered.get(1).getMeta().containsKey(CommonRestrictions.PROCESS_INSTANCE_ID));
    assertEquals("the-called-instance", instanceOf(delivered.get(2)));
    assertFalse(delivered.get(3).getMeta().containsKey(CommonRestrictions.PROCESS_INSTANCE_ID));

  }

}
