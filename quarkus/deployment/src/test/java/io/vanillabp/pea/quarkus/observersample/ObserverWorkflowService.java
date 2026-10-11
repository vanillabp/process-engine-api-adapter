package io.vanillabp.pea.quarkus.observersample;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

/**
 * The workflow service of the Quarkus test of the user-task observers. It has one
 * notification handler for the claimed user task. It also has a small store in memory, which
 * copies aggregates on save and on load. The second user task of the BPMN has no method here,
 * on purpose. An observer sees it anyway, and the configuration marks it with
 * {@code implemented-externally}.
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

  /**
   * The method of the extension for the unclaimed user task. It reads a process variable which
   * no <code>&#64;WorkflowTask</code> method reads.
   *
   * @param shown The process variable only this method reads
   */
  @Glimpse(element = "t_unclaimed")
  public void glimpseOfTheUnclaimedTask(
      @TaskParam("cockpitOnly") final String shown) {
  }

}
