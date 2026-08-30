package io.vanillabp.pea;

import static org.mockito.Mockito.mock;

import org.mockito.Answers;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What the platform hands the adapter, for tests which need the adapter and not the
 * registration. The core standing in for both halves of the task SPI is given per test,
 * and so is the scoping where a test is about what this adapter deploys under which
 * name; the rest are mocks nobody calls unless the test says so.
 */
public final class TestCollaborators {

  private TestCollaborators() {
    // static helper
  }

  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core) {

    return of(core, scopingWithoutNameClashAvoidance());

  }

  /**
   * The default of this adapter: identifiers are deployed as modelled. A bare mock would
   * answer null to every one of them, and null is not a value the platform ever gives an
   * adapter.
   */
  private static NameClashAvoidanceSupport scopingWithoutNameClashAvoidance() {

    return mock(NameClashAvoidanceSupport.class, invocation -> {
      final var method = invocation.getMethod();
      if ("modeFor".equals(method.getName())) {
        return NameClashAvoidance.NONE;
      }
      if (method.getReturnType() == String.class) {
        // every one of them ends with the adapter id and carries the identifier right
        // before it
        return invocation.getArgument(invocation.getArguments().length - 2);
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });

  }

  /**
   * For a test which needs one collaborator to be its own: take the builder, replace it,
   * build.
   *
   * @param <T> A double playing both halves of the task SPI
   * @param core The double
   * @return A builder with every other collaborator already filled
   */
  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators.Builder builder(
      final T core) {

    return AdapterCollaborators
        .forAdapter("pea")
        .workflowTaskWiring(core)
        .workflowTaskInvoker(core)
        .scoping(scopingWithoutNameClashAvoidance())
        .workflowAggregateSync(mock(WorkflowAggregateSync.class))
        .preCommitRegistrar(mock(PreCommitRegistrar.class))
        .workflowEndedInvoker(mock(WorkflowEndedInvoker.class))
        .bpmsInitiatedStartInvoker(mock(BpmsInitiatedStartInvoker.class));

  }

  public static <T extends WorkflowTaskWiring & WorkflowTaskInvoker> AdapterCollaborators of(
      final T core,
      final NameClashAvoidanceSupport scoping) {

    return AdapterCollaborators
        .forAdapter("pea")
        .workflowTaskWiring(core)
        .workflowTaskInvoker(core)
        .scoping(scoping)
        .workflowAggregateSync(mock(WorkflowAggregateSync.class))
        .preCommitRegistrar(mock(PreCommitRegistrar.class))
        .workflowEndedInvoker(mock(WorkflowEndedInvoker.class))
        .bpmsInitiatedStartInvoker(mock(BpmsInitiatedStartInvoker.class))
        .build();

  }

}
