package io.vanillabp.pea.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the Quarkus end-to-end application. It is a JPA entity on H2,
 * for two reasons. The platform reads the persistence technology from the aggregate, and so
 * picks the JDBC outbox for phase two. And a rollback has to really throw away the change of
 * the aggregate, or the assertions about the two phases mean nothing.
 * <p>
 * It is a plain entity with a repository on purpose, not an active record. The adapter reads
 * the JavaBean properties of the aggregate to build what goes to the engine. The base class
 * of an active record would add persistence methods to these properties, and they only work
 * while a session is open.
 */
@Entity
@Getter
@Setter
public class PeaE2eAggregate {

  @Id
  private String id;

  private String results;

  private String taskId;

  /**
   * Never sent to the engine. Because of it, the class shares everything else.
   */
  @NoSyncWithBPMS
  private String secret;

  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
