package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Pushing a changed workflow-aggregate through the Process-Engine-API (story 44) -
 * which the API cannot do: it modifies the payload of a TASK, never that of a running
 * process instance (GAPS.md entry 18). What this test pins is that the refusal comes
 * in phase ONE, at the application's call, and that it names a way forward.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaAggregateChangedTest {

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private PeaProcessService<Object> service() {

    return new PeaProcessService<Object>("pea", engine, engine, engine, engine);

  }

  @Test
  @DisplayName("Phase one refuses, naming the process and a way forward")
  public void phaseOneRefusesGuiding() {

    final var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> service().aggregateChangedPhaseOne("mod", "Process", null, new Object(), null));

    assertTrue(exception.getMessage().contains("Process"));
    assertTrue(exception.getMessage().contains("GAPS.md entry 18"));
    assertTrue(exception.getMessage().contains("completes a task"));

  }

  @Test
  @DisplayName("Phase two refuses the same way - an outbox entry cannot exist, but it stays honest")
  public void phaseTwoRefusesGuiding() {

    final var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> service().aggregateChangedPhaseTwo("mod", "Process", null, "42", "task-1"));

    assertTrue(exception.getMessage().contains("mod"));

  }

}
