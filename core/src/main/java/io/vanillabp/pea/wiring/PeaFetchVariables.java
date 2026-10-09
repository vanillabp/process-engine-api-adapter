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
 * <li>every variable a {@code @TaskParam} of an extension's method reads for the served tasks
 * ({@code WorkflowTaskWiring#extensionTaskParameterNames}). The engine hands a task to this
 * one subscription, so an extension reads what it delivered and has no subscription of its
 * own to ask with.</li>
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
 * <strong>No key asks for more.</strong> A name no annotation carries, read through a path
 * the scanner cannot see, fails the delivery with a message which says so. The handler reads
 * such a value from the workflow aggregate. The key <code>fetch-variables</code> asked for
 * the complete payload in snapshots of version 2.0 and was removed. A key still set ends the
 * start ({@link #rejectTheRemovedKey}).
 * </p>
 * <p>
 * Why the core reports the names instead of a scan of the model deriving them is decision 7 in the
 * repository's DECISIONS.md.
 */
public final class PeaFetchVariables {

  private PeaFetchVariables() {
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
     * Asks the engine for everything the process instance holds. More than the handlers
     * declared, which costs payload on every delivery and never a missing value. Only
     * handlers and tests built without a selection get it.
     *
     * @return A selection asking for the complete payload
     */
    public static Selection everything() {

      return new Selection(true, List.of());

    }

    /**
     * Asks for named variables only. The names are sorted, so two nodes of one application
     * open the same subscription and a restart does not look like a change.
     *
     * @param names The variable names, in any order
     * @return A selection asking for those names, sorted so it is stable across restarts
     */
    public static Selection of(
        final Collection<String> names) {

      return new Selection(false, List.copyOf(new TreeSet<>(names)));

    }

    /**
     * Turns the selection into what the subscription command takes. Note the API's
     * convention: an empty set means everything rather than nothing.
     *
     * @return What the {@code SubscribeForTaskCmd} carries - an empty set is the API's
     *         way of asking for everything
     */
    public Set<String> payloadDescription() {

      return all
          ? Set.of()
          : Set.copyOf(names);

    }

    /**
     * Whether a delivery of this subscription carries that variable. Asked whenever a
     * handler reads a task parameter: a name the subscription left out would arrive as
     * null and read like an empty value, so the handler refuses with a guiding message
     * instead of running on nothing.
     *
     * @param name A variable name
     * @return Whether a delivery of this subscription carries that variable
     */
    public boolean covers(
        final String name) {

      return all || names.contains(name);

    }

    /**
     * The selection in words, so the startup line names what this subscription asks for
     * instead of leaving somebody to work it out from the configuration.
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
   * The last part of the removed key, at every level it could be set at.
   */
  public static final String REMOVED_KEY = "fetch-variables";

  /**
   * Ends the start where somebody still sets the removed key <code>fetch-variables</code>.
   * Ignoring it would be silent: a value of <code>all</code> was set for a handler which
   * reads a variable nobody declared, and that handler would now fail its delivery long
   * after the start. The message names every key it found and says what applies instead.
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
   * What a delivery says when the variable holding the workflow aggregate's ID is not
   * there. A subscription always asks for that variable, so the cause is the process
   * instance: a workflow started past VanillaBP, or a model which removed the variable. The
   * message still names the set, because that is the first thing a reader checks.
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
