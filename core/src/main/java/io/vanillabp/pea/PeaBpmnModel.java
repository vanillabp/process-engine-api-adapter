package io.vanillabp.pea;

import java.util.List;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The adapter's BPMN model type ({@code BPMN} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}).
 * <p>
 * The bpm-crafters Process-Engine-API deliberately has no BPMN model abstraction: its
 * {@code DeploymentApi} only accepts opaque {@code NamedResource}s (filename + bytes).
 * Because VanillaBP wires business code per BPMN process id, the adapter needs at least
 * the process id in addition to the raw resource - so this record carries it explicitly.
 * <p>
 * The {@code bpmnProcessId} is extracted from the BPMN XML by
 * {@code PeaDeploymentService.readBpmn} using a JDK StAX parser. See {@code GAPS.md} (first
 * entry): a BPMS-agnostic API without a model type forces every consumer to parse BPMN
 * itself. The names of the process and of its user tasks are read in the same pass, so
 * whoever needs them does not have to read the same bytes a second time.
 *
 * @param filename The name of the BPMN resource (used for logging and deployment)
 * @param resource The raw BPMN XML bytes
 * @param bpmnProcessId The id of the executable process contained in the resource
 * @param processName The <code>name</code> attribute of the process element, what a
 *          modeller wrote on it. May be <code>null</code>: a process needs no name
 * @param tasks The service-like tasks of the process to be wired to
 *          {@code @WorkflowTask} methods (activity id + task definition - the
 *          <code>zeebe:taskDefinition</code> type; the Process-Engine-API itself
 *          has no notion of task definitions in BPMN, see {@code GAPS.md})
 * @param userTasks The user tasks of the process, each with its element id, its external
 *          form reference and the name the modeller wrote on it
 */
public record PeaBpmnModel(
                           String filename,
                           byte[] resource,
                           String bpmnProcessId,
                           String processName,
                           List<BpmnTaskSpec> tasks,
                           List<BpmnTaskSpec> userTasks) {

  /**
   * Convenience constructor for a process without a name and without user tasks.
   *
   * @param filename The name of the BPMN resource
   * @param resource The raw BPMN XML bytes
   * @param bpmnProcessId The id of the executable process
   * @param tasks The service-like tasks of the process
   */
  public PeaBpmnModel(
      final String filename,
      final byte[] resource,
      final String bpmnProcessId,
      final List<BpmnTaskSpec> tasks) {

    this(filename, resource, bpmnProcessId, null, tasks, List.of());

  }

  /**
   * Convenience constructor for a process without a name.
   *
   * @param filename The name of the BPMN resource
   * @param resource The raw BPMN XML bytes
   * @param bpmnProcessId The id of the executable process
   * @param tasks The service-like tasks of the process
   * @param userTasks The user tasks of the process
   */
  public PeaBpmnModel(
      final String filename,
      final byte[] resource,
      final String bpmnProcessId,
      final List<BpmnTaskSpec> tasks,
      final List<BpmnTaskSpec> userTasks) {

    this(filename, resource, bpmnProcessId, null, tasks, userTasks);

  }

}
