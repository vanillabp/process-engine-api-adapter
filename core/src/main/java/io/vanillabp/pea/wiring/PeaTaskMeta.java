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
 * The Process-Engine-API names the keys a subscription may be RESTRICTED by
 * ({@link CommonRestrictions}). It names no keys for what a delivered task carries. Each
 * engine adapter fills what it has, and {@link TaskInformation} itself names only
 * {@code reason} and {@code retries}. The keys below are the ones that the API's own
 * reference adapter for an embedded Camunda 7 fills. So a subscriber can expect these, if it
 * can expect anything. A key an engine leaves out is never an error. The task is still worth
 * reporting, just with one detail less.
 * <p>
 * This adapter reads four of the keys itself and passes the rest on as they are. The keys
 * are written here and not in the handlers. That way, whoever watches the user tasks of this
 * adapter ({@link io.vanillabp.pea.observation.PeaUserTaskObserver}) reads the same names
 * the adapter writes. Each value has one spelling, and it is the engine's spelling.
 * <p>
 * What the keys MEAN is up to the Process-Engine-API, not to this adapter. Entry 3 of the
 * {@code GAPS.md} of the Business Cockpit adapter says where the API could fix that most
 * cheaply.
 */
@Slf4j
public final class PeaTaskMeta {

  /**
   * The BPMN process id of the delivered task. This key is a convention of this ADAPTER, not
   * of the engine. The Process-Engine-API names no such key (see {@code GAPS.md}, entry 6).
   * Without it, a delivery can only be routed if its task definition is unique across the
   * processes of the workflow module.
   * <p>
   * The value is the id the ENGINE knows, so under {@code use-prefix} it carries the prefix.
   * Read it through
   * {@link #plainBpmnProcessId(TaskInformation, NameClashAvoidanceSupport, String, String)}
   * and not from the map. Everything this adapter hands to the core is keyed by the plain id
   * (see decision 2 in the repository's DECISIONS.md).
   */
  public static final String BPMN_PROCESS_ID = "bpmnProcessId";

  /**
   * The BPMN element id of the task, what a modeller wrote as the element's id. Both task
   * handlers report it to the core, which writes it into the delivery record; an engine
   * filling no such key leaves the record without an element.
   */
  public static final String BPMN_TASK_ID = CommonRestrictions.ACTIVITY_ID;

  /**
   * The engine's own id of the workflow the task belongs to. It goes into the delivery record
   * like {@link #BPMN_TASK_ID}. If an engine does not fill it, the record has no workflow id.
   */
  public static final String WORKFLOW_ID = CommonRestrictions.PROCESS_INSTANCE_ID;

  /**
   * The version tag of the deployed process definition, if the engine keeps one. The API has
   * no version number. So this tag is the only thing VanillaBP can match
   * <code>&#64;WorkflowTask(version = ...)</code> against here (see {@code GAPS.md}, entry
   * 19).
   */
  public static final String PROCESS_VERSION_TAG = CommonRestrictions.PROCESS_DEFINITION_VERSION_TAG;

  /** The BPMN name of the task. A task list shows it if it has no better title. */
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
   * Reads one meta value. Every key is optional. Each engine decides which keys it fills, so
   * a caller has to cope with an empty answer.
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
   * The BPMN process a delivery says it belongs to, in the form the registries of the core
   * use as their key.
   * <p>
   * The engine fills {@link #BPMN_PROCESS_ID} with the id IT knows. Under {@code use-prefix},
   * that id starts with the workflow module as a prefix. But everything this adapter hands
   * over uses the plain id the application wrote: the registry lookup of a handler, the
   * process id an observer reads, the id a viewer asks about. So the value is translated
   * back here.
   * <p>
   * Both task handlers read this entry. They used to read it differently: the user-task side
   * translated it, the service-task side did not. That broke every service-task delivery
   * when the engine filled the entry and the module used prefixes. The rule now lives here,
   * so the two cannot drift apart again.
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
   * Reads a meta value which names several things, for example the candidate groups of a
   * user task. The API carries them as one string, and the separator is the comma.
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
   * A value which is not a date is logged and dropped, not thrown. The task itself is still
   * worth reporting, and the defect lies with the engine which wrote the value.
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
