package io.vanillabp.pea;

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
 * itself.
 *
 * @param filename The name of the BPMN resource (used for logging and deployment)
 * @param resource The raw BPMN XML bytes
 * @param bpmnProcessId The id of the executable process contained in the resource
 * @param tasks The service-like tasks of the process to be wired to
 *          {@code @WorkflowTask} methods (activity id + task definition - the
 *          <code>zeebe:taskDefinition</code> type; the Process-Engine-API itself
 *          has no notion of task definitions in BPMN, see {@code GAPS.md})
 */
public record PeaBpmnModel(
                           String filename,
                           byte[] resource,
                           String bpmnProcessId,
                           java.util.List<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> tasks,
                           java.util.List<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> userTasks) {

  /**
   * Convenience constructor without user tasks (story 24 added them).
   */
  public PeaBpmnModel(
      final String filename,
      final byte[] resource,
      final String bpmnProcessId,
      final java.util.List<io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec> tasks) {

    this(filename, resource, bpmnProcessId, tasks, java.util.List.of());

  }

}
