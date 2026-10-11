package io.vanillabp.pea.springboot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.pea.wiring.PeaFetchVariables;

/**
 * The view of the Process-Engine-API adapter on the shared <code>vanillabp.*</code>
 * configuration tree. The adapter has no connection settings of its own, because the
 * application provides the engine as beans. The only key here is the removed
 * <code>fetch-variables</code>. It is read at
 * <code>vanillabp.adapters.&lt;id&gt;.fetch-variables</code> and at the three levels below.
 * It is bound only so the start can refuse it, see
 * {@link PeaFetchVariables#rejectTheRemovedKey}.
 * <p>
 * The platform binds the same prefix with its own {@code @ConfigurationProperties} class.
 * The two live side by side. Each one ignores the keys it does not know.
 * <p>
 * The adapter ids never come from this class. They always come from the core properties of
 * the platform. This class is only asked about an adapter id the platform already knows.
 */
@ConfigurationProperties("vanillabp")
public class VanillaBpPeaProperties {

  /**
   * Spring Boot builds the class and fills in the keys it knows. An application which
   * configures nothing keeps the empty maps, and every lookup then finds nothing.
   */
  public VanillaBpPeaProperties() {

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter ID.
   */
  private Map<String, PeaScopedKeys> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree, down to the task level, where the
   * removed key could be set too.
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
   * Every key which still sets the removed <code>fetch-variables</code> for one adapter, at
   * the adapter level and at the three levels below it. The start refuses them all in one
   * message, see {@link PeaFetchVariables#rejectTheRemovedKey}.
   *
   * @param adapterId The adapter ID
   * @return The full keys, empty where nobody sets it
   */
  public List<String> fetchVariablesKeys(
      final String adapterId) {

    final var keys = new ArrayList<String>();
    if (setsFetchVariables(adapters, adapterId)) {
      keys.add("vanillabp.adapters.%s.%s".formatted(adapterId, PeaFetchVariables.REMOVED_KEY));
    }
    workflowModules.forEach((
        moduleId,
        module) -> {
      if (setsFetchVariables(module.getAdapters(), adapterId)) {
        keys.add("vanillabp.workflow-modules.%s.adapters.%s.%s"
            .formatted(moduleId, adapterId, PeaFetchVariables.REMOVED_KEY));
      }
      module.getWorkflows().forEach((
          workflowId,
          workflow) -> {
        if (setsFetchVariables(workflow.getAdapters(), adapterId)) {
          keys.add("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(moduleId, workflowId, adapterId, PeaFetchVariables.REMOVED_KEY));
        }
        workflow.getTasks().forEach((
            taskId,
            task) -> {
          if (setsFetchVariables(task.getAdapters(), adapterId)) {
            keys.add("vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                .formatted(moduleId, workflowId, taskId, adapterId, PeaFetchVariables.REMOVED_KEY));
          }
        });
      });
    });
    return keys;

  }

  private static boolean setsFetchVariables(
      final Map<String, PeaScopedKeys> level,
      final String adapterId) {

    final var keys = level == null
        ? null
        : level.get(adapterId);
    return (keys != null) && (keys.getFetchVariables() != null);

  }

  /**
   * The scope-specific keys of one <code>adapters.&lt;id&gt;</code> section.
   */
  public static class PeaScopedKeys {

    /**
     * Spring Boot builds one per section it finds.
     */
    public PeaScopedKeys() {

    }

    /**
     * The removed key <code>fetch-variables</code> at this level. It is bound only so the
     * start can refuse it. It is a text, so every value somebody wrote reaches that message.
     * A typed field would fail with a conversion error first.
     */
    private String fetchVariables;

    /**
     * The removed key <code>fetch-variables</code> at this level.
     *
     * @return The value somebody wrote, <code>null</code> where this level says nothing
     */
    public String getFetchVariables() {

      return fetchVariables;

    }

    /**
     * The removed key <code>fetch-variables</code> at this level.
     *
     * @param fetchVariables The value somebody wrote, <code>null</code> where this level
     *          says nothing
     */
    public void setFetchVariables(
        final String fetchVariables) {

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
   * The adapter's view of one task section, the most specific level.
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
