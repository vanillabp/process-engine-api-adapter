package io.vanillabp.pea.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.pea.PeaBpmnModel;

/**
 * What this application version deployed to the Process-Engine-API. There is one instance
 * per configured adapter id. {@code PeaDeploymentService#deployResources} fills it at every
 * boot.
 * <p>
 * <b>Why:</b> the Process-Engine-API has no repository or query API at all. Neither the
 * process definitions nor their BPMN XML can be read back from the engine (see
 * {@code GAPS.md}). The only source of both is what the deployment pipeline of VanillaBP
 * read at boot, so the adapter keeps it. As a result, a workflow which still runs on a
 * definition of an OLDER application version is served with the model deployed now. The
 * Process-Engine-API offers nothing better.
 *
 * <h2>What can be asked</h2>
 *
 * A process is found by its workflow module and its BPMN process id. Its user tasks are
 * found by the BPMN element id a modeller wrote, and by the external form reference the
 * adapter subscribes them under. All three come from the same models. This is an index over
 * what is already here, not a second store, so it can never disagree with what was
 * deployed.
 * <p>
 * Every identifier here is the PLAIN one the application wrote, never the scoped one in the
 * deployed bytes (see decision 2 in the repository's DECISIONS.md). The identifiers of a
 * delivery are translated back to the plain form as well.
 *
 * <h2>When the answers are complete</h2>
 *
 * The processes of a workflow module are in here from the moment this adapter deployed the
 * module. VanillaBP deploys EVERY module before it starts workflow processing for any of
 * them. So a caller at runtime sees all of them: a task delivery, an observer or the viewer
 * API. A caller which runs while the deployment pipeline still walks the modules sees only
 * the modules deployed so far. An extension which wires its own part among them is such a
 * caller.
 */
public class PeaDeployedProcesses {

  /**
   * Starts out empty. One instance belongs to one configured adapter id, and the
   * deployment service fills it at every boot.
   */
  public PeaDeployedProcesses() {

  }

  /**
   * One deployed process: the model as it was read, and what the engine answered when the
   * bundle with it was deployed.
   *
   * @param workflowModuleId The workflow module the process belongs to
   * @param model The BPMN model as read and deployed
   * @param deploymentKey The deployment key of the Process-Engine-API. It is the only
   *        version information the API offers, and there is one key per deployed BUNDLE,
   *        not per process
   */
  public record DeployedProcess(
                                String workflowModuleId,
                                PeaBpmnModel model,
                                String deploymentKey) {

    /**
     * The process id the application wrote, not the scoped one the deployed bytes carry
     * (see decision 2 in the repository's DECISIONS.md).
     *
     * @return The plain BPMN process id
     */
    public String bpmnProcessId() {

      return model.bpmnProcessId();

    }

    /**
     * What a viewer shows instead of the process id, where the model offers one.
     *
     * @return The name the modeller wrote on the process, or <code>null</code> where the
     *         model carries none
     */
    public String processName() {

      return model.processName();

    }

    /**
     * The user task with the given BPMN element id. An element id is unique in a BPMN file,
     * so the answer is one task or none.
     *
     * @param bpmnTaskId The BPMN element id of the user task
     * @return The user task, or <code>null</code> if this process has no such user task
     *         or the task names no external form reference. The model keeps those in
     *         {@code userTasksWithoutAFormReference}, because nothing is ever delivered for
     *         them, and whoever asks here asks about a delivery
     */
    public BpmnTaskSpec userTaskByElementId(
        final String bpmnTaskId) {

      return model
          .userTasks()
          .stream()
          .filter(userTask -> userTask.activityId().equals(bpmnTaskId))
          .findFirst()
          .orElse(null);

    }

    /**
     * The user tasks with the given external form reference. Two user tasks of one process
     * may share a form reference. So the answer is a collection, and the caller decides what
     * to do with more than one.
     *
     * @param formReference The plain external form reference
     * @return The user tasks, empty where none of them carries that reference
     */
    public List<BpmnTaskSpec> userTasksByFormReference(
        final String formReference) {

      return model
          .userTasks()
          .stream()
          .filter(userTask -> formReference.equals(userTask.taskDefinition()))
          .toList();

    }

  }

  private final Map<String, DeployedProcess> byProcess = new ConcurrentHashMap<>();

  /**
   * The processes of one workflow module which own a user task with that element id. The
   * second map does the same by external form reference. Both are built again from
   * {@link #byProcess} on every {@link #record}. So after a redeployment no entry is left
   * for a user task which the model no longer has.
   */
  private volatile Map<String, List<DeployedProcess>> userTasksByElementId = Map.of();

  private volatile Map<String, List<DeployedProcess>> userTasksByFormReference = Map.of();

