package io.vanillabp.pea.quarkus.tasksample;

/**
 * The workflow aggregate of the Quarkus smoke test of task processing. It is kept in a static
 * store in memory, without a database.
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
