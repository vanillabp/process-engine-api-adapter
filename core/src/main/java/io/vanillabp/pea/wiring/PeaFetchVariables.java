package io.vanillabp.pea.wiring;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which payload variables a task subscription of this adapter asks the engine for.
 *
 * <p>
 * A {@code SubscribeForTaskCmd} carries a set of payload variables, and an EMPTY set
 * means "hand me everything the process instance holds". Subscribing that way makes
 * every delivery carry a copy of data the handler already has in the workflow aggregate,
 * plus whatever else the model accumulated, so this adapter names the set instead.
 * </p>
 *
 * <p>
 * VanillaBP can name the set instead, because the workflow aggregate is the source of
 * truth and the delivery only has to carry what the adapter and the handler read:
 * </p>
 * <ul>
 * <li>the <strong>workflow aggregate's ID</strong>, in the variable named after the
 * aggregate's ID attribute ({@code WorkflowTaskInvoker#resolveWorkflowAggregateIdName}).
 * The handler is loaded by it, and the name belongs to the BPMN process rather than to
 * the subscription, so a subscription serving two processes which disagree carries both
 * names;</li>
 * <li>every variable a {@code @TaskParam} of the served tasks reads
 * ({@code WorkflowTaskInvoker#taskParameterNames}). Those names live on the handler
 * methods and the core reads them off the annotations while the application wires itself
 * - the adapter itself never sees a BPMN model here (see {@code GAPS.md} 1), so this is
 * the only place they could come from.</li>
 * </ul>
 *
 * <p>
 * <strong>The set belongs to the SUBSCRIPTION, not to the delivery.</strong> One
 * subscription serves a task definition across the BPMN processes of a workflow module,
 * so its set is the union over everything it serves. It is sorted, which keeps it the
 * same across restarts of one application version.
 * </p>
 *
 * <p>
 * <strong>The escape hatch</strong> is
 * {@code vanillabp.adapters.<id>.fetch-variables: all}, the same key and the same two
 * values as on Camunda 8, resolvable down to task level. What it is for is a name no
 * annotation carries - a value read through a path the scanner cannot see. A statically
 * named {@code @TaskParam} needs none of it.
 * </p>
 */
public final class PeaFetchVariables {

  private PeaFetchVariables() {
  }

  /**
   * What {@code vanillabp.adapters.<id>.fetch-variables} may say.
   */
  public enum Mode {
    /**
     * Ask for the variables the adapter derived - the default, and what keeps a delivery
     * at what VanillaBP actually reads.
     */
    DERIVED,
    /**
     * Ask for the complete payload of the process instance, which is what a
     * Process-Engine-API subscription does when it names nothing.
     */
    ALL
  }

  /**
   * What one subscription asks for: either the complete payload, or the names below.
   *
   * @param all Whether the subscription asks for every variable of the process instance
   * @param names The variable names to ask for, sorted; empty while {@link #all} is
   *          <code>true</code>
   */
  public record Selection(boolean all,
                          List<String> names) {

    /**
     * @return A selection asking for the complete payload
     */
    public static Selection everything() {

      return new Selection(true, List.of());

    }

    /**
     * @param names The variable names, in any order
     * @return A selection asking for those names, sorted so it is stable across restarts
     */
    public static Selection of(
        final Collection<String> names) {

      return new Selection(false, List.copyOf(new TreeSet<>(names)));

    }

    /**
     * @return What the {@code SubscribeForTaskCmd} carries - an empty set is the API's
     *         way of asking for everything
     */
    public Set<String> payloadDescription() {

      return all
          ? Set.of()
          : Set.copyOf(names);

    }

    /**
     * @param name A variable name
     * @return Whether a delivery of this subscription carries that variable
     */
    public boolean covers(
        final String name) {

      return all || names.contains(name);

    }

    /**
     * @return What the startup line and the guiding messages call this selection
     */
    public String describe() {

      return all
          ? "all payload variables of the process instance"
          : names.toString();

    }

  }

  /**
   * The property key of the escape hatch, at the level a reader has to change it.
   *
   * @param adapterId The adapter id
   * @return The full property key
   */
  public static String propertyKey(
      final String adapterId) {

    return "vanillabp.adapters.%s.fetch-variables".formatted(adapterId);

  }

  /**
   * What a delivery says when the variable holding the workflow aggregate's ID is not
   * there. Two causes lead here: a workflow started past VanillaBP, and a subscription
   * whose named set does not carry the name. The message therefore names the set too.
   *
   * @param what What kind of task it is, capitalized ("Task", "User task")
   * @param taskId The engine's task id
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateIdName The variable the aggregate's ID was expected in
   * @param adapterId The adapter id, for the property key
   * @param selection What this subscription asks for
   * @return The message
   */
  public static String missingAggregateId(
      final String what,
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final String aggregateIdName,
      final String adapterId,
      final Selection selection) {

    return """
        %s '%s' (definition '%s') of BPMN process '%s' carries no payload variable '%s' holding \
        the workflow aggregate's ID! Either the workflow was not started through VanillaBP (the \
        variable is written on start), or its subscription did not ask for that variable: it \
        asks for %s. Set '%s' to 'all' to have this subscription ask for the complete payload."""
        .formatted(
            what,
            taskId,
            taskDefinition,
            bpmnProcessId,
            aggregateIdName,
            selection.describe(),
            propertyKey(adapterId));

  }

  /**
   * What a delivery says when a <code>&#64;TaskParam</code> names a variable this
   * subscription did not ask for. The adapter cannot tell that case apart from a variable
   * which is genuinely absent, and handing the method a <code>null</code> would be a
   * silent loss of what the engine computed - so the delivery fails and the task is
   * failed, which leaves the retry semantics to the engine behind the API.
   * <p>
   * A subscription asks for every name a <code>&#64;TaskParam</code> of the tasks it
   * serves DECLARES, so getting here means the name was not declared on the method: it
   * was assembled at runtime, or read past the annotation.
   *
   * @param name The variable the method asked for
   * @param taskDefinition The task definition, as the core knows it
   * @param adapterId The adapter id, for the property key
   * @param selection What this subscription asks for
   * @return The message
   */
  public static String unfetchedTaskParameter(
      final String name,
      final String taskDefinition,
      final String adapterId,
      final Selection selection) {

    return """
        The @WorkflowTask method serving '%s' reads the payload variable '%s', but its \
        subscription does not ask for that variable: it asks for %s. A subscription asks for \
        every name a @TaskParam of its tasks declares, so this name reached the delivery some \
        other way - through a value computed at runtime rather than through @TaskParam("%s"). \
        Either declare it that way, or read the value from the workflow aggregate, which is what \
        VanillaBP is about, or set '%s' to 'all' - at task level for this one task, or at \
        workflow, workflow-module or adapter level."""
        .formatted(taskDefinition, name, selection.describe(), name, propertyKey(adapterId));

  }

}
