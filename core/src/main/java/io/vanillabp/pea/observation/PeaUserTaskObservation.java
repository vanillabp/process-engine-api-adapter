package io.vanillabp.pea.observation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.bpmcrafters.processengineapi.task.TaskInformation;

/**
 * One user task of one workflow module, as the Process-Engine-API delivered it to the
 * subscription of this adapter, or took it back.
 * <p>
 * The adapter worked out the identifiers in front while it routed the delivery: the
 * configured engine the task came from, the workflow module and BPMN process of the task, the
 * subscription which delivered it, and the workflow aggregate the payload named. Everything
 * else is what the engine said, passed on unchanged. The meta map differs from engine to
 * engine, and an observer usually reads more of it than the adapter needs.
 * <p>
 * The identifiers are PLAIN, never the scoped ones the deployed bytes carry: the subscription
 * is keyed by the scoped task definition and the engine reports the scoped BPMN process id,
 * and {@code PeaUserTaskHandler} translates both back before building this, so an observer
 * sees the names the application's BPMN and its configuration use (see decision 2 in the
 * repository's DECISIONS.md). {@code UserTaskObserverScopingIntegrationTest} holds that under
 * <code>use-prefix</code>.
 *
 * @param adapterId The configured adapter id whose engine delivered this task
 * @param workflowModuleId The workflow module the subscription belongs to
 * @param bpmnProcessId The BPMN process the task belongs to, or <code>null</code> if that
 *          cannot be told. That happens when the engine named none in the meta map and the
 *          subscription serves several processes, mostly for a termination
 * @param taskDefinition The subscription's task definition - the external form reference of
 *          the user task
 * @param workflowAggregateId The id of the workflow aggregate as a text, or <code>null</code>
 *          if the delivery carried none. Then the BPMN process has no workflow aggregate in
 *          this application, or the subscription did not ask the engine for the variable
 *          which holds it. A termination carries no payload at all
 * @param taskInformation What the engine says about the task: its id and its meta map
 * @param payload The variables the subscription asked the engine for, empty for a termination
 */
public record PeaUserTaskObservation(
                                     String adapterId,
                                     String workflowModuleId,
                                     String bpmnProcessId,
                                     String taskDefinition,
                                     String workflowAggregateId,
                                     TaskInformation taskInformation,
                                     Map<String, Object> payload) {

  /**
   * Checks the values an observer cannot do without. It makes the payload unmodifiable, so
   * an observer which keeps the observation cannot change what the next one sees.
   */
  public PeaUserTaskObservation {
    // what the observation is about. Without these, it belongs to no workflow module. If an
    // observer found that out later, while building a report, it could name neither the task
    // nor whoever handed it over
    Objects.requireNonNull(adapterId, "adapterId");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId");
    Objects.requireNonNull(taskDefinition, "taskDefinition");
    Objects.requireNonNull(taskInformation, "taskInformation");
    // not Map.copyOf, which refuses null: a process variable an engine holds as null is a
    // value like any other, and an observer passes it on as null
    payload = payload == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  /**
   * The id the engine gave the task, which is what a completion is addressed to.
   *
   * @return The engine's own id of the user task
   */
  public String taskId() {

    return taskInformation.getTaskId();

  }

  /**
   * What the engine says about the task apart from its payload: who it is assigned to, when
   * it is due, whatever that engine fills in. The engine decides which keys are there. So an
   * observer asks for a key and takes no answer for granted.
   *
   * @return What the engine says about the task, which the Process-Engine-API guarantees to
   *         be a map and may be an empty one
   */
  public Map<String, String> meta() {

    return taskInformation.getMeta();

  }

  /**
   * Why the engine reported the task, if the engine says so. An observer which builds a
   * report needs it to tell a new task from a new assignee of a task it already has.
   *
   * @return Why the engine reported the task - <code>create</code>, <code>assign</code>,
   *         <code>update</code>, <code>complete</code> or <code>delete</code> in the
   *         vocabulary the API's own engine adapters use - or <code>null</code> where the
   *         engine names no reason
   */
  public String reason() {

    return meta().get(TaskInformation.REASON);

  }

}
