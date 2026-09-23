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
 * What this application version deployed to the Process-Engine-API - one instance
 * per configured adapter id, filled by
 * {@code PeaDeploymentService#deployResources} at every boot.
 * <p>
 * <b>Why:</b> the Process-Engine-API has no repository/query API at all - neither
 * process definitions nor their BPMN XML can be read back from the engine (see
 * {@code GAPS.md}). The only source of both is what VanillaBP's deployment pipeline
 * read at boot, so the adapter keeps it. Consequence: a workflow still running on a
 * definition deployed by a PREVIOUS application version is served with the
 * currently deployed model - the Process-Engine-API offers nothing better.
 *
 * <h2>What can be asked</h2>
 *
 * A process is found by the workflow module and the BPMN process id, and its user tasks
 * are found by the BPMN element id a modeller wrote and by the external form reference
 * the adapter subscribes them under. All three are read out of the same models: this is
 * an index over what is already here, not a second store, so it can never disagree with
 * what was deployed.
 * <p>
 * Every identifier here is the PLAIN one the application wrote, never the scoped one the
 * deployed bytes carry (see decision 2 in the repository's DECISIONS.md), which is what
 * the identifiers of a delivery are translated back to as well.
 *
 * <h2>When the answers are complete</h2>
 *
 * A workflow module's processes are in here from the moment this adapter deployed that
 * module. VanillaBP deploys EVERY module before it starts workflow processing for any of
 * them, so a caller at runtime - a task delivery, an observer, the viewer API - sees all
 * of them. A caller running while the deployment pipeline is still walking the modules,
 * an extension wiring its own half among them, sees the modules deployed so far and not
 * the ones behind it.
 */
public class PeaDeployedProcesses {

  /**
   * Starts out empty. One instance belongs to one configured adapter id, and the
   * deployment service fills it at every boot.
   */
  public PeaDeployedProcesses() {

  }

  /**
   * One deployed process: the model as it was read, plus what the engine answered when the
   * bundle carrying it was deployed.
   *
   * @param workflowModuleId The workflow module the process belongs to
   * @param model The BPMN model as read and deployed
   * @param deploymentKey The Process-Engine-API deployment key (the only version
   *        information the API offers - one key per deployed BUNDLE, not per
   *        process)
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
     * The user task with the given BPMN element id. An element id is unique within a BPMN
     * file, so this answers one task or none.
     *
     * @param bpmnTaskId The BPMN element id of the user task
     * @return The user task, or <code>null</code> where this process has no such user
     *         task or the task has no external form reference and was therefore never
     *         read
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
     * The user tasks carrying the given external form reference. Two user tasks of one
     * process may share a form reference, so this answers a collection and the caller
     * decides what to do with more than one.
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
   * The processes of one workflow module which own a user task of that element id, and
   * the same by external form reference. Both are rebuilt from {@link #byProcess} on
   * every {@link #record}, so a redeployment cannot leave an entry of a user task the
   * model does not carry any more.
   */
  private volatile Map<String, List<DeployedProcess>> userTasksByElementId = Map.of();

  private volatile Map<String, List<DeployedProcess>> userTasksByFormReference = Map.of();

  /**
   * The adapter-native process definition id: the Process-Engine-API has no
   * process definition ids, so the adapter composes a stable one from what it
   * knows (see {@code GAPS.md}).
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
   * Remembers one deployed process and rebuilds the two user-task indexes from what is
   * held now. Synchronized because those indexes are replaced as a whole: two modules
   * deploying at once would otherwise each index the state they read.
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
   * The BPMN process ids a workflow module DECLARES without deploying a model under them -
   * the old id of a renamed process. Filled while the module starts processing, from what
   * the core answers about the application's own methods.
   */
  private final Set<String> declaredWithoutDeployment = ConcurrentHashMap.newKeySet();

  /**
   * Remembers that a workflow module serves a BPMN process id it deployed nothing under.
   * Nothing of the model behind that id is known here, which is exactly what makes the
   * memory worth keeping: a caller asking about such a workflow gets an empty answer, and
   * this is what tells an empty answer about a renamed process apart from an empty answer
   * about an id nobody ever used.
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
   * version deployed, which is all the Process-Engine-API allows: a workflow still running
   * on an older definition cannot be told apart from a new one here.
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
   * The same answer for a caller which already holds a definition id, which is what the
   * viewer API hands back and forth.
   *
   * @param processDefinitionId The adapter-native definition id (see
   *        {@link #definitionId(String, String)})
   * @return The deployed process or <code>null</code>
   */
  public DeployedProcess byDefinitionId(
      final String processDefinitionId) {

    return byProcess.get(processDefinitionId);

  }

  /**
   * The deployed processes of one workflow module owning a user task with the given BPMN
   * element id. An element id is unique within a BPMN file but nothing keeps two
   * processes of one module from using the same one, so the answer is a collection.
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
   * The deployed processes of one workflow module owning a user task with the given
   * external form reference - the key this adapter subscribes a user task under.
   * <p>
   * A form reference is NOT unique inside a workflow module: two processes may show the
   * same form, and under the name-clash avoidance mode {@code none} nothing separates
   * them at the engine either. So the answer is a collection and the caller decides. The
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
   * Builds both user-task indexes from scratch. Called for every recorded process, which
   * happens once per process while a workflow module is deployed: a handful of models,
   * walked once, in exchange for two lookups which cannot drift away from what was
   * deployed.
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
   * The index as a caller may hold it: nothing of it changes when the next process is
   * recorded, because the next {@link #reindex()} builds maps of its own.
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
   * nor a form reference carries a blank, so the two halves can never be read as another
   * pair.
   */
  private static String key(
      final String workflowModuleId,
      final String userTaskKey) {

    return workflowModuleId
        + " "
        + userTaskKey;

  }

}
