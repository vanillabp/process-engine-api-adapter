package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.bpmcrafters.processengineapi.CommonRestrictions;
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.wiring.PeaTaskMeta;

/**
 * The vocabulary of the meta map an engine delivers a task with: the keys the adapter and
 * whoever watches its user tasks agree on, and the three ways of reading a value out of a map
 * whose entries an engine may simply not have filled.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaTaskMetaTest {

  private static TaskInformation task(
      final Map<String, String> meta) {

    return new TaskInformation("task-1", meta);

  }

  @Test
  @DisplayName("The keys the Process-Engine-API names itself are taken from it, not spelled again")
  public void theApisOwnKeysComeFromTheApi() {

    assertEquals(CommonRestrictions.ACTIVITY_ID, PeaTaskMeta.BPMN_TASK_ID);
    assertEquals(CommonRestrictions.PROCESS_INSTANCE_ID, PeaTaskMeta.WORKFLOW_ID);
    assertEquals(CommonRestrictions.PROCESS_DEFINITION_VERSION_TAG, PeaTaskMeta.PROCESS_VERSION_TAG);

  }

  @Test
  @DisplayName("A value an engine left out and one it left empty read the same")
  public void anEmptyValueReadsAsNone() {

    assertEquals("jane", PeaTaskMeta.text(task(Map.of(PeaTaskMeta.ASSIGNEE, "jane")), PeaTaskMeta.ASSIGNEE));
    assertNull(PeaTaskMeta.text(task(Map.of()), PeaTaskMeta.ASSIGNEE));
    assertNull(PeaTaskMeta.text(task(Map.of(PeaTaskMeta.ASSIGNEE, "  ")), PeaTaskMeta.ASSIGNEE));

  }

  @Test
  @DisplayName("Candidates arrive comma separated and are read as a list")
  public void aCommaSeparatedValueIsReadAsAList() {

    assertEquals(
        List.of("jane", "joe"),
        PeaTaskMeta.list(task(Map.of(PeaTaskMeta.CANDIDATE_USERS, " jane , joe ")), PeaTaskMeta.CANDIDATE_USERS));
    assertTrue(PeaTaskMeta.list(task(Map.of()), PeaTaskMeta.CANDIDATE_GROUPS).isEmpty());
    assertTrue(
        PeaTaskMeta.list(task(Map.of(PeaTaskMeta.CANDIDATE_GROUPS, " , ")), PeaTaskMeta.CANDIDATE_GROUPS).isEmpty());

  }

  @Test
  @DisplayName("A date which is not a date costs the value, never the task")
  public void aBrokenTimestampIsDroppedRatherThanThrown() {

    assertEquals(
        OffsetDateTime.parse("2026-09-13T10:15:30+02:00"),
        PeaTaskMeta.timestamp(task(Map.of(PeaTaskMeta.DUE_DATE, "2026-09-13T10:15:30+02:00")), PeaTaskMeta.DUE_DATE));
    assertNull(PeaTaskMeta.timestamp(task(Map.of()), PeaTaskMeta.FOLLOW_UP_DATE));
    assertNull(
        PeaTaskMeta.timestamp(task(Map.of(PeaTaskMeta.DUE_DATE, "next tuesday")), PeaTaskMeta.DUE_DATE),
        "the engine wrote nonsense - the task is still worth reporting");

  }

}
