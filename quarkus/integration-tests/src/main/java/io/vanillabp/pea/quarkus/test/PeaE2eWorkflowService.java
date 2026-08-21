package io.vanillabp.pea.quarkus.test;

import java.io.InputStream;
import java.util.List;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the Quarkus end-to-end application - the same shape the
 * Spring Boot suite drives, so both platforms run the identical set of documented
 * features against the in-memory mock engine.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = PeaE2eAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = PeaE2eWorkflowService.PROCESS),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "PeaE2eMessageProcess"), @BpmnProcess(
            bpmnProcessId = "PeaE2eMessageStartProcess")
    })
public class PeaE2eWorkflowService {

  public static final String PROCESS = "PeaE2eProcess";

  @Inject
  ProcessService<PeaE2eAggregate> processService;

  public PeaE2eAggregate startWorkflow(
      final String id) {

    final var aggregate = new PeaE2eAggregate();
    aggregate.setId(id);
    return processService.startWorkflow(aggregate);

  }

  public PeaE2eAggregate completeTask(
      final PeaE2eAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public PeaE2eAggregate cancelTask(
      final PeaE2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  public PeaE2eAggregate completeUserTask(
      final PeaE2eAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public PeaE2eAggregate cancelUserTask(
      final PeaE2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }

  public PeaE2eAggregate correlateMessage(
      final PeaE2eAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public PeaE2eAggregate correlateMessage(
      final PeaE2eAggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public PeaE2eAggregate startWorkflowByMessage(
      final PeaE2eAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  public void sendSignal(
      final String signalName) {

    processService.sendSignal(signalName);

  }

  public PeaE2eAggregate aggregateChanged(
      final PeaE2eAggregate aggregate) {

    return processService.aggregateChanged(aggregate);

  }

  public PeaE2eAggregate aggregateChanged(
      final PeaE2eAggregate aggregate,
      final String taskId) {

    return processService.aggregateChanged(aggregate, taskId);

  }

  public List<ProcessDefinition> getProcessDefinitions(
      final PeaE2eAggregate aggregate,
      final String historyContext) {

    return processService.getProcessDefinitions(aggregate, historyContext);

  }

  public InputStream getBpmnXml(
      final String processDefinitionId) {

    return processService.getBpmnXml(processDefinitionId);

  }

  public WorkflowHistory getWorkflowHistory(
      final PeaE2eAggregate aggregate) {

    return processService.getWorkflowHistory(aggregate, null);

  }

  public String getWorkflowModuleId() {

    return processService.getWorkflowModuleId();

  }

  @WorkflowTask
  public void e2eHappy(
      final PeaE2eAggregate aggregate) {

    // idempotent: keyed on aggregate state, not on call count
    if ((aggregate.getResults() == null) || !aggregate.getResults().contains("happy")) {
      aggregate.appendResult("happy");
    }
    aggregate.setSecret("s3cr3t");

  }

  @WorkflowTask
  public void e2eError(
      final PeaE2eAggregate aggregate) {

    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void e2eFails(
      final PeaE2eAggregate aggregate) {

    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-pea-quarkus");

  }

  @WorkflowTask
  public void e2eAsync(
      final PeaE2eAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  @WorkflowTask(taskDefinition = "e2eApprove")
  public void e2eApproveNotification(
      final PeaE2eAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("usertask-"
        + event.name().toLowerCase());

  }

}
