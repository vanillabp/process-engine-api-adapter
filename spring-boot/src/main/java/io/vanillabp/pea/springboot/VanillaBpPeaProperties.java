package io.vanillabp.pea.springboot;

import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaFetchVariablesResolver;

/**
 * The Process-Engine-API adapter's OVERLAY of the shared <code>vanillabp.*</code>
 * configuration tree. The adapter has no connection settings of its own - the engine is
 * provided by the application as beans - so the only key here is
 * <code>fetch-variables</code>, and it sits at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.fetch-variables</code> plus the three scoped levels
 * below it. A second {@code @ConfigurationProperties} class over the same prefix coexists
 * with the platform's binding of the core model; keys unknown to either view are ignored
 * by the JavaBean binding.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties; the overlay is a per-known-id lookup only.
 */
@ConfigurationProperties("vanillabp")
public class VanillaBpPeaProperties {

  /**
   * Spring Boot builds the class and fills it with the keys of this overlay it recognises.
   * An application which configures nothing keeps the empty maps, and every lookup then
   * answers the default.
   */
  public VanillaBpPeaProperties() {

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter ID.
   */
  private Map<String, PeaScopedKeys> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree - the overlay mirrors the levels of
   * the most-specific-wins resolution (task &gt; workflow &gt; workflow-module &gt;
   * adapter).
   */
  private Map<String, ModuleOverlay> workflowModules = Map.of();

  /**
   * The adapter sections of the shared tree, keyed by adapter ID.
   *
   * @return The sections, keyed by adapter ID, never <code>null</code>
   */
  public Map<String, PeaScopedKeys> getAdapters() {

    return adapters;

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter ID.
   *
   * @param adapters The sections, keyed by adapter ID
   */
  public void setAdapters(
      final Map<String, PeaScopedKeys> adapters) {

    this.adapters = adapters;

  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow-module ID.
   *
   * @return The sections, keyed by workflow-module ID, never <code>null</code>
   */
  public Map<String, ModuleOverlay> getWorkflowModules() {

    return workflowModules;

  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow-module ID.
   *
   * @param workflowModules The sections, keyed by workflow-module ID
   */
  public void setWorkflowModules(
      final Map<String, ModuleOverlay> workflowModules) {

    this.workflowModules = workflowModules;

  }

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
  public PeaFetchVariables.Mode fetchVariablesFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(PeaScopedKeys::getFetchVariables)
        .filter(Objects::nonNull)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters.get(adapterId);
    return (adapter != null) && (adapter.getFetchVariables() != null)
        ? adapter.getFetchVariables()
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
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.getTasks().get(taskDefinition)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, PeaScopedKeys>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.getAdapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.getAdapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.getAdapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

  }

  /**
   * The scope-specific keys of one <code>adapters.&lt;id&gt;</code> section.
   */
  public static class PeaScopedKeys {

    /**
     * Spring Boot builds one per section it finds. An unset mode is what makes the level
     * fall through to the next less specific one.
     */
    public PeaScopedKeys() {

    }

    /**
     * Whether a subscription of this level asks for the derived payload variables or for
     * all of them, <code>null</code> where the level says nothing.
     */
    private PeaFetchVariables.Mode fetchVariables;

    /**
     * Whether a subscription of this level asks for the derived payload variables or for
     * all of them.
     *
     * @return The mode, <code>null</code> where this level says nothing
     */
    public PeaFetchVariables.Mode getFetchVariables() {

      return fetchVariables;

    }

    /**
     * Whether a subscription of this level asks for the derived payload variables or for
     * all of them.
     *
     * @param fetchVariables The mode, <code>null</code> to fall through to the next less
     *          specific level
     */
    public void setFetchVariables(
        final PeaFetchVariables.Mode fetchVariables) {

      this.fetchVariables = fetchVariables;

    }

  }

  /**
   * The adapter's view of one workflow-module section.
   */
  public static class ModuleOverlay {

    /**
     * Spring Boot builds one per workflow-module section it finds.
     */
    public ModuleOverlay() {

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this workflow module.
     */
    private Map<String, PeaScopedKeys> adapters = Map.of();

    /**
     * The workflow sections of this workflow module, keyed by BPMN process ID.
     */
    private Map<String, WorkflowOverlay> workflows = Map.of();

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @return The sections, keyed by adapter ID, never <code>null</code>
     */
    public Map<String, PeaScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @param adapters The sections, keyed by adapter ID
     */
    public void setAdapters(
        final Map<String, PeaScopedKeys> adapters) {

      this.adapters = adapters;

    }

    /**
     * The workflow sections of this workflow module, keyed by BPMN process ID.
     *
     * @return The sections, keyed by BPMN process ID, never <code>null</code>
     */
    public Map<String, WorkflowOverlay> getWorkflows() {

      return workflows;

    }

    /**
     * The workflow sections of this workflow module, keyed by BPMN process ID.
     *
     * @param workflows The sections, keyed by BPMN process ID
     */
    public void setWorkflows(
        final Map<String, WorkflowOverlay> workflows) {

      this.workflows = workflows;

    }

  }

  /**
   * The adapter's view of one workflow section.
   */
  public static class WorkflowOverlay {

    /**
     * Spring Boot builds one per workflow section it finds.
     */
    public WorkflowOverlay() {

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this workflow.
     */
    private Map<String, PeaScopedKeys> adapters = Map.of();

    /**
     * The task sections of this workflow, keyed by task definition.
     */
    private Map<String, TaskOverlay> tasks = Map.of();

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @return The sections, keyed by adapter ID, never <code>null</code>
     */
    public Map<String, PeaScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @param adapters The sections, keyed by adapter ID
     */
    public void setAdapters(
        final Map<String, PeaScopedKeys> adapters) {

      this.adapters = adapters;

    }

    /**
     * The task sections of this workflow, keyed by task definition.
     *
     * @return The sections, keyed by task definition, never <code>null</code>
     */
    public Map<String, TaskOverlay> getTasks() {

      return tasks;

    }

    /**
     * The task sections of this workflow, keyed by task definition.
     *
     * @param tasks The sections, keyed by task definition
     */
    public void setTasks(
        final Map<String, TaskOverlay> tasks) {

      this.tasks = tasks;

    }

  }

  /**
   * The adapter's view of one task section - the MOST specific level.
   */
  public static class TaskOverlay {

    /**
     * Spring Boot builds one per task section it finds.
     */
    public TaskOverlay() {

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this task.
     */
    private Map<String, PeaScopedKeys> adapters = Map.of();

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @return The sections, keyed by adapter ID, never <code>null</code>
     */
    public Map<String, PeaScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The <code>adapters.&lt;id&gt;</code> sections of this level, keyed by adapter ID.
     *
     * @param adapters The sections, keyed by adapter ID
     */
    public void setAdapters(
        final Map<String, PeaScopedKeys> adapters) {

      this.adapters = adapters;

    }

  }

}
