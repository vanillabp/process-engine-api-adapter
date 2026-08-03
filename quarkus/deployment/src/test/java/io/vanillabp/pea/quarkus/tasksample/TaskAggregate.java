package io.vanillabp.pea.quarkus.tasksample;

/**
 * The workflow aggregate of the Quarkus task-processing smoke test - held in a
 * static in-memory store (no database involved).
 */
public class TaskAggregate {

  public String id;

  public String results;

  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