  /**
   * The process definition id of this adapter. The Process-Engine-API has no process
   * definition ids, so the adapter builds a stable one from what it knows (see
   * {@code GAPS.md}).
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @return The adapter-native definition id
   */
  public static String definitionId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return workflowModuleId
        + "|"
        + bpmnProcessId;

  }

  /**
   * Remembers one deployed process and builds the two user-task indexes again from what is
   * held now. It is synchronized because the indexes are replaced as a whole. Without that,
   * two modules which deploy at the same time would each index only the state they read.
   *
   * @param workflowModuleId The workflow module the process belongs to
   * @param model The BPMN model as read and deployed
   * @param deploymentKey What the engine answered for the bundle the process travelled in
   */
  public synchronized void record(
      final String workflowModuleId,
      final PeaBpmnModel model,
      final String deploymentKey) {

    byProcess.put(
        definitionId(workflowModuleId, model.bpmnProcessId()),
        new DeployedProcess(workflowModuleId, model, deploymentKey));
    reindex();

  }

  /**
   * The BPMN process ids a workflow module DECLARES without deploying a model under them.
   * Such an id is the old id of a renamed process. The set is filled when the module starts
   * processing, from what the core says about the methods of the application.
   */
  private final Set<String> declaredWithoutDeployment = ConcurrentHashMap.newKeySet();

  /**
   * Remembers that a workflow module serves a BPMN process id it deployed nothing under.
   * Nothing of the model behind that id is known here, and that is why this is worth
   * keeping. A caller who asks about such a workflow gets an empty answer. This record tells
   * an empty answer about a renamed process apart from one about an id nobody ever used.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   */
  public void recordDeclaredWithoutDeployment(
      final String workflowModuleId,
      final String bpmnProcessId) {

    declaredWithoutDeployment.add(definitionId(workflowModuleId, bpmnProcessId));

  }

  /**
   * Whether an empty answer about this process means "renamed" rather than "never heard
   * of it".
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   * @return Whether the module declares that id without deploying a model under it
   */
  public boolean isDeclaredWithoutDeployment(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return declaredWithoutDeployment.contains(definitionId(workflowModuleId, bpmnProcessId));

  }

  /**
   * The model a workflow of this process is served with. It is the one THIS application
   * version deployed, because the Process-Engine-API allows nothing else. A workflow which
   * still runs on an older definition looks the same as a new one here.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @return The deployed process or <code>null</code>
   */
  public DeployedProcess deployedVersionOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return byProcess.get(definitionId(workflowModuleId, bpmnProcessId));

  }

  /**
   * The same answer, for a caller which already has a definition id. The viewer API passes
   * such ids back and forth.
   *
   * @param processDefinitionId The definition id of this adapter (see
   *        {@link #definitionId(String, String)})
   * @return The deployed process or <code>null</code>
   */
  public DeployedProcess byDefinitionId(
      final String processDefinitionId) {

    return byProcess.get(processDefinitionId);

  }

  /**
   * The deployed processes of one workflow module which own a user task with the given
   * BPMN element id. An element id is unique in a BPMN file. But two processes of one module
   * may use the same one, so the answer is a collection.
   * <p>
   * The order is the one the processes were deployed in.
   *
   * @param workflowModuleId The workflow module to look in
   * @param bpmnTaskId The BPMN element id of the user task
   * @return The processes owning such a user task, empty where none does
   */
  public List<DeployedProcess> byUserTaskElementId(
      final String workflowModuleId,
      final String bpmnTaskId) {

    return userTasksByElementId.getOrDefault(key(workflowModuleId, bpmnTaskId), List.of());

  }

  /**
   * The deployed processes of one workflow module which own a user task with the given
   * external form reference. This adapter subscribes a user task under that key.
   * <p>
   * A form reference is NOT unique in a workflow module. Two processes may show the same
   * form, and with the name-clash-avoidance mode {@code none} the engine does not keep them
   * apart either. So the answer is a collection, and the caller decides. The
   * order is the one the processes were deployed in.
   *
   * @param workflowModuleId The workflow module to look in
   * @param formReference The plain external form reference
   * @return The processes owning such a user task, empty where none does
   */
  public List<DeployedProcess> byUserTaskFormReference(
      final String workflowModuleId,
      final String formReference) {

    return userTasksByFormReference.getOrDefault(key(workflowModuleId, formReference), List.of());

  }

  /**
   * Builds both user-task indexes from scratch. This runs for every recorded process, so
   * once per process while a workflow module deploys. That means walking a few models once,
   * and in return the two lookups always match what was deployed.
   */
  private void reindex() {

    final var elementIds = new LinkedHashMap<String, List<DeployedProcess>>();
    final var formReferences = new LinkedHashMap<String, List<DeployedProcess>>();
    byProcess
        .values()
        .forEach(process -> process
            .model()
            .userTasks()
            .forEach(userTask -> {
              add(elementIds, key(process.workflowModuleId(), userTask.activityId()), process);
              if (userTask.taskDefinition() != null) {
                add(formReferences, key(process.workflowModuleId(), userTask.taskDefinition()), process);
              }
            }));
    userTasksByElementId = immutable(elementIds);
    userTasksByFormReference = immutable(formReferences);

  }

  /**
   * The index in a form a caller may keep. Nothing in it changes when the next process is
   * recorded, because the next {@link #reindex()} builds new maps.
   */
  private static Map<String, List<DeployedProcess>> immutable(
      final Map<String, List<DeployedProcess>> index) {

    final var copy = new LinkedHashMap<String, List<DeployedProcess>>();
    index.forEach((
        key,
        processes) -> copy.put(key, List.copyOf(processes)));
    return Map.copyOf(copy);

  }

  private static void add(
      final Map<String, List<DeployedProcess>> index,
      final String key,
      final DeployedProcess process) {

    final var processes = index.computeIfAbsent(key, ignored -> new ArrayList<>());
    if (!processes.contains(process)) {
      processes.add(process);
    }

  }

  /**
   * Joins a workflow module and one of the two user-task keys. Neither a BPMN element id
   * nor a form reference contains a blank. So the joined key can never be read as a
   * different pair.
   */
  private static String key(
      final String workflowModuleId,
      final String userTaskKey) {

    return workflowModuleId
        + " "
        + userTaskKey;

  }

}
