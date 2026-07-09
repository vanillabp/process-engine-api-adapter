package io.vanillabp.pea;

import java.util.ArrayList;
import java.util.List;

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

}
