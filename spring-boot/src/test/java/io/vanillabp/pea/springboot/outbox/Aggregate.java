package io.vanillabp.pea.springboot.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A JPA workflow aggregate with a generated (non-string) id: the outbox serializes the id
 * as a string and the phase-two dispatch converts it back to the original type before the
 * adapter receives it.
 */
@Entity
@Table(name = "PEA_OUTBOX_TEST_AGGREGATE")
@Getter
@Setter
public class Aggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

  /**
   * Never part of the payload sent to the engine - which also
   * derives the class' mode "share everything else" (opt-out).
   */
  @io.vanillabp.spi.service.NoSyncWithBPMS
  private String secret;

}
