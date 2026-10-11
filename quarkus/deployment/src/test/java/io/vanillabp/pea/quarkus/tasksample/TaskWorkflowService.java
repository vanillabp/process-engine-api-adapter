package io.vanillabp.pea.quarkus.tasksample;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The workflow service of the Quarkus smoke test of task processing. It has one handler for
 * the normal path. It also has a small store in memory, which copies aggregates on save and
 * on load. Only saved state is kept, so a test can see what was committed.
 */
@Singleton
@WorkflowService(
    workflowAggregateClass = TaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QuarkusTaskProcess"))
public class TaskWorkflowService implements AggregatePersistenceAware<TaskAggregate> {

  public static final Map<String, TaskAggregate> AGGREGATES = new ConcurrentHashMap<>();

  @Inject
  ProcessService<TaskAggregate> processService;

  private static TaskAggregate copyOf(
      final TaskAggregate aggregate) {

    final var copy = new TaskAggregate();
    copy.id = aggregate.id;
    copy.results = aggregate.results;
    return copy;

  }

  @Override
  public Class<TaskAggregate> getAggregateClass() {

    return TaskAggregate.class;

  }

  @Override
  public TaskAggregate save(
      final TaskAggregate aggregate) {

    AGGREGATES.put(aggregate.id, copyOf(aggregate));
    return aggregate;

  }

  @Override
  public Object getAggregateId(
      final TaskAggregate aggregate) {

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
  public TaskAggregate loadById(
      final Object aggregateId) {

    final var stored = AGGREGATES.get(aggregateId);
    return stored != null
        ? copyOf(stored)
        : null;

  }

  @WorkflowTask
  public void quarkusHappy(
      final TaskAggregate aggregate) {

    aggregate.appendResult("happy");

  }

}
