package io.vanillabp.pea;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.bpmcrafters.processengineapi.task.TaskSubscription;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;

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
 * Everything in here is collected while the module deploys and read once: the models and
 * the decision tables become the bundle handed to the {@code DeploymentApi}, the declared
 * identifiers go to the core so it can warn about a name two modules scope to the same
 * string, and the subscriptions are what {@code stopWorkflowProcessing} closes again.
 */
public class PeaProcessingContext {

  private final String workflowModuleId;

  private final List<PeaBpmnModel> models = new ArrayList<>();

  /**
   * Starts an empty context for one workflow module.
   *
   * @param workflowModuleId The workflow module this context collects, which is the unit
   *          the adapter deploys in one call
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

  private final Set<ModelIdentifier> declaredIdentifiers = new LinkedHashSet<>();

  /**
   * The identifiers the module's models declare, with the plain names the application gave
   * them - handed to the core after the deployment so it can warn where another workflow
   * module declares a name which reaches the engine in the same form.
   * <p>
   * Read while the module deploys and never again: no delivery and no
   * <code>ProcessService</code> call looks at them.
   * <p>
   * A set, because the names a workflow module scopes on its own sit in the
   * <code>definitions</code> element of a file: a file holding two processes declares each
   * of them once for both, and the core asks for them without duplicates.
   *
   * @return What the models read so far declare
   */
  public Set<ModelIdentifier> getDeclaredIdentifiers() {

    return declaredIdentifiers;

  }

  /**
   * The task subscriptions opened by startWorkflowProcessing, closed by
   * stopWorkflowProcessing (reverse order).
   */
  private final List<TaskSubscription> subscriptions = new ArrayList<>();

  /**
   * The subscriptions opened while the module started processing. They are closed in
   * reverse order when it stops, which is why the order matters here.
   *
   * @return The open subscriptions, in the order they were opened
   */
  public List<TaskSubscription> getSubscriptions() {

    return subscriptions;

  }

}
