package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Broadcasting a BPMN signal through the Process-Engine-API. The API has
 * a {@code SignalApi}, so unlike the other gaps of this adapter this one needed no
 * workaround - what the test pins is that the broadcast waits for phase two (the
 * engine is treated as REMOTE) and that an engine implementation without that API
 * says so instead of swallowing the signal.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaSendSignalTest {

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private PeaProcessService<Object> service() {

    final var service = new PeaProcessService<Object>("pea", engine, engine, engine, engine);
    service.setSignalApi(engine);
    return service;

  }

  @Test
  @DisplayName("Phase one broadcasts nothing, phase two hands the signal to the engine")
  public void broadcastHappensInPhaseTwo() {

    final var service = service();

    PhaseOperations.phaseOne(service, io.vanillabp.integration.spi.PhaseOperation.SEND_SIGNAL, "mod", "Process", null,
        null, PhaseOperations.args(io.vanillabp.integration.spi.PhaseTwoCall.ARG_SIGNAL_NAME, "OrderReceived"));
    assertTrue(engine.getBroadcastSignals().isEmpty(), "a remote engine must not act before the commit");

    PhaseOperations.phaseTwo(service, io.vanillabp.integration.spi.PhaseOperation.SEND_SIGNAL, "mod", "Process", null,
        null, PhaseOperations.args(io.vanillabp.integration.spi.PhaseTwoCall.ARG_SIGNAL_NAME, "OrderReceived"));
    assertEquals(List.of("OrderReceived"), engine.getBroadcastSignals());

  }

  @Test
  @DisplayName("An engine without a SignalApi says so instead of losing the signal")
  public void missingSignalApiFailsGuiding() {

    final var service = new PeaProcessService<Object>("pea", engine, engine, engine, engine);

    final var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> PhaseOperations.phaseTwo(service, io.vanillabp.integration.spi.PhaseOperation.SEND_SIGNAL, "mod",
            "Process", null, null,
            PhaseOperations.args(io.vanillabp.integration.spi.PhaseTwoCall.ARG_SIGNAL_NAME, "OrderReceived")));

    assertTrue(exception.getMessage().contains("OrderReceived"));
    assertTrue(exception.getMessage().contains("SignalApi"));

  }

}
