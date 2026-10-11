package io.vanillabp.pea.springboot.outbox;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of the deployed BPMN process {@code PeaTestProcess}. The test starts
 * a workflow through its {@link ProcessService}.
 */
@Service
@WorkflowService(
    workflowAggregateClass = Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "PeaTestProcess"))
public class SampleWorkflowService {

  private final ProcessService<Aggregate> processService;

  public SampleWorkflowService(
      final ProcessService<Aggregate> processService) {

    this.processService = processService;

  }

  public ProcessService<Aggregate> getProcessService() {

    return processService;

  }

}
