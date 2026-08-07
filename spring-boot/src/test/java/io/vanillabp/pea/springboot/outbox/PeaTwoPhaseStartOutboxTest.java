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

  /**
   * The name of the payload variable carrying the aggregate's ID: the adapter names it
   * after the aggregate's ID property ({@link Aggregate#getId()}).
   */
  private static final String AGGREGATE_ID_VARIABLE = "id";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private InMemoryProcessEngine engine;

  @Autowired
  private AggregateRepository repository;

  @BeforeEach
  public void resetEngine() {

    engine.reset();
    repository.deleteAll();

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
    assertEquals(attached.getId(), preflightCommand.get().get(AGGREGATE_ID_VARIABLE));

    // after commit: exactly one SYNC is dispatched, creating the instance
    final var sync = awaitStartWithMode(ExecutionMode.SYNC, 10000);
    assertNotNull(sync, "phase two (SYNC) must be dispatched after commit");
    final var syncCommand = assertInstanceOf(PeaStartProcessCommand.class, sync.command());
    assertEquals(BPMN_PROCESS_ID, syncCommand.getBpmnProcessId());
    assertEquals(attached.getId(), syncCommand.get().get(AGGREGATE_ID_VARIABLE));

    assertEquals(1, engine.getStartedInstances().size(), "SYNC must create exactly one instance");
    final var instance = engine.getStartedInstances().getFirst();
    assertEquals(attached.getId(), instance.variables().get(AGGREGATE_ID_VARIABLE));

  }

  @Test
  @DisplayName("The start payload carries the shared attributes plus the ID variable (story 28/28b)")
  public void startPayloadCarriesTheSharedAttributes() throws Exception {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("shared");
      aggregate.setSecret("s3cr3t");
      return processService.startWorkflow(aggregate);
    });

    final var sync = awaitStartWithMode(ExecutionMode.SYNC, 10000);
    assertNotNull(sync, "phase two (SYNC) must be dispatched after commit");

    // BOTH phases carry the same payload: the preflight validates what phase two
    // will send
    for (final var command : java.util.List.of(
        startsWithMode(ExecutionMode.PREFLIGHT_CHECK).getFirst().command(), sync.command())) {
      final var payload = assertInstanceOf(PeaStartProcessCommand.class, command).get();
      assertEquals(attached.getId(), payload.get(AGGREGATE_ID_VARIABLE));
      assertEquals("shared", payload.get("content"));
      assertTrue(
          !payload.containsKey("secret"),
          "a @NoSyncWithBPMS attribute must never travel but the payload was: "
              + payload);
    }

    assertEquals("shared", engine.getStartedInstances().getFirst().variables().get("content"));

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

  @Test
  @DisplayName("A failed PREFLIGHT_CHECK (phase one) rolls the caller's transaction back")
  public void failedPreflightRollsTransactionBack() throws Exception {

    engine.failPreflightFor(BPMN_PROCESS_ID);

    // the joined future's failure surfaces in phase one and aborts the transaction
    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("preflight-fail-test");
          return processService.startWorkflow(aggregate);
        }));
    assertTrue(exception.getMessage().contains("Preflight check"));
    assertTrue(exception.getMessage().contains(BPMN_PROCESS_ID));

    // the transaction rolled back: the aggregate was not persisted
    assertEquals(0, repository.count(), "the aggregate must not be persisted after a failed preflight");

    // and phase two is never dispatched (no outbox entry was committed)
    Thread.sleep(1500);
    assertTrue(startsWithMode(ExecutionMode.SYNC).isEmpty());
    assertTrue(engine.getStartedInstances().isEmpty());

  }

  @Test
  @DisplayName("A failed SYNC (phase two) makes the outbox retry the dispatch")
  public void failedSyncIsRetriedByOutbox() throws Exception {

    engine.failNextSyncFor(BPMN_PROCESS_ID);

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("sync-fail-test");
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);

    // the first SYNC fails (joined future -> outbox retries), the second succeeds:
    // the retry is visible as a second SYNC invocation and the instance exists
    final var deadline = System.currentTimeMillis() + 10000;
    while (startsWithMode(ExecutionMode.SYNC).size() < 2) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected the outbox to retry the failed SYNC dispatch");
      Thread.sleep(50);
    }
    assertEquals(1, engine.getStartedInstances().size(), "the retry has to create the instance exactly once");
    assertEquals(
        attached.getId(), engine.getStartedInstances().getFirst().variables().get(AGGREGATE_ID_VARIABLE));

  }

}
