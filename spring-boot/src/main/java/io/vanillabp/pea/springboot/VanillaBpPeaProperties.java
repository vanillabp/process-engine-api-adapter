package io.vanillabp.pea.springboot;

import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaFetchVariablesResolver;
import lombok.Getter;
import lombok.Setter;

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
@Getter
@Setter
public class VanillaBpPeaProperties {

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
  @Getter
  @Setter
  public static class PeaScopedKeys {

    private PeaFetchVariables.Mode fetchVariables;

  }

  /**
   * The adapter's view of one workflow-module section.
   */
  @Getter
  @Setter
  public static class ModuleOverlay {

    private Map<String, PeaScopedKeys> adapters = Map.of();

    private Map<String, WorkflowOverlay> workflows = Map.of();

  }

  /**
   * The adapter's view of one workflow section.
   */
  @Getter
  @Setter
  public static class WorkflowOverlay {

    private Map<String, PeaScopedKeys> adapters = Map.of();

    private Map<String, TaskOverlay> tasks = Map.of();

  }

  /**
   * The adapter's view of one task section - the MOST specific level.
   */
  @Getter
  @Setter
  public static class TaskOverlay {

    private Map<String, PeaScopedKeys> adapters = Map.of();

  }

}
