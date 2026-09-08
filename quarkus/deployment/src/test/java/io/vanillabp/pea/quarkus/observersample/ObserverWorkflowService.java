package io.vanillabp.pea.quarkus.observersample;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

/**
 * Workflow service of the Quarkus user-task observer test: one notification handler for the
 * claimed user task plus an inline in-memory persistence copying aggregates on save/load. The
 * second user task of the BPMN has no method here on purpose - an observer sees it anyway.
 */
@Singleton
@WorkflowService(
    workflowAggregateClass = ObserverAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QuarkusObserverProcess"))
public class ObserverWorkflowService implements AggregatePersistenceAware<ObserverAggregate> {

  public static final Map<String, ObserverAggregate> AGGREGATES = new ConcurrentHashMap<>();

  private static ObserverAggregate copyOf(
      final ObserverAggregate aggregate) {

    final var copy = new ObserverAggregate();
    copy.id = aggregate.id;
    copy.results = aggregate.results;
    return copy;

  }

  @Override
  public Class<ObserverAggregate> getAggregateClass() {

    return ObserverAggregate.class;

  }

  @Override
  public ObserverAggregate save(
      final ObserverAggregate aggregate) {

    AGGREGATES.put(aggregate.id, copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final ObserverAggregate aggregate) {

    return aggregate.id;

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Class<?> getAggregateIdType() {

    return String.class;

  }

  @Override
  public ObserverAggregate loadById(
      final Object aggregateId) {

    final var stored = AGGREGATES.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  @WorkflowTask(taskDefinition = "quarkusObserved")
  public void quarkusObservedNotification(
      final ObserverAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.results = "notified:"
        + taskId;

  }

}
