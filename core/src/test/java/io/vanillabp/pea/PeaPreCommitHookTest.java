package io.vanillabp.pea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.pea.mock.InMemoryProcessEngine;
import io.vanillabp.pea.processservice.PeaProcessService;

/**
 * The phase-one check of this adapter is handed to the platform's pre-commit hook
 * instead of running when the application calls, so the window in which its answer goes
 * stale before phase two stays small. Without a hook it runs immediately, the behaviour this
 * adapter had before.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PeaPreCommitHookTest {

  private static class OrderAggregate {
  }

  private final InMemoryProcessEngine engine = new InMemoryProcessEngine();

  private final List<String> events = new LinkedList<>();

  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        @SuppressWarnings("unchecked")
        final var aggregateClass = (Class<Object>) (Class<?>) OrderAggregate.class;
        return aggregateClass;
      }

      @Override
      public Object save(
          final Object aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final Object aggregate) {
        return "42";
      }

    };

  }

  private PeaProcessService<Object> service() {

    return serviceHandingChecksTo(mock(io.vanillabp.integration.adapter.spi.PreCommitRegistrar.class));

  }

  /**
   * The service with the hook this test wants to observe: where a check is handed over is
   * decided while the service is built, like every other collaborator.
   */
  private PeaProcessService<Object> serviceHandingChecksTo(
      final io.vanillabp.integration.adapter.spi.PreCommitRegistrar preCommitRegistrar) {

    return new PeaProcessService<>(
        "pea", engine, engine, engine, engine, new io.vanillabp.pea.deployment.PeaDeployedProcesses(), io.vanillabp.pea.TestCollaborators
            .builder(new PeaDeploymentServiceTest.PermissiveInvoker())
            .preCommitRegistrar(preCommitRegistrar)
            .build());

  }

  @Test
  @DisplayName("The check is handed over naming the aggregate, and runs when the hook says so")
  public void theCheckIsHandedToTheHook() {

    engine.getOpenTaskIds().add("task-1");
    final var deferred = new LinkedList<Runnable>();
    final var service = serviceHandingChecksTo((
        aggregateClass,
        check) -> {
      events.add("handed over for "
          + aggregateClass.getSimpleName());
      deferred.add(check);
    });

    service.completeTaskPhaseOne("mod", "Process", persistence(), new Object(), "task-1");

    // nothing was asked of the engine yet - the check waits for the commit
    assertEquals(List.of("handed over for OrderAggregate"), events);
    assertTrue(engine.getInvocations().isEmpty(), engine.getInvocations().toString());

    deferred.forEach(Runnable::run);
    assertTrue(
        engine
            .getInvocations()
            .stream()
            .anyMatch(invocation -> "completeTask".equals(invocation.method())),
        engine.getInvocations().toString());

  }

  @Test
  @DisplayName("A failing check still reaches the caller, so the transaction can be aborted")
  public void aFailingCheckReachesTheCaller() {

    // a hook which runs the check right away, like the platform runners do at commit time
    final var service = serviceHandingChecksTo((
        aggregateClass,
        check) -> check.run());

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> service
            .completeTaskPhaseOne("mod", "Process", persistence(), new Object(), "task-gone"));

    assertTrue(failure.getMessage().contains("task-gone"), failure.getMessage());

  }

  @Test
  @DisplayName("Without an aggregate persistence the check runs immediately")
  public void withoutAPersistenceTheCheckRunsImmediately() {

    // a hook which never runs what it is handed: the check still has to reach the caller,
    // because there is no aggregate class to hang it on
    final var service = serviceHandingChecksTo((
        aggregateClass,
        check) -> {
    });

    assertThrows(
        IllegalStateException.class,
        () -> service.completeTaskPhaseOne("mod", "Process", null, new Object(), "task-gone"));

  }

}
