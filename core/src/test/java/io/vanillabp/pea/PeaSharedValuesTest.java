package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.deployment.PeaDeployedProcesses;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Two promises about the payload this adapter sends. The SPI and this adapter both write
 * them down, and this test holds them:
 * <ol>
 * <li>{@code WorkflowAggregateSync} promises that the ID of the workflow aggregate is never
 * one of the shared values. It also promises that the technical variable with the ID is
 * ALWAYS written, whatever the sync model says. The Process-Engine-API has no slot for a
 * business key, so that variable is the only way back to the workflow. An aggregate
 * annotated {@code @NoSyncWithBPMS} must still be reachable.</li>
 * <li>{@code AggregateSyncMode} promises that {@link AggregateSyncMode#FULL} is the default
 * of EVERY adapter, so a model may read the same attributes wherever it runs. So this
 * adapter has to ask with FULL.</li>
 * </ol>
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaSharedValuesTest {

  private static final String AGGREGATE_ID_NAME = "loanRequestId";

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  /** What the aggregate's persistence answers: the ID and its name, nothing else. */
  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {

        return Object.class;

      }

      @Override
      public String getAggregateIdName() {

        return AGGREGATE_ID_NAME;

      }

      @Override
      public Object loadById(
          final Object aggregateId) {

        return new Object();

      }

    };

  }

  /**
   * A sync model which answers the given values and records which default it was asked
   * with.
   */
  private static class RecordingSync implements WorkflowAggregateSync {

    private final Map<String, Object> values;

    private AggregateSyncMode askedWith;

    private RecordingSync(
        final Map<String, Object> values) {

      this.values = values;

    }

    @Override
    public Map<String, Object> syncedValues(
        final Object workflowAggregate,
        final AggregateSyncMode adapterDefault) {

      askedWith = adapterDefault;
      return values;

    }

    @Override
    public void validateSyncModel(
        final Class<?> workflowAggregateClass) {

    }

  }

  private PeaProcessService<Object> serviceSharing(
      final WorkflowAggregateSync aggregateSync) {

    return new PeaProcessService<>(
        "pea", engine, engine, engine, engine, new PeaDeployedProcesses(), TestCollaborators
            .builder(new PeaDeploymentServiceTest.PermissiveInvoker())
            .workflowAggregateSync(aggregateSync)
            .build());

  }

  @Test
  @DisplayName("An aggregate sharing nothing still reaches the engine with its ID variable")
  public void theIdVariableIsWrittenWhateverIsShared() {

    final var sharesNothing = new RecordingSync(Map.of());

    PhaseOperations.phaseTwo(serviceSharing(sharesNothing), PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4711", Map.of());

    final var started = engine.getStartedInstances();
    assertEquals(1, started.size(), "the workflow has to be started");
    assertEquals(
        Map.of(AGGREGATE_ID_NAME, "loan-4711"),
        started.getFirst().variables(),
        "the technical ID variable is written although the aggregate shares nothing");

  }

  @Test
  @DisplayName("The shared values travel next to the ID variable, which is none of them")
  public void theIdVariableIsNotOneOfTheSharedValues() {

    final var sharesTwo = new RecordingSync(Map.of("amount", 4711, "approved", Boolean.FALSE));

    PhaseOperations.phaseTwo(serviceSharing(sharesTwo), PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4712", Map.of());

    final var variables = engine.getStartedInstances().getFirst().variables();
    assertEquals(3, variables.size(), "the shared values plus the ID variable");
    assertEquals(4711, variables.get("amount"));
    assertEquals(Boolean.FALSE, variables.get("approved"));
    assertEquals("loan-4712", variables.get(AGGREGATE_ID_NAME));

  }

  @Test
  @DisplayName("This adapter asks for everything - FULL is the default of every adapter")
  public void theAdapterAsksWithFull() {

    final var recording = new RecordingSync(Map.of());

    PhaseOperations.phaseTwo(serviceSharing(recording), PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4713", Map.of());

    assertNotNull(recording.askedWith, "the sync model has to be asked at all");
    assertEquals(AggregateSyncMode.FULL, recording.askedWith);
    assertTrue(PeaProcessService.SYNC_MODE == AggregateSyncMode.FULL);

  }

}
