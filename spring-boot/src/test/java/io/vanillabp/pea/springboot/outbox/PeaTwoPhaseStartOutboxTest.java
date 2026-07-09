package io.vanillabp.pea.springboot.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import dev.bpmcrafters.processengineapi.ExecutionMode;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.mock.InMemoryProcessEngine.Invocation;
import io.vanillabp.pea.processservice.PeaStartProcessCommand;
import io.vanillabp.spi.process.ProcessService;

/**
 * End-to-end proof that VanillaBP's two-phase workflow start maps onto the
 * Process-Engine-API's {@link ExecutionMode}, driven through
 * {@link ProcessService#startWorkflow} inside a JPA transaction with the phase-two outbox:
 * <ul>
 *   <li>phase one ({@code PREFLIGHT_CHECK}) runs inside the local transaction - exactly one
 *       is recorded while the transaction is still open and no instance is created;</li>
 *   <li>phase two ({@code SYNC}) is dispatched after commit and creates the instance
 *       (matching BPMN process id and aggregate id);</li>
 *   <li>on rollback the {@code PREFLIGHT_CHECK} was recorded but a {@code SYNC} is never
 *       dispatched.</li>
 * </ul>
 * No Docker and no network involved (H2 + in-memory mock engine).
 */
@SpringBootTest(classes = OutboxTestApplication.class)
@ActiveProfiles("outbox")
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class PeaTwoPhaseStartOutboxTest {

  private static final String BPMN_PROCESS_ID = "PeaTestProcess";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private InMemoryProcessEngine engine;

  @BeforeEach
  public void resetEngine() {

    engine.reset();

  }

  private List<Invocation> startsWithMode(
      final ExecutionMode mode) {

    return engine
        .getInvocations()
        .stream()
        .filter(invocation -> "startProcess".equals(invocation.method()))
        .filter(invocation -> invocation.executionMode() == mode)
        .toList();

  }

  private Invocation awaitStartWithMode(
      final ExecutionMode mode,
      final long timeoutMs) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      final var found = startsWithMode(mode);
      if (!found.isEmpty()) {
        return found.getFirst();
      }
      Thread.sleep(50);
    }
    return null;

  }

  @Test
  @DisplayName("Phase one is PREFLIGHT_CHECK inside the transaction, phase two is SYNC after commit")
  public void preflightInTransactionAndSyncAfterCommit() throws Exception {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("commit-test");
      final var saved = processService.startWorkflow(aggregate);

      // still inside the transaction: phase one ran (exactly one PREFLIGHT_CHECK), phase
      // two (SYNC) has not been dispatched yet and no instance exists
      assertEquals(1, startsWithMode(ExecutionMode.PREFLIGHT_CHECK).size());
      assertTrue(startsWithMode(ExecutionMode.SYNC).isEmpty());
      assertTrue(engine.getStartedInstances().isEmpty());

      return saved;
    });

    assertNotNull(attached);
    assertNotNull(attached.getId());

    // the PREFLIGHT_CHECK carried the right process id and aggregate id
    final var preflight = startsWithMode(ExecutionMode.PREFLIGHT_CHECK).getFirst();
    final var preflightCommand = assertInstanceOf(PeaStartProcessCommand.class, preflight.command());
    assertEquals(BPMN_PROCESS_ID, preflightCommand.getBpmnProcessId());
    assertEquals(attached.getId(), preflightCommand.get().get(InMemoryProcessEngine.AGGREGATE_ID_VARIABLE));

    // after commit: exactly one SYNC is dispatched, creating the instance
    final var sync = awaitStartWithMode(ExecutionMode.SYNC, 10000);
    assertNotNull(sync, "phase two (SYNC) must be dispatched after commit");
    final var syncCommand = assertInstanceOf(PeaStartProcessCommand.class, sync.command());
    assertEquals(BPMN_PROCESS_ID, syncCommand.getBpmnProcessId());
    assertEquals(attached.getId(), syncCommand.get().get(InMemoryProcessEngine.AGGREGATE_ID_VARIABLE));

    final var instance = engine.getStartedInstances().get(attached.getId());
    assertNotNull(instance, "SYNC must create an instance keyed by the aggregate id");
    assertEquals(attached.getId(), instance.aggregateId());

  }

  @Test
  @DisplayName("On rollback PREFLIGHT_CHECK was recorded but SYNC is never dispatched")
  public void rollbackRecordsPreflightButNeverSync() throws Exception {

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("rollback-test");
          processService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // phase one already ran synchronously before the rollback
    assertEquals(1, startsWithMode(ExecutionMode.PREFLIGHT_CHECK).size());

    // wait well beyond the outbox poll interval: phase two must never be dispatched
    Thread.sleep(1500);
    assertTrue(startsWithMode(ExecutionMode.SYNC).isEmpty(), "no SYNC may be dispatched after rollback");
    assertTrue(engine.getStartedInstances().isEmpty());

  }

}
