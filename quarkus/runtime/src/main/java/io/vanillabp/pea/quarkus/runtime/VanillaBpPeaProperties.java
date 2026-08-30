package io.vanillabp.pea.quarkus.runtime;

import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaFetchVariablesResolver;

/**
 * The Process-Engine-API adapter's OVERLAY of the shared <code>vanillabp.*</code>
 * configuration tree. The adapter has no connection settings of its own - the engine is
 * provided by the application as beans - so the only key here is
 * <code>fetch-variables</code>, at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.fetch-variables</code> plus the three scoped levels
 * below it.
 * <p>
 * Quarkus knows no blanket {@code withMappingIgnore} for the {@code vanillabp} prefix any
 * more, so a key no registered mapping models fails the startup. This overlay is
 * therefore what makes the adapter's key writable at all.
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
   * The workflow-module sections of the shared tree - the overlay mirrors the levels of
   * the most-specific-wins resolution (task &gt; workflow &gt; workflow-module &gt;
   * adapter).
   *
   * @return The workflow-module sections, keyed by workflow module ID
   */
  Map<String, ModuleOverlay> workflowModules();

  /**
   * Resolves whether a subscription asks for the DERIVED payload variables or for all of
   * them, most specific wins; falls back to the adapter-level value and finally the
   * default {@code derived}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition
   * @param adapterId The adapter ID
   * @return The most specific configured mode or the default
   */
  default PeaFetchVariables.Mode fetchVariablesFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(PeaScopedKeys::fetchVariables)
        .flatMap(Optional::stream)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters().get(adapterId);
    return adapter != null
        ? adapter
            .fetchVariables()
            .orElse(PeaFetchVariablesResolver.DEFAULT_FETCH_VARIABLES)
        : PeaFetchVariablesResolver.DEFAULT_FETCH_VARIABLES;

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> sections of the three levels below the adapter,
   * most specific first.
   */
  private Stream<PeaScopedKeys> scopedKeysMostSpecificFirst(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.tasks().get(taskDefinition)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, PeaScopedKeys>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.adapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.adapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.adapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

  }

  /**
   * The scope-specific keys of one <code>adapters.&lt;id&gt;</code> section.
   */
  interface PeaScopedKeys {

    /**
     * Whether a subscription asks for the derived payload variables or for all of them,
     * at this level.
     *
     * @return The mode
     */
    Optional<PeaFetchVariables.Mode> fetchVariables();

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
