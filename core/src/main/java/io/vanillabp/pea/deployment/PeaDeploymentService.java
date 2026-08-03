package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import dev.bpmcrafters.processengineapi.deploy.DeployBundleCommand;
import dev.bpmcrafters.processengineapi.deploy.DeploymentApi;
import dev.bpmcrafters.processengineapi.deploy.NamedResource;
import dev.bpmcrafters.processengineapi.task.ServiceTaskCompletionApi;
import dev.bpmcrafters.processengineapi.task.SubscribeForTaskCmd;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.TaskType;
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * The Process-Engine-API adapter's deployment service - one instance per configured
 * adapter id (not per type).
 * <p>
 * The Process-Engine-API has no BPMN model type (its {@code DeploymentApi} deploys opaque
 * {@code NamedResource}s), so {@link #readBpmn} parses the BPMN XML itself - just far enough
 * to extract the executable process ids - using the JDK's StAX streaming parser
 * ({@code javax.xml.stream}). See {@code GAPS.md}.
 * <p>
 * The {@link DeploymentApi} is injected via the constructor so the platform modules provide
 * the implementation (by default the in-memory mock, later a real Process-Engine-API
 * implementation).
 */
@Slf4j
public class PeaDeploymentService implements AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext> {

  private final String adapterId;

  private final DeploymentApi deploymentApi;

  /**
   * The core's task-processing entry point: wiring validation during
   * {@link #wireBpmn} and task dispatch at runtime.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final TaskSubscriptionApi taskSubscriptionApi;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this.adapterId = adapterId;
    this.deploymentApi = deploymentApi;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskSubscriptionApi = taskSubscriptionApi;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return PeaAdapter.ADAPTER_TYPE;

  }

  @Override
  public Class<PeaBpmnModel> getModelType() {

    return PeaBpmnModel.class;

  }

  @Override
  public Class<PeaProcessingContext> getProcessContextType() {

    return PeaProcessingContext.class;

  }

  @Override
  public List<Map.Entry<String, PeaBpmnModel>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    final byte[] resource;
    try {
      resource = bpmn.readAllBytes();
    } catch (final IOException e) {
      throw new BpmnParseException(
          "Could not read BPMN file '%s' of workflow module '%s'".formatted(filename, workflowModuleId), e);
    }

    final var result = new ArrayList<Map.Entry<String, PeaBpmnModel>>();
    final var parsed = parseBpmn(workflowModuleId, filename, resource);
    for (final var process : parsed) {
      result.add(Map.entry(
          process.bpmnProcessId(),
          new PeaBpmnModel(filename, resource, process.bpmnProcessId(), process.tasks())));
    }
    return result;

  }

  /**
   * One executable process parsed from a BPMN file: its id and its service-like
   * tasks (activity id + <code>zeebe:taskDefinition</code> type).
   */
  private record ParsedProcess(
                               String bpmnProcessId,
                               List<BpmnTaskSpec> tasks) {
  }

  /**
   * Streams over the BPMN XML with StAX and collects all
   * {@code <bpmn:process isExecutable="true">} elements together with their
   * service-like tasks. StAX is used (instead of building a DOM or depending on a
   * BPMS-specific model API) because the Process-Engine-API has no BPMN model type.
   * The task definition is read from the <code>zeebe:taskDefinition</code>
   * extension (Camunda-8-style - the Process-Engine-API does not define how BPMN
   * names task definitions, see {@code GAPS.md}); a task without one is reported
   * by the wiring validation.
   *
   * @param workflowModuleId The workflow module id (used for error messages)
   * @param filename The BPMN filename (used for error messages)
   * @param resource The raw BPMN XML bytes
   * @return The executable processes contained in the resource
   * @throws BpmnParseException If the XML cannot be parsed
   */
  private List<ParsedProcess> parseBpmn(
      final String workflowModuleId,
      final String filename,
      final byte[] resource) throws BpmnParseException {

    final var factory = XMLInputFactory.newFactory();
    // harden the parser: no external entities, no DTDs (defence against XXE)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

    final var serviceLikeTasks = java.util.Set.of("serviceTask", "sendTask", "businessRuleTask", "scriptTask");

    final var processes = new ArrayList<ParsedProcess>();
    ParsedProcess currentProcess = null;
    String currentTaskId = null;
    boolean currentTaskHasDefinition = false;

    XMLStreamReader reader = null;
    try (var in = new ByteArrayInputStream(resource)) {
      reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        final var event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          final var element = reader.getLocalName();
          if ("process".equals(element)) {
            final var bpmnProcessId = reader.getAttributeValue(null, "id");
            currentProcess = Boolean.parseBoolean(
                reader.getAttributeValue(null, "isExecutable")) && (bpmnProcessId != null) && !bpmnProcessId.isBlank()
                    ? new ParsedProcess(bpmnProcessId, new ArrayList<>())
                    : null;
            if (currentProcess != null) {
              processes.add(currentProcess);
            }
          } else if ((currentProcess != null) && serviceLikeTasks.contains(element)) {
            currentTaskId = reader.getAttributeValue(null, "id");
            currentTaskHasDefinition = false;
          } else if ((currentTaskId != null) && "taskDefinition".equals(element)) {
            currentProcess
                .tasks()
                .add(new BpmnTaskSpec(currentTaskId, reader.getAttributeValue(null, "type")));
            currentTaskHasDefinition = true;
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          final var element = reader.getLocalName();
          if (serviceLikeTasks.contains(element) && (currentTaskId != null)) {
            if (!currentTaskHasDefinition && (currentProcess != null)) {
              // no zeebe:taskDefinition: reported by the wiring validation
              currentProcess
                  .tasks()
                  .add(new BpmnTaskSpec(currentTaskId, null));
            }
            currentTaskId = null;
          } else if ("process".equals(element)) {
            currentProcess = null;
          }
        }
      }
      return processes;
    } catch (final XMLStreamException | IOException e) {
      throw new BpmnParseException(
          "Could not parse BPMN file '%s' of workflow module '%s'".formatted(filename, workflowModuleId), e);
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (final XMLStreamException e) {
          // ignore: closing the reader over an in-memory byte array cannot fail meaningfully
        }
      }
    }

  }

  @Override
  public PeaProcessingContext prepareBpmn(
      final String workflowModuleId,
      final PeaProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model) {

    final var context = existingContext == null
        ? new PeaProcessingContext(workflowModuleId)
        : existingContext;
    context.getModels().add(model);
    return context;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model,
      final PeaProcessingContext context) {

    // validate the BPMN's tasks against the registered @WorkflowTask methods;
    // throwing here honors the deployment-failure policy
    workflowTaskInvoker.validateTaskWiring(workflowModuleId, bpmnProcessId, model.tasks());

    log.info(
        "Process-Engine-API adapter '{}': wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        model.tasks().size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) throws IllegalStateException {

    if ((bpmsProcessingContext == null) || bpmsProcessingContext.getModels().isEmpty()) {
      log.info(
          "Process-Engine-API adapter '{}': no executable BPMN processes found for workflow module '{}' - nothing to deploy",
          adapterId,
          workflowModuleId);
      return;
    }

    // A BPMN file may contain several executable processes, so the same resource can show
    // up multiple times in the context - deploy each file (by name) exactly once.
    final var resourcesByFilename = new LinkedHashMap<String, byte[]>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> resourcesByFilename.putIfAbsent(model.filename(), model.resource()));
    final var resources = resourcesByFilename
        .entrySet()
        .stream()
        .map(entry -> new NamedResource(entry.getKey(), new ByteArrayInputStream(entry.getValue()), Map.of()))
        .toList();

    // The Process-Engine-API's DeployBundleCommand has an optional tenantId, but a PEA
    // tenant is the underlying BPMS' multi-tenancy - not a VanillaBP workflow-module
    // namespace. Deploying "for workflow module X" (Camunda-7-style module-as-tenant
    // isolation) is therefore not expressible; the bundle is deployed to the default
    // tenant. See GAPS.md.
    final var command = new DeployBundleCommand(resources, null);
    try {
      final var deploymentInformation = deploymentApi
          .deploy(command)
          .get();
      log.info(
          "Process-Engine-API adapter '{}': deployed {} BPMN file(s) of workflow module '{}' (deployment '{}')",
          adapterId,
          resources.size(),
          workflowModuleId,
          deploymentInformation.getDeploymentKey());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while deploying resources of workflow module '%s'".formatted(workflowModuleId), e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException(
          "Deployment of resources of workflow module '%s' failed".formatted(workflowModuleId), e.getCause());
    }


    // after ALL processes of the module were wired: methods matching no task of
    // any wired process are a defect (per-module check, honors the policy)
    workflowTaskInvoker.validateNoUnwiredWorkflowTaskMethods(workflowModuleId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    if (bpmsProcessingContext == null) {
      return;
    }

    // one task subscription per DISTINCT task definition of the module; the task
    // handler dispatches through the core's WorkflowTaskInvoker. The BPMN process
    // a delivered task belongs to travels in TaskInformation.meta (adapter
    // convention key 'bpmnProcessId' - see GAPS.md); if absent, the task
    // definition has to be unique across the module's processes
    final var processesByTaskDefinition = new LinkedHashMap<String, List<String>>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> model
            .tasks()
            .stream()
            .filter(task -> task.taskDefinition() != null)
            .forEach(task -> processesByTaskDefinition
                .computeIfAbsent(task.taskDefinition(), key -> new ArrayList<>())
                .add(model.bpmnProcessId())));

    processesByTaskDefinition.forEach((
        taskDefinition,
        bpmnProcessIds) -> {
      final var handler = new PeaTaskHandler(
          adapterId, workflowModuleId, taskDefinition, List
              .copyOf(bpmnProcessIds), workflowTaskInvoker, serviceTaskCompletionApi);
      try {
        final var subscription = taskSubscriptionApi
            .subscribeForTask(new SubscribeForTaskCmd(
                Map.of(), // no restrictions
                TaskType.EXTERNAL, taskDefinition, java.util.Set.of(), // all payload variables
                handler, (java.util.function.Consumer<String>) taskId -> log.debug(
                    "Process-Engine-API adapter '{}': task '{}' terminated", adapterId, taskId)))
            .get();
        bpmsProcessingContext.getSubscriptions().add(subscription);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while subscribing for task definition '%s'".formatted(taskDefinition), e);
      } catch (final ExecutionException e) {
        throw new IllegalStateException(
            "Could not subscribe for task definition '%s' of workflow module '%s' (adapter '%s')!"
                .formatted(taskDefinition, workflowModuleId, adapterId), e.getCause());
      }
      log.info(
          "Process-Engine-API adapter '{}': subscribed for task definition '{}' of workflow module '{}'",
          adapterId,
          taskDefinition,
          workflowModuleId);
    });

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    if (bpmsProcessingContext == null) {
      return;
    }
    // unsubscribe in reverse order (graceful shutdown parity with the pipeline)
    final var subscriptions = bpmsProcessingContext.getSubscriptions();
    for (var i = subscriptions.size() - 1; i >= 0; --i) {
      try {
        taskSubscriptionApi
            .unsubscribe(new UnsubscribeFromTaskCmd(subscriptions.get(i)))
            .get();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final ExecutionException e) {
        log.warn(
            "Process-Engine-API adapter '{}': could not unsubscribe a task subscription of workflow module '{}'",
            adapterId,
            workflowModuleId,
            e.getCause());
      }
    }
    subscriptions.clear();
    log.info(
        "Process-Engine-API adapter '{}': stopped workflow processing of workflow module '{}'",
        adapterId,
        workflowModuleId);

  }

}
