package io.vanillabp.pea;

import java.util.List;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The BPMN model of this adapter. It is the {@code BPMN} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}.
 * <p>
 * The bpm-crafters Process-Engine-API has no BPMN model on purpose. Its {@code DeploymentApi}
 * takes a {@code NamedResource}, which is a file name and the bytes, and nothing more. But
 * VanillaBP wires the business code by BPMN process id, so the adapter needs at least that
 * id next to the raw bytes. This record carries it.
 * <p>
 * {@code PeaDeploymentService.readBpmn} reads the {@code bpmnProcessId} out of the BPMN XML
 * with the StAX parser of the JDK. An API which is the same for every BPMS and has no model
 * type makes every user parse BPMN on their own, see the first entry of {@code GAPS.md}. The
 * same pass also reads the names of the process and of its user tasks. So nobody who needs
 * them has to read the bytes a second time.
 *
 * @param filename The name of the BPMN resource (used for logging and deployment)
 * @param resource The raw BPMN XML bytes
 * @param bpmnProcessId The id of the executable process contained in the resource
 * @param processName The <code>name</code> attribute of the process element, what a
 *          modeller wrote on it. May be <code>null</code>: a process needs no name
 * @param tasks The service-like tasks of the process which are wired to
 *          {@code @WorkflowTask} methods. Each has its activity id and its task definition,
 *          which is the type of <code>zeebe:taskDefinition</code>. The Process-Engine-API
 *          itself knows no task definitions in BPMN, see {@code GAPS.md}
 * @param userTasks The user tasks of the process, each with its element id, its external
 *          form reference and the name the modeller wrote on it
 * @param userTasksWithoutAFormReference The user tasks which name no external form
 *          reference, each with its element id and its name. Their task definition is
 *          always <code>null</code>, because the model names none. Such a task cannot be
 *          subscribed for and never becomes a spec of the core. It is carried so the boot
 *          can name it to whoever drew it. It is read in the same pass as the rest, so
 *          nobody reads the same bytes a second time (decision 10 in the repository's
 *          DECISIONS.md)
 */
public record PeaBpmnModel(
                           String filename,
                           byte[] resource,
                           String bpmnProcessId,
                           String processName,
                           List<BpmnTaskSpec> tasks,
                           List<BpmnTaskSpec> userTasks,
                           List<BpmnTaskSpec> userTasksWithoutAFormReference) {

  /**
   * A shorter constructor, for a process without a name and without user tasks.
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

    this(filename, resource, bpmnProcessId, null, tasks, List.of(), List.of());

  }

  /**
   * A shorter constructor, for a process without a name.
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

    this(filename, resource, bpmnProcessId, null, tasks, userTasks, List.of());

  }

  /**
   * A shorter constructor, for a process whose user tasks all name an external form
   * reference. VanillaBP asks for such a model.
   *
   * @param filename The name of the BPMN resource
   * @param resource The raw BPMN XML bytes
   * @param bpmnProcessId The id of the executable process
   * @param processName The <code>name</code> attribute of the process element
   * @param tasks The service-like tasks of the process
   * @param userTasks The user tasks of the process
   */
  public PeaBpmnModel(
      final String filename,
      final byte[] resource,
      final String bpmnProcessId,
      final String processName,
      final List<BpmnTaskSpec> tasks,
      final List<BpmnTaskSpec> userTasks) {

    this(filename, resource, bpmnProcessId, processName, tasks, userTasks, List.of());

  }

}
