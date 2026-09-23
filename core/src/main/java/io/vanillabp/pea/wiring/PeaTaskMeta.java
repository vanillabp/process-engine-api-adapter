package io.vanillabp.pea.wiring;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
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
 * This adapter reads four of them itself and hands the rest on untouched. They are spelled out
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
   * <p>
   * The value is the id the ENGINE knows, so it carries the prefix under
   * {@code use-prefix}. Read it through
   * {@link #plainBpmnProcessId(TaskInformation, NameClashAvoidanceSupport, String, String)}
   * rather than from the map, because everything this adapter hands the core is keyed by
   * the plain id (see decision 2 in the repository's DECISIONS.md).
   */
  public static final String BPMN_PROCESS_ID = "bpmnProcessId";

  /**
   * The BPMN element id of the task, what a modeller wrote as the element's id. Both task
   * handlers report it to the core, which writes it into the delivery record; an engine
   * filling no such key leaves the record without an element.
   */
  public static final String BPMN_TASK_ID = CommonRestrictions.ACTIVITY_ID;

  /**
   * The engine's own id of the workflow the task belongs to. It travels into the delivery
   * record like {@link #BPMN_TASK_ID} and is empty there for the same reason where an engine
   * names none.
   */
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
   * Reads one meta value. Every key is optional: which of them an engine fills is that
   * engine's business, so a caller handles an empty answer rather than requiring one.
   *
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
   * Which BPMN process a delivery says it belongs to, in the form the core's registries are
   * keyed by.
   * <p>
   * The engine fills {@link #BPMN_PROCESS_ID} with the id IT knows, and under
   * {@code use-prefix} that id carries the workflow module as a prefix. Everything this
   * adapter hands over - the registry lookup of a handler, the process id an observer reads,
   * the id a viewer asks about - uses the plain id the application wrote, so the value is
   * translated back here.
   * <p>
   * Both task handlers read this one entry, and they used to read it differently: the
   * user-task side translated and the service-task side did not, which broke every
   * service-task delivery of an engine filling the entry while the module prefixed its
   * identifiers. The rule lives here now so the two cannot drift apart again.
   *
   * @param taskInformation What the engine delivered
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   * @param workflowModuleId The workflow module the subscription belongs to
   * @param adapterId The adapter which was delivered the task
   * @return The plain BPMN process id, or <code>null</code> where the engine filled none
   */
  public static String plainBpmnProcessId(
      final TaskInformation taskInformation,
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String adapterId) {

    final var fromMeta = text(taskInformation, BPMN_PROCESS_ID);
    if (fromMeta == null) {
      return null;
    }
    return NameClashAvoidanceSupport.plainProcessId(scoping, workflowModuleId, fromMeta, adapterId);

  }

  /**
   * Reads a meta value which names several things, the candidate groups of a user task for
   * example. The API carries them as one string, so the comma is the separator and nothing
   * else can be.
   *
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
