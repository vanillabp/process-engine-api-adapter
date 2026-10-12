package io.vanillabp.pea.wiring;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which payload variables a task subscription of this adapter asks the engine for.
 *
 * <p>
 * A {@code SubscribeForTaskCmd} carries a set of payload variables. An EMPTY set means
 * "give me everything the process instance holds". Then every delivery carries a copy of
 * data the handler already has in the workflow aggregate, plus whatever else the model
 * collected. So this adapter names the variables it wants.
 * </p>
 *
 * <p>
 * VanillaBP can do that because the workflow aggregate holds the true state. The delivery
 * only has to carry what the adapter and the handler read:
 * </p>
 * <ul>
 * <li>the <strong>ID of the workflow aggregate</strong>, in the variable named after the ID
 * attribute of the aggregate ({@code WorkflowTaskInvoker#resolveWorkflowAggregateIdName}).
 * The aggregate is loaded by this ID. The name belongs to the BPMN process, not to the
 * subscription. So a subscription which serves two processes with different names asks for
 * both;</li>
 * <li>every variable a {@code @TaskParam} of the served tasks reads
 * ({@code WorkflowTaskInvoker#taskParameterNames}). These names are on the handler methods,
 * and the core reads them from the annotations while the application wires itself. The
 * adapter sees no BPMN model here (see {@code GAPS.md} 1), so the names can only come from
 * there.</li>
 * <li>every variable a {@code @TaskParam} of a method of an extension reads for the served
 * tasks ({@code WorkflowTaskWiring#extensionTaskParameterNames}). The engine hands a task
 * to this one subscription. An extension reads what that delivery carries, because it has
 * no subscription of its own.</li>
 * </ul>
 *
 * <p>
 * <strong>The set belongs to the SUBSCRIPTION, not to the delivery.</strong> One
 * subscription serves a task definition in all the BPMN processes of a workflow module. So
 * its set is the union over everything it serves. The set is sorted, so it stays the same
 * across restarts of one application version.
 * </p>
 *
 * <p>
 * <strong>No key asks for more.</strong> If a name is on no annotation and is read in a
 * way the scanner cannot see, the delivery fails with a message which says so. The handler
 * reads such a value from the workflow aggregate. In the snapshots of version 2.0, the key
 * <code>fetch-variables</code> asked for the complete payload. It was removed. If the key
 * is still set, the startup stops ({@link #rejectTheRemovedKey}).
 * </p>
 * <p>
 * Why the core reports the names, and the adapter does not scan the model for them, is
 * decision 7 in the repository's DECISIONS.md.
 */
public final class PeaFetchVariables {

  private PeaFetchVariables() {
  }

  /**
   * What one subscription asks for: the complete payload, or the names below.
   *
   * @param all Whether the subscription asks for every variable of the process instance
   * @param names The variable names to ask for, sorted; empty while {@link #all} is
   *          <code>true</code>
   */
  public record Selection(boolean all,
                          List<String> names) {

    /**
     * Asks the engine for everything the process instance holds. That is more than the
     * handlers declared. It makes every delivery larger, but no value is ever missing. Only
     * handlers and tests built without a selection get it.
     *
     * @return A selection asking for the complete payload
     */
    public static Selection everything() {

      return new Selection(true, List.of());

    }

    /**
     * Asks for named variables only. The names are sorted. So two nodes of one application
     * open the same subscription, and a restart does not look like a change.
     *
     * @param names The variable names, in any order
     * @return A selection asking for those names, sorted so it is stable across restarts
     */
    public static Selection of(
        final Collection<String> names) {

      return new Selection(false, List.copyOf(new TreeSet<>(names)));

    }

    /**
     * Turns the selection into what the subscription command takes. Note the rule of the
     * API: an empty set means everything, not nothing.
     *
     * @return What the {@code SubscribeForTaskCmd} carries. In the API, an empty set asks
     *         for everything
     */
    public Set<String> payloadDescription() {

      return all
          ? Set.of()
          : Set.copyOf(names);

    }

    /**
     * Whether a delivery of this subscription carries that variable. It is asked whenever
     * a handler reads a task parameter. A name the subscription left out would arrive as
     * null and look like an empty value. So the handler refuses with a message which says
     * what to do, and does not go on with nothing.
     *
     * @param name A variable name
     * @return Whether a delivery of this subscription carries that variable
     */
    public boolean covers(
        final String name) {

      return all || names.contains(name);

    }

    /**
     * The selection in words. So the startup line names what this subscription asks for,
     * and nobody has to work it out from the configuration.
     *
     * @return What the startup line and the guiding messages call this selection
     */
    public String describe() {

      return all
          ? "all payload variables of the process instance"
          : names.toString();

    }

  }

  /**
   * The last part of the removed key. It is the same at every level the key could be set
   * at.
   */
  public static final String REMOVED_KEY = "fetch-variables";

  /**
   * Stops the startup if somebody still sets the removed key <code>fetch-variables</code>.
   * Ignoring the key would hide a problem. The value <code>all</code> was set for a handler
   * which reads a variable nobody declared. That handler would now fail its delivery, long
   * after the startup. The message names every key it found and says what to do instead.
   *
   * @param adapterId The adapter id
   * @param keys The full keys which set it, at the adapter level and below; empty where
   *          nobody does
   * @throws IllegalStateException If any key sets it
   */
  public static void rejectTheRemovedKey(
      final String adapterId,
      final List<String> keys) {

    if ((keys == null) || keys.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Process-Engine-API adapter '%s' is configured with '%s', which does not exist any more:
              %s
            Remove the key. A subscription now always asks the engine for the variables \
            VanillaBP reads: the variable holding the workflow aggregate's ID and every name a \
            @TaskParam of the served tasks declares. A handler which needs more reads it from the \
            workflow aggregate."""
            .formatted(adapterId, REMOVED_KEY, String.join("\n  ", keys)));

  }

  /**
   * What a delivery says when the variable with the ID of the workflow aggregate is not
   * there. A subscription always asks for that variable, so the cause is in the process
   * instance. Either the workflow was started without VanillaBP, or the model removed the
   * variable. The message still names the set, because a reader checks that first.
   *
   * @param what What kind of task it is, capitalized ("Task", "User task")
   * @param taskId The engine's task id
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateIdName The variable the aggregate's ID was expected in
   * @param selection What this subscription asks for
   * @return The message
   */
  public static String missingAggregateId(
      final String what,
      final String taskId,
      final String taskDefinition,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Selection selection) {

    return """
        %s '%s' (definition '%s') of BPMN process '%s' carries no payload variable '%s' holding \
        the workflow aggregate's ID! Its subscription asks for %s. Either the workflow was not \
        started through VanillaBP (the variable is written on start), or something in the process \
        removed or overwrote that variable."""
        .formatted(
            what,
            taskId,
            taskDefinition,
            bpmnProcessId,
            aggregateIdName,
            selection.describe());

  }

  /**
   * What a delivery says when a <code>&#64;TaskParam</code> names a variable this
   * subscription did not ask for. The adapter cannot tell this case from a variable which
   * really is not there. Handing the method a <code>null</code> would silently lose what
   * the engine computed. So the delivery fails. For a service task the task is failed, and
   * the engine behind the API decides about retries. For a user task the handler logs the
   * error and the user task stays open; nothing is failed or retried.
   * <p>
   * A subscription asks for every name which a <code>&#64;TaskParam</code> of its tasks
   * DECLARES. So if this happens, the name was not declared on the method. It was built at
   * runtime, or read without the annotation.
   *
   * @param name The variable the method asked for
   * @param taskDefinition The task definition, as the core knows it
   * @param selection What this subscription asks for
   * @return The message
   */
  public static String unfetchedTaskParameter(
      final String name,
      final String taskDefinition,
      final Selection selection) {

    return """
        The @WorkflowTask method serving '%s' reads the payload variable '%s', but its \
        subscription does not ask for that variable: it asks for %s. A subscription asks for \
        every name a @TaskParam of its tasks declares, so this name reached the delivery some \
        other way - through a value computed at runtime rather than through @TaskParam("%s"). \
        Either declare it that way, or read the value from the workflow aggregate, which is what \
        VanillaBP is about."""
        .formatted(taskDefinition, name, selection.describe(), name);

  }

}
