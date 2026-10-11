package io.vanillabp.pea;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.bpmcrafters.processengineapi.task.TaskSubscription;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;

/**
 * The processing context of this adapter. It is the {@code PC} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}.
 * <p>
 * There is one instance per workflow module. It grows over all BPMN files of the module
 * while the deployment pipeline runs ({@code readBpmn -> prepareBpmn -> wireBpmn ->
 * deployResources -> startWorkflowProcessing}). It collects everything the adapter needs
 * to deploy the resources of the module to the Process-Engine-API in one call.
 * <p>
 * Everything in here is collected while the module deploys, and it is read once. The
 * models and the decision tables become the bundle for the {@code DeploymentApi}. The
 * declared identifiers go to the core, so it can warn when two modules scope a name to the
 * same string. The subscriptions are what {@code stopWorkflowProcessing} closes again.
 */
public class PeaProcessingContext {

  private final String workflowModuleId;

  private final List<PeaBpmnModel> models = new ArrayList<>();

  /**
   * Starts an empty context for one workflow module.
   *
   * @param workflowModuleId The workflow module this context collects for. The adapter
   *          deploys one module in one call
   */
  public PeaProcessingContext(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * Which module this context is about. Every identifier below is scoped with it on its way
   * to the engine, because this BPMS has no namespace matching a workflow module.
   *
   * @return The workflow module this context belongs to
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The processes read so far, in the order the pipeline handed the files over. That order
   * is the one they are deployed in, and the one the registry of deployed processes keeps.
   *
   * @return The models of this workflow module
   */
  public List<PeaBpmnModel> getModels() {

    return models;

  }

  private final java.util.Map<String, byte[]> decisions = new java.util.LinkedHashMap<>();

  /**
   * The decision tables of the workflow module, by file name. They are deployed in the same
   * bundle as the processes. The API takes the files as they are, so nothing here has to
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
   * @param filename The DMN file name. It keeps its extension, because that is all the
   *          engine behind the API has to tell a decision from a process
   * @param dmn The file
   */
  public void addDecision(
      final String filename,
      final byte[] dmn) {

    decisions.putIfAbsent(filename, dmn);

  }

  private final Set<ModelIdentifier> declaredIdentifiers = new LinkedHashSet<>();

  /**
   * The identifiers the models of the module declare, with the plain names the application
   * gave them. They go to the core after the deployment. The core then warns when another
   * workflow module declares a name which reaches the engine in the same form.
   * <p>
   * They are read while the module deploys and never again. No delivery and no
   * <code>ProcessService</code> call looks at them.
   * <p>
   * This is a set, because the names a workflow module scopes on its own sit in the
   * <code>definitions</code> element of a file. A file with two processes declares each of
   * them once for both, and the core wants them without duplicates.
   *
   * @return What the models read so far declare
   */
  public Set<ModelIdentifier> getDeclaredIdentifiers() {

    return declaredIdentifiers;

  }

  private final List<TaskSubscription> subscriptions = new ArrayList<>();

  /**
   * The subscriptions opened when the module started processing. They are closed in
   * reverse order when it stops, so the order matters here.
   *
   * @return The open subscriptions, in the order they were opened
   */
  public List<TaskSubscription> getSubscriptions() {

    return subscriptions;

  }

}
