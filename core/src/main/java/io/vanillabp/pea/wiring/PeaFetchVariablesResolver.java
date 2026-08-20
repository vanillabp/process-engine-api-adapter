package io.vanillabp.pea.wiring;

/**
 * Resolves whether a task subscription asks the engine for the DERIVED payload variables
 * or for all of them - implemented by the platform modules on top of the adapter's
 * configuration overlay with most-specific-wins semantics across the four levels (task
 * &gt; workflow &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.fetch-variables
 * </pre>
 *
 * The key and its two values are the ones Camunda 8 uses, deliberately: an application
 * moving between the two BPMS must not have to learn a second name for the same question.
 * <p>
 * A subscription serves several tasks, and the two values do not average. Where any of
 * them says <code>all</code>, the subscription asks for everything - asking for more than
 * derived is never wrong, only more expensive.
 */
@FunctionalInterface
public interface PeaFetchVariablesResolver {

  /**
   * What applies where no level configures anything: the derived set.
   */
  PeaFetchVariables.Mode DEFAULT_FETCH_VARIABLES = PeaFetchVariables.Mode.DERIVED;

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition
   * @return The most specific configured mode or {@link #DEFAULT_FETCH_VARIABLES}
   */
  PeaFetchVariables.Mode fetchVariablesFor(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition);

  /**
   * Asks a resolver which may not be there - the deployment service is built without one
   * in tests, and a resolver is free to answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition
   * @return The resolved mode, never <code>null</code>
   */
  static PeaFetchVariables.Mode resolve(
      final PeaFetchVariablesResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (resolver == null) {
      return DEFAULT_FETCH_VARIABLES;
    }
    final var resolved = resolver.fetchVariablesFor(workflowModuleId, bpmnProcessId, taskDefinition);
    return resolved == null
        ? DEFAULT_FETCH_VARIABLES
        : resolved;

  }

}
