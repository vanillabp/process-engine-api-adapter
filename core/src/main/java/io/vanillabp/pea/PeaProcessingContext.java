package io.vanillabp.pea;

import java.util.ArrayList;
import java.util.List;

import dev.bpmcrafters.processengineapi.task.TaskSubscription;

/**
 * The adapter's processing context ({@code PC} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}).
 * <p>
 * One instance is accumulated across all BPMN files of a single workflow module while
 * the deployment pipeline runs
 * ({@code readBpmn -> prepareBpmn -> wireBpmn -> deployResources ->
 * startWorkflowProcessing}). It collects everything the adapter needs to deploy the
 * module's resources to the Process-Engine-API in one go.
 * <p>
 * Skeleton stage: the context only remembers the workflow module id and the models
 * seen so far. Real wiring/deployment state is added by later feature stories.
 */
public class PeaProcessingContext {

  private final String workflowModuleId;

  private final List<PeaBpmnModel> models = new ArrayList<>();

  public PeaProcessingContext(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  public List<PeaBpmnModel> getModels() {

    return models;

  }

  private final java.util.Map<String, byte[]> decisions = new java.util.LinkedHashMap<>();

  /**
   * The decision tables of the workflow module, keyed by filename - deployed with its
   * processes in the same bundle. The API takes opaque resources, so nothing here has to
   * understand a decision.
   *
   * @return The files, in the order they were read
   */
  public java.util.Map<String, byte[]> getDecisions() {

    return decisions;

  }

  /**
   * Remembers a decision table for deployment.
   *
   * @param filename The DMN file name - it keeps its extension, which is all the engine
   *          behind the API has to tell a decision from a process
   * @param dmn The file
   */
  public void addDecision(
      final String filename,
      final byte[] dmn) {

    decisions.putIfAbsent(filename, dmn);

  }

  /**
   * The task subscriptions opened by startWorkflowProcessing, closed by
   * stopWorkflowProcessing (reverse order).
   */
  private final List<TaskSubscription> subscriptions = new ArrayList<>();

  public List<TaskSubscription> getSubscriptions() {

    return subscriptions;

  }

}
