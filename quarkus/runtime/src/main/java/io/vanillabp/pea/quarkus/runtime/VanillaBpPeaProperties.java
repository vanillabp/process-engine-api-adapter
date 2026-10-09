package io.vanillabp.pea.quarkus.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import io.vanillabp.pea.wiring.PeaFetchVariables;

/**
 * The Process-Engine-API adapter's OVERLAY of the shared <code>vanillabp.*</code>
 * configuration tree. The adapter has no connection settings of its own - the engine is
 * provided by the application as beans. The only key here is the removed
 * <code>fetch-variables</code>, at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.fetch-variables</code> plus the three scoped levels
 * below it. It is bound only so the start can refuse it, see
 * {@link PeaFetchVariables#rejectTheRemovedKey}.
 * <p>
 * Quarkus knows no blanket {@code withMappingIgnore} for the {@code vanillabp} prefix any
 * more, so a key no registered mapping models fails the startup. This overlay is
 * therefore what lets the start answer the removed key with a message of this adapter
 * instead of SmallRye's "does not map to any root".
 * <p>
 * Never {@code @Inject} this mapping: injecting it turns it into a STATIC-INIT mapping and
 * the whole tree is validated before the adapter extensions registered their RUN_TIME
 * overlays. Read it through
 * {@code ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)}
 * instead.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpPeaProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID.
   *
   * @return The adapter sections
   */
  Map<String, PeaScopedKeys> adapters();

  /**
   * The workflow-module sections of the shared tree, down to the task level, where the
   * removed key could be set too.
   *
   * @return The workflow-module sections, keyed by workflow module ID
   */
  Map<String, ModuleOverlay> workflowModules();

  /**
   * Every key which still sets the removed <code>fetch-variables</code> for one adapter, at
   * the adapter level and at the three levels below it. The start refuses them all in one
   * message, see {@link PeaFetchVariables#rejectTheRemovedKey}.
   *
   * @param adapterId The adapter ID
   * @return The full keys, empty where nobody sets it
   */
  default List<String> fetchVariablesKeys(
      final String adapterId) {

    final var keys = new ArrayList<String>();
    if (setsFetchVariables(adapters(), adapterId)) {
      keys.add("vanillabp.adapters.%s.%s".formatted(adapterId, PeaFetchVariables.REMOVED_KEY));
    }
    workflowModules().forEach((
        moduleId,
        module) -> {
      if (setsFetchVariables(module.adapters(), adapterId)) {
        keys.add("vanillabp.workflow-modules.%s.adapters.%s.%s"
            .formatted(moduleId, adapterId, PeaFetchVariables.REMOVED_KEY));
      }
      module.workflows().forEach((
          workflowId,
          workflow) -> {
        if (setsFetchVariables(workflow.adapters(), adapterId)) {
          keys.add("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(moduleId, workflowId, adapterId, PeaFetchVariables.REMOVED_KEY));
        }
        workflow.tasks().forEach((
            taskId,
            task) -> {
          if (setsFetchVariables(task.adapters(), adapterId)) {
            keys.add("vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                .formatted(moduleId, workflowId, taskId, adapterId, PeaFetchVariables.REMOVED_KEY));
          }
        });
      });
    });
    return keys;

  }

  /**
   * Whether one level sets the removed key for one adapter.
   *
   * @param level The <code>adapters</code> section of the level
   * @param adapterId The adapter ID
   * @return Whether the key is there
   */
  private static boolean setsFetchVariables(
      final Map<String, PeaScopedKeys> level,
      final String adapterId) {

    final var keys = level == null
        ? null
        : level.get(adapterId);
    return (keys != null) && keys.fetchVariables().isPresent();

  }

  /**
   * The scope-specific keys of one <code>adapters.&lt;id&gt;</code> section.
   */
  interface PeaScopedKeys {

    /**
     * The removed key <code>fetch-variables</code> at this level, bound only so the start
     * can refuse it.
     *
     * @return What somebody wrote under the removed key
     */
    Optional<String> fetchVariables();

  }

  /**
   * The adapter's view of one workflow-module section.
   */
  interface ModuleOverlay {

    /**
     * The module-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, PeaScopedKeys> adapters();

    /**
     * The workflow sections of the module, keyed by BPMN process ID.
     *
     * @return The workflow sections
     */
    Map<String, WorkflowOverlay> workflows();

  }

  /**
   * The adapter's view of one workflow section.
   */
  interface WorkflowOverlay {

    /**
     * The workflow-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, PeaScopedKeys> adapters();

    /**
     * The task sections of the workflow, keyed by task definition.
     *
     * @return The task sections
     */
    Map<String, TaskOverlay> tasks();

  }

  /**
   * The adapter's view of one task section - the MOST specific level.
   */
  interface TaskOverlay {

    /**
     * The task-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, PeaScopedKeys> adapters();

  }

}
