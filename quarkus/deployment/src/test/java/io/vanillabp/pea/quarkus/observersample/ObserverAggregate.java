package io.vanillabp.pea.quarkus.observersample;

/**
 * The workflow aggregate of the Quarkus test of the user-task observers. It is kept in a
 * static store in memory, without a database.
 */
public class ObserverAggregate {

  public String id;

  public String results;

}
