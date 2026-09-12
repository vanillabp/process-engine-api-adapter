package io.vanillabp.pea.deployment;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class PeaDeployedProcesses {

  /**
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
  }

  private final Map<String, DeployedProcess> byProcess = new ConcurrentHashMap<>();

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

  public void record(
      final String workflowModuleId,
      final PeaBpmnModel model,
      final String deploymentKey) {

    byProcess.put(
        definitionId(workflowModuleId, model.bpmnProcessId()),
        new DeployedProcess(workflowModuleId, model, deploymentKey));

  }

  /**
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
   * Everything this boot has handed to the engine so far, over every workflow module.
   * <p>
   * There is one engine behind this API and it keeps nothing apart (see {@code GAPS.md},
   * entry 15), so this is also the list of BPMN process ids which are taken in it. The
   * collision check of the next workflow module reads it for that reason.
   *
   * @return The processes deployed up to now, in no particular order
   */
  public Collection<DeployedProcess> deployedSoFar() {

    return byProcess.values();

  }

  /**
   * @param processDefinitionId The adapter-native definition id (see
   *        {@link #definitionId(String, String)})
   * @return The deployed process or <code>null</code>
   */
  public DeployedProcess byDefinitionId(
      final String processDefinitionId) {

    return byProcess.get(processDefinitionId);

  }

}
