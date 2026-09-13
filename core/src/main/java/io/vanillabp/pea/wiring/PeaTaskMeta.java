package io.vanillabp.pea.wiring;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import lombok.extern.slf4j.Slf4j;

/**
 * The keys a delivered task carries in {@link TaskInformation#getMeta()}, and how to read
 * them.
 * <p>
 * The Process-Engine-API defines a vocabulary for the keys a subscription may be RESTRICTED
 * by ({@link CommonRestrictions}) and none for the keys a delivered task carries: an engine
 * adapter fills whatever it has, and {@link TaskInformation} itself names only
 * {@code reason} and {@code retries}. The keys below are the ones the API's own reference
 * adapter for an embedded Camunda 7 fills, so they are what a subscriber can expect where it
 * can expect anything. A key an engine leaves out is never an error - it means one detail
 * less about a task worth reporting anyway.
 * <p>
 * This adapter reads two of them itself and hands the rest on untouched. They are spelled out
 * here rather than in the handlers so that whoever watches this adapter's user tasks
 * ({@link io.vanillabp.pea.observation.PeaUserTaskObserver}) reads the same names the adapter
 * writes: one value, one spelling, and the spelling which matters is the engine's.
 * <p>
 * What the keys MEAN is the Process-Engine-API's business, not this adapter's - see entry 3
 * of the Business Cockpit adapter's {@code GAPS.md}, where the API could close it cheapest.
 */
@Slf4j
public final class PeaTaskMeta {

  /**
   * The BPMN process id of the delivered task. This one is an ADAPTER convention rather
   * than the engine's: the Process-Engine-API names no such key (see {@code GAPS.md}, entry
   * 6), and without it a task definition has to be unique across the processes of a
   * workflow module for a delivery to be routable.
   */
  public static final String BPMN_PROCESS_ID = "bpmnProcessId";

  /** The BPMN element id of the task, what a modeller wrote as the element's id. */
  public static final String BPMN_TASK_ID = CommonRestrictions.ACTIVITY_ID;

  /** The engine's own id of the workflow the task belongs to. */
  public static final String WORKFLOW_ID = CommonRestrictions.PROCESS_INSTANCE_ID;

  /**
   * The version tag of the deployed process definition, where an engine keeps one. The API
   * has no numeric version, so this tag is all VanillaBP can match
   * <code>&#64;WorkflowTask(version = ...)</code> against here (see {@code GAPS.md}, entry
   * 19).
   */
  public static final String PROCESS_VERSION_TAG = CommonRestrictions.PROCESS_DEFINITION_VERSION_TAG;

  /** The BPMN name of the task, the fallback title of a task list. */
  public static final String TASK_NAME = "taskName";

  /** Who the task is assigned to. */
  public static final String ASSIGNEE = "assignee";

  /** Who may claim the task, comma separated. */
  public static final String CANDIDATE_USERS = "candidateUsers";

  /** Whose members may claim the task, comma separated. */
  public static final String CANDIDATE_GROUPS = "candidateGroups";

  /** When the task is due, ISO-8601. */
  public static final String DUE_DATE = "dueDate";

  /** When somebody wants to be reminded of the task, ISO-8601. */
  public static final String FOLLOW_UP_DATE = "followUpDate";

  private PeaTaskMeta() {
  }

  /**
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The value, or <code>null</code> where the engine filled none
   */
  public static String text(
      final TaskInformation taskInformation,
      final String key) {

    final var value = taskInformation.getMeta().get(key);
    return (value == null) || value.isBlank()
        ? null
        : value;

  }

  /**
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The comma-separated value as a list, empty where the engine filled none
   */
  public static List<String> list(
      final TaskInformation taskInformation,
      final String key) {

    final var value = text(taskInformation, key);
    if (value == null) {
      return List.of();
    }
    return Arrays
        .stream(value.split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .toList();

  }

  /**
   * A date the engine wrote into its meta map.
   * <p>
   * A value which is not a date is reported and dropped rather than thrown: the task itself
   * is worth reporting, and the defect belongs to the engine which wrote the value.
   *
   * @param taskInformation What the engine delivered
   * @param key One of the keys above
   * @return The date, or <code>null</code> where the engine filled none or wrote nonsense
   */
  public static OffsetDateTime timestamp(
      final TaskInformation taskInformation,
      final String key) {

    final var value = text(taskInformation, key);
    if (value == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (final DateTimeParseException e) {
      log.warn(
          "Process-Engine-API: task '{}' carries '{}' as its '{}', which is not an ISO-8601 "
              + "timestamp. Whoever reads the task gets it without that value.",
          taskInformation.getTaskId(),
          value,
          key);
      return null;
    }

  }

}
