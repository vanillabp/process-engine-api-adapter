package io.vanillabp.pea.observation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import dev.bpmcrafters.processengineapi.task.TaskInformation;

/**
 * One user task of one workflow module, as the Process-Engine-API delivered it to this
 * adapter's subscription or withdrew it again.
 * <p>
 * The identifiers in front are what the adapter resolved while routing the delivery: which of
 * its configured engines the task came from, which workflow module and BPMN process the task
 * belongs to, which subscription delivered it and which workflow aggregate the payload named.
 * Everything else is the engine's own word, handed on unchanged, because the meta map is
 * engine-specific and an observer usually reads more of it than the adapter needs.
 * <p>
 * The identifiers are PLAIN, never the scoped ones the deployed bytes carry: name-clash
 * avoidance is undone before a delivery reaches the adapter's handler, and an observer sees
 * the same names the application's BPMN and configuration use (see decision 2 in the
 * repository's DECISIONS.md).
 *
 * @param adapterId The configured adapter id whose engine delivered this task
 * @param workflowModuleId The workflow module the subscription belongs to
 * @param bpmnProcessId The BPMN process the task belongs to, or <code>null</code> where it
 *          cannot be told: the engine named none in the meta map and the subscription serves
 *          several processes, which a termination is the realistic case for
 * @param taskDefinition The subscription's task definition - the external form reference of
 *          the user task
 * @param workflowAggregateId The workflow aggregate's id, serialized, or <code>null</code>
 *          where the delivery carried none - the BPMN process has no workflow aggregate in
 *          this application, or the subscription did not ask the engine for the variable
 *          holding it, and a termination carries no payload at all
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

  public PeaUserTaskObservation {
    // what the observation is about: without them it cannot be placed under any workflow
    // module, and finding that out where a report is built would name neither the task nor
    // whoever handed it over
    Objects.requireNonNull(adapterId, "adapterId");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId");
    Objects.requireNonNull(taskDefinition, "taskDefinition");
    Objects.requireNonNull(taskInformation, "taskInformation");
    // not Map.copyOf: a process variable an engine holds as null is a value like any
    // other, and an observer passes it on as null
    payload = payload == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  /**
   * @return The engine's own id of the user task
   */
  public String taskId() {

    return taskInformation.getTaskId();

  }

  /**
   * @return What the engine says about the task, which the Process-Engine-API guarantees to
   *         be a map and may be an empty one
   */
  public Map<String, String> meta() {

    return taskInformation.getMeta();

  }

  /**
   * @return Why the engine reported the task - <code>create</code>, <code>assign</code>,
   *         <code>update</code>, <code>complete</code> or <code>delete</code> in the
   *         vocabulary the API's own engine adapters use - or <code>null</code> where the
   *         engine names no reason
   */
  public String reason() {

    return meta().get(TaskInformation.REASON);

  }

}
