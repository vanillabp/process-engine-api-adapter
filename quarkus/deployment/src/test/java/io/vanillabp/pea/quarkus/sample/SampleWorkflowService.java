package io.vanillabp.pea.quarkus.sample;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService implements AggregatePersistenceAware<Aggregate> {

  @Inject
  ProcessService<Aggregate> processService;

  @Override
  public Class<Aggregate> getAggregateClass() {
    return Aggregate.class;
  }

  @Override
  public Aggregate save(
      final Aggregate aggregate) {
    return null; // not necessary for this test
  }

  @Override
  public Object getAggregateId(
      final Aggregate aggregate) {
    return null; // not necessary for this test
  }

  @WorkflowTask
  public void doSomething() {
    // not exercised by the skeleton smoke test
  }

}
