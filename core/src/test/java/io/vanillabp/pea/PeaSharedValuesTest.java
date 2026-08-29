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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * Two claims about the payload this adapter sends, both written down in the SPI and in
 * this adapter and held by this test:
 * <ol>
 * <li>{@code WorkflowAggregateSync} promises that the workflow-aggregate's ID is never
 * part of the shared values and that the technical variable carrying it is written
 * ALWAYS, no matter what the sync model says. The Process-Engine-API has no
 * business-key slot, so that variable is the only way back to the workflow: an
 * aggregate annotated {@code @NoSyncWithBPMS} must not become unaddressable.</li>
 * <li>{@code AggregateSyncMode} promises that {@link AggregateSyncMode#FULL} is the
 * default of EVERY adapter, so a model may read the same attributes
 * wherever it runs. This adapter therefore has to ask with FULL.</li>
 * </ol>
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaSharedValuesTest {

  private static final String AGGREGATE_ID_NAME = "loanRequestId";

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  /** What the aggregate's persistence answers - the ID and its name, nothing else. */
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
   * A sync model answering the given values, recording which default it was asked with.
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
        "pea", engine, engine, engine, engine, new io.vanillabp.pea.deployment.PeaDeployedProcesses(), io.vanillabp.pea.TestCollaborators
            .builder(new PeaDeploymentServiceTest.PermissiveInvoker())
            .workflowAggregateSync(aggregateSync)
            .build());

  }

  @Test
  @DisplayName("An aggregate sharing nothing still reaches the engine with its ID variable")
  public void theIdVariableIsWrittenWhateverIsShared() {

    final var sharesNothing = new RecordingSync(Map.of());

    PhaseOperations.phaseTwo(serviceSharing(sharesNothing), io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4711", java.util.Map.of());

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

    PhaseOperations.phaseTwo(serviceSharing(sharesTwo), io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4712", java.util.Map.of());

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

    PhaseOperations.phaseTwo(serviceSharing(recording), io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
        "loan-approval", "LoanApproval", persistence(), "loan-4713", java.util.Map.of());

    assertNotNull(recording.askedWith, "the sync model has to be asked at all");
    assertEquals(AggregateSyncMode.FULL, recording.askedWith);
    assertTrue(PeaProcessService.SYNC_MODE == AggregateSyncMode.FULL);

  }

}
