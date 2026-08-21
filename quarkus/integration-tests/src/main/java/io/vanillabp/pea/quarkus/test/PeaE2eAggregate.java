package io.vanillabp.pea.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The workflow aggregate of the Quarkus end-to-end application. It is a JPA entity
 * on H2 for two reasons: the platform reads the persistence technology off the
 * aggregate to attribute the JDBC phase-two outbox, and a rollback has to really
 * discard the aggregate change so the two-phase assertions mean something.
 * <p>
 * Deliberately a plain entity with a repository rather than an active record: the
 * adapter reads the aggregate's JavaBean properties to build what travels to the
 * engine, and the active-record base class would add persistence methods to that
 * set which only work while a session is open.
 */
@Entity
public class PeaE2eAggregate {

  @Id
  private String id;

  private String results;

  private String taskId;

  /**
   * Never sent to the engine - which (story 28b) also makes the class' sync mode
   * "share everything else".
   */
  @NoSyncWithBPMS
  private String secret;

  public String getId() {

    return id;

  }

  public void setId(
      final String id) {

    this.id = id;

  }

  public String getResults() {

    return results;

  }

  public void setResults(
      final String results) {

    this.results = results;

  }

  public String getTaskId() {

    return taskId;

  }

  public void setTaskId(
      final String taskId) {

    this.taskId = taskId;

  }

  public String getSecret() {

    return secret;

  }

  public void setSecret(
      final String secret) {

    this.secret = secret;

  }

  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
