package io.vanillabp.pea.deployment;

import java.util.List;
import java.util.function.Predicate;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The user tasks of one BPMN process which reach no <code>&#64;WorkflowTask</code> method, and
 * the sentences a boot says about them.
 * <p>
 * Nothing here is a defect. The engine creates the user task, it stands in a task list and
 * whoever finishes it moves the workflow on, which is why the core hands such a task over as an
 * OPTIONAL spec. What the application loses is the notification, and it used to lose it without a
 * word anybody reads. See the decision about a user task nothing serves in the repository's
 * DECISIONS.md.
 * <p>
 * Two things can be missing, and they are named apart because the way out differs. A user task
 * whose external form reference no method names is delivered to this adapter and dropped again. A
 * user task naming no external form reference is never even subscribed for, so the engine never
 * offers it here.
 */
public final class PeaUnservedUserTasks {

  private PeaUnservedUserTasks() {
  }

  /**
   * The user tasks of one process whose external form reference no method names.
   * <p>
   * Asked with BOTH keys a task is wired by, the way the delivery asks at runtime
   * ({@link io.vanillabp.pea.wiring.PeaUserTaskHandler}): a method names the task definition,
   * which is the external form reference here, or the element id.
   *
   * @param userTasks The user tasks of the process which name an external form reference
   * @param aMethodNames Whether a <code>&#64;WorkflowTask</code> method of the application names
   *          the given task definition or element id
   * @return The ones nothing serves, in the order the model carries them
   */
  public static List<BpmnTaskSpec> unserved(
      final List<BpmnTaskSpec> userTasks,
      final Predicate<String> aMethodNames) {

    return userTasks
        .stream()
        .filter(userTask -> !aMethodNames.test(userTask.taskDefinition()) && !aMethodNames
            .test(userTask.activityId()))
        .toList();

  }

  /**
   * The report about one process.
   *
   * @param unserved The user tasks whose external form reference no method names
   * @param withoutAFormReference The user tasks naming no external form reference
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param workflowModuleId The workflow module
   * @return The message, or <code>null</code> where there is nothing to say
   */
  public static String report(
      final List<BpmnTaskSpec> unserved,
      final List<BpmnTaskSpec> withoutAFormReference,
      final String bpmnProcessId,
      final String workflowModuleId) {

    if (unserved.isEmpty() && withoutAFormReference.isEmpty()) {
      return null;
    }
    final var message = new StringBuilder(
        """
            BPMN process '%s' of workflow module '%s' has %d user task(s) which reach no \
            @WorkflowTask method. The engine creates such a task and whoever works a task list \
            finishes it, so the workflow runs as modelled. What your application does not get is \
            the notification: no method of it is called when the task is created. Where you want \
            one, add it to the class which claims this process:"""
            .formatted(bpmnProcessId, workflowModuleId, unserved.size() + withoutAFormReference.size()));
    unserved.forEach(userTask -> message.append(aReferenceNoMethodNames(userTask)));
    withoutAFormReference.forEach(userTask -> message.append(noReferenceAtAll(userTask)));
    message
        .append(
            """

                Where a task list is all these tasks need, this line is the whole story and there is \
                nothing to do about it. VanillaBP completes such a task through \
                ProcessService#completeUserTask whether a method is notified about it or not.""");
    return message.toString();

  }

  /**
   * One user task whose external form reference no method names. This adapter subscribes for the
   * reference whatever the application serves, so the engine does deliver the task and the
   * delivery is dropped here.
   *
   * @param userTask The user task
   * @return Its line, starting with a line break
   */
  private static String aReferenceNoMethodNames(
      final BpmnTaskSpec userTask) {

    return """

          - %s, whose external form reference is '%s': add a method annotated with \
        @WorkflowTask(taskDefinition = "%s") or @WorkflowTask(id = "%s"). The engine offers this \
        task to the adapter and the delivery is dropped again, so your observers see it and your \
        application does not."""
        .formatted(
            described(userTask),
            userTask.taskDefinition(),
            userTask.taskDefinition(),
            userTask.activityId());

  }

  /**
   * One user task naming no external form reference. There is no name to subscribe under, so
   * nothing of VanillaBP ever hears about the task, and a method alone would not change that.
   *
   * @param userTask The user task
   * @return Its line, starting with a line break
   */
  private static String noReferenceAtAll(
      final BpmnTaskSpec userTask) {

    return """

          - %s, which names no external form reference: this adapter subscribes for user tasks by \
        that reference, so the engine never offers this task here and no method can be wired to it. \
        Set 'External form reference' (zeebe:formDefinition externalReference) on the element to \
        the task definition your method names, and add the method."""
        .formatted(described(userTask));

  }

  /**
   * @param userTask The user task
   * @return What to look for in the model, with the name the modeller wrote on it where there is
   *         one
   */
  private static String described(
      final BpmnTaskSpec userTask) {

    return userTask.name() == null
        ? "user task '%s'".formatted(userTask.activityId())
        : "user task '%s' (named '%s' in the model)".formatted(userTask.activityId(), userTask.name());

  }

}
