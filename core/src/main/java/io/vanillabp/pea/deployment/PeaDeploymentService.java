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
import io.vanillabp.integration.adapter.spi.AdapterPlatformVersion;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
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
// see decision 3 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class PeaDeploymentService implements AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext> {

  private final String adapterId;

  private final DeploymentApi deploymentApi;

  /**
   * The core's task-processing entry point: wiring validation during
   * {@link #wireBpmn} and task dispatch at runtime.
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * The runtime half of the split SPI. This service does not only wire: it opens the task
   * subscriptions at {@code startWorkflowProcessing}, and their handlers hand every
   * delivery to the core - so it holds both halves and passes this one on.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * The core's entry point for workflows which ended - used ONLY to warn
   * about methods this adapter cannot serve. May be <code>null</code> (tests).
   */
  private final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker;

  private final TaskSubscriptionApi taskSubscriptionApi;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  /**
   * What this application version deployed - the ONLY source of process
   * definitions and BPMN XML for the viewer API (the Process-Engine-API has no
   * repository API, see {@code GAPS.md}). Shared with the adapter id's
   * {@code PeaProcessService}.
   */
  private final PeaDeployedProcesses deployedProcesses;

  /**
   * Convenience constructor without a shared deployment record (tests) - the
   * service then records into an instance of its own.
   */
  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this(adapterId, deploymentApi, collaborators, taskSubscriptionApi, serviceTaskCompletionApi, new PeaDeployedProcesses());

  }

  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final PeaDeployedProcesses deployedProcesses) {

    AdapterPlatformVersion.requireCompatiblePlatform(PeaAdapter.ADAPTER_TYPE, PeaDeploymentService.class);

    this.adapterId = adapterId;
    this.deploymentApi = deploymentApi;
    this.collaborators = collaborators;
    this.workflowTaskWiring = collaborators.workflowTaskWiring();
    this.workflowTaskInvoker = collaborators.workflowTaskInvoker();
    this.workflowEndedInvoker = collaborators.workflowEndedInvoker().orElse(null);
    this.scoping = collaborators.scoping();
    this.taskSubscriptionApi = taskSubscriptionApi;
    this.serviceTaskCompletionApi = serviceTaskCompletionApi;
    this.deployedProcesses = deployedProcesses;

  }

  /**
   * The core's name-clash-avoidance model. The Process-Engine-API has no
   * isolation mechanism of its own, so only {@code none} and {@code use-prefix} can
   * be served - {@code by-adapter} (the default!) is rejected at startup with a
   * guiding message. May be <code>null</code> (tests).
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see
   * {@link io.vanillabp.integration.adapter.spi.AdapterCollaborators}).
   */
  private final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators;

  /**
   * Resolves whether a subscription asks for the DERIVED payload variables or for all of
   * them, supplied by the platform modules. May be <code>null</code> (tests):
   * the derived set applies.
   */
  private io.vanillabp.pea.wiring.PeaFetchVariablesResolver fetchVariablesResolver;

  /**
   * Sets the <code>fetch-variables</code> resolver (the platform modules construct this
   * service and inject it afterwards).
   *
   * @param fetchVariablesResolver The resolver, or <code>null</code> for the default
   */
  public void setFetchVariablesResolver(
      final io.vanillabp.pea.wiring.PeaFetchVariablesResolver fetchVariablesResolver) {

    this.fetchVariablesResolver = fetchVariablesResolver;

  }

  /**
   * One BPMN task a subscription serves - what its payload set is derived from.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param taskDefinition The PLAIN task definition (the external form reference for a
   *          user task)
   */
  record ServedTask(String bpmnProcessId,
                    String taskDefinition) {
  }

  /**
   * What one subscription asks the engine for: the union of the aggregate-ID
   * variables and the declared <code>&#64;TaskParam</code> names of everything it serves,
   * unless a level of the configuration says <code>all</code>.
   *
   * @param workflowModuleId The workflow module
   * @param served The tasks this subscription serves
   * @return The selection, never <code>null</code>
   */
  io.vanillabp.pea.wiring.PeaFetchVariables.Selection fetchVariablesOf(
      final String workflowModuleId,
      final List<ServedTask> served) {

    final var variables = new java.util.TreeSet<String>();
    for (final var task : served) {
      final var mode = io.vanillabp.pea.wiring.PeaFetchVariablesResolver
          .resolve(fetchVariablesResolver, workflowModuleId, task.bpmnProcessId(), task.taskDefinition());
      if (mode == io.vanillabp.pea.wiring.PeaFetchVariables.Mode.ALL) {
        // one subscription serves a task definition, so the two values cannot both
        // apply - and asking for more than derived is never wrong, only more expensive
        return io.vanillabp.pea.wiring.PeaFetchVariables.Selection.everything();
      }
      final String aggregateIdName;
      try {
        aggregateIdName = workflowTaskWiring
            .resolveWorkflowAggregateIdName(workflowModuleId, task.bpmnProcessId());
      } catch (final RuntimeException e) {
        log.debug(
            "Process-Engine-API adapter '{}': the BPMN process '{}' of workflow module '{}' has no "
                + "known workflow aggregate - its subscriptions ask for all payload variables",
            adapterId,
            task.bpmnProcessId(),
            workflowModuleId,
            e);
        return io.vanillabp.pea.wiring.PeaFetchVariables.Selection.everything();
      }
      variables.add(aggregateIdName);
      // what the handlers of this task read with @TaskParam: the core scanned those
      // names off the methods while wiring, and this adapter has no model to guess from
      variables
          .addAll(
              workflowTaskWiring
                  .taskParameterNames(workflowModuleId, task.bpmnProcessId(), task.taskDefinition()));
    }
    return io.vanillabp.pea.wiring.PeaFetchVariables.Selection.of(variables);

  }

  /**
   * A task definition as the engine knows it (prefixed under {@code use-prefix}).
   */
  private String scopedTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    return scoping == null
        ? taskDefinition
        : scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);

  }

  /**
   * Two <code>process-engine-api</code> adapter ids cannot address different
   * engines: the Process-Engine-API is provided by the APPLICATION as a
   * set of CDI/Spring beans - there is no per-adapter-id connection configuration,
   * so every configured id of this type ends up talking to the very same engine
   * beans. Configuring two of them is therefore a defect, not a migration setup
   * (see {@code GAPS.md}, entry 14).
   */
  @Override
  public void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

    if ((adapterIdsOfThisType == null) || (adapterIdsOfThisType.size() < 2)) {
      return;
    }
    throw new IllegalStateException(
        """
            The adapter ids '%s' are all of type '%s', but this adapter cannot address more than \
            ONE engine: the Process-Engine-API implementation is provided by the application as \
            beans (StartProcessApi, DeploymentApi, ...) and carries no per-adapter-id connection \
            configuration - all these ids would talk to the same engine, and the BPMS election \
            would ask it twice. Configure a single '%s' adapter id (a migration between two \
            engines behind the Process-Engine-API is not expressible - see the adapter's GAPS.md)."""
            .formatted(
                String.join("', '", adapterIdsOfThisType),
                PeaAdapter.ADAPTER_TYPE,
                PeaAdapter.ADAPTER_TYPE));

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
          new PeaBpmnModel(filename, resource, process.bpmnProcessId(), process.tasks(), process.userTasks())));
    }
    return result;

  }

  /**
   * One executable process parsed from a BPMN file: its id and its service-like
   * tasks (activity id + <code>zeebe:taskDefinition</code> type).
   */
  private record ParsedProcess(
                               String bpmnProcessId,
                               List<BpmnTaskSpec> tasks,
                               List<BpmnTaskSpec> userTasks) {
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
    final var BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    final var processes = new ArrayList<ParsedProcess>();
    ParsedProcess currentProcess = null;
    String currentTaskId = null;
    boolean currentTaskHasDefinition = false;
    String currentUserTaskId = null;
    boolean currentUserTaskHasFormReference = false;

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
                    ? new ParsedProcess(bpmnProcessId, new ArrayList<>(), new ArrayList<>())
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
          } else
            if ((currentProcess != null) && "userTask".equals(element) && BPMN_NS.equals(reader.getNamespaceURI())) {
              // namespace check: the marker extension <zeebe:userTask/> shares the
              // local name with the BPMN element
              currentUserTaskId = reader.getAttributeValue(null, "id");
              currentUserTaskHasFormReference = false;
            } else if ((currentUserTaskId != null) && "formDefinition".equals(element)) {
              // user tasks: the zeebe:formDefinition external reference
              // IS the task definition (Camunda-8-style convention); the handler is
              // OPTIONAL (notification only)
              final var externalReference = reader.getAttributeValue(null, "externalReference");
              if ((externalReference != null) && !externalReference.isBlank()) {
                currentProcess
                    .userTasks()
                    .add(BpmnTaskSpec.userTask(currentUserTaskId, externalReference));
                currentUserTaskHasFormReference = true;
              }
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
          } else
            if ("userTask".equals(element) && (currentUserTaskId != null) && BPMN_NS.equals(reader.getNamespaceURI())) {
              if (!currentUserTaskHasFormReference && (currentProcess != null)) {
                // a user task without an external form reference cannot be wired -
                // fine, it is processed through forms/task lists only (no spec)
                log.debug(
                    "User task '{}' of BPMN file '{}' has no external form reference - VanillaBP "
                        + "notifications are not available for it",
                    currentUserTaskId,
                    filename);
              }
              currentUserTaskId = null;
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
    // The deployed BYTES carry the scoped identifiers, while the model's
    // own bpmnProcessId/tasks stay PLAIN - they key the core's registries
    // (see decision 2 in the repository's DECISIONS.md)
    final var scopedResource = PeaScoping.apply(
        model.resource(), workflowModuleId, model.bpmnProcessId(), adapterId, scoping);
    context
        .getModels()
        .add(scopedResource == model.resource()
            ? model
            : new PeaBpmnModel(
                model.filename(), scopedResource, model.bpmnProcessId(), model.tasks(), model.userTasks()));
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
    final var specs = new ArrayList<BpmnTaskSpec>(model.tasks());
    specs.addAll(model.userTasks());
    workflowTaskWiring.validateTaskWiring(workflowModuleId, bpmnProcessId, specs);

    failOnBpmsInitiatedStartEvents(workflowModuleId, filename, bpmnProcessId, model);
    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, bpmnProcessId);

    log.info(
        "Process-Engine-API adapter '{}': wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        model.tasks().size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  /**
   * Fails the deployment of a process the ENGINE would start on its own (a timer,
   * signal or conditional start event). The Process-Engine-API has no way to tell an
   * application that its engine started a process (see {@code GAPS.md}), so such a
   * workflow would run without a workflow aggregate: no task could be routed, no
   * expression resolved. Failing the deployment is the honest answer - and it honors
   * the deployment-failure policy, so a non-first-priority adapter can degrade it to
   * a warning.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The BPMN file
   * @param bpmnProcessId The BPMN process ID
   * @param model The model, carrying the raw BPMN
   */
  private void failOnBpmsInitiatedStartEvents(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model) {

    final var startEvents = PeaStartEvents.bpmsInitiatedStartEventsOf(model.resource(), bpmnProcessId);
    if (startEvents.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            BPMN process '%s' (file '%s', workflow module '%s') is started by the engine itself (%s), \
            which the Process-Engine-API adapter cannot serve: the API does not report such a start, \
            so VanillaBP could never build the workflow aggregate the workflow needs. Start the \
            workflow from your application (ProcessService#startWorkflow, or a message start event \
            and ProcessService#startWorkflowByMessage), or run this workflow module on a BPMS whose \
            adapter supports it."""
            .formatted(bpmnProcessId, filename, workflowModuleId, String.join(", ", startEvents)));

  }

  /**
   * Warns about a <code>&#64;WorkflowEnded</code> method this adapter cannot serve.
   * The Process-Engine-API delivers TASKS; it has no event, subscription or callback
   * saying that a process instance ended (see {@code GAPS.md}), so the notification
   * never arrives.
   * <p>
   * Unlike a start event the engine fires on its own, this does NOT fail the
   * deployment: the workflow itself runs perfectly well, only the notification is
   * missing - failing the boot over it would be out of proportion. The warning names
   * what does not happen so nobody waits for it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   */
  private void warnAboutUnservedWorkflowEndedHandlers(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if ((workflowEndedInvoker == null) || !workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId)) {
      return;
    }
    log
        .warn(
            """
                A @WorkflowEnded method serves BPMN process '{}' of workflow module '{}', but the \
                Process-Engine-API adapter '{}' cannot report the end of a workflow: the API delivers \
                tasks and has no notification about a process instance which ended. The workflow runs \
                normally, the method is never called. Model an explicit task in front of the end \
                event if the application has to act there, or run this workflow module on a BPMS \
                whose adapter supports the notification.""",
            bpmnProcessId,
            workflowModuleId,
            adapterId);

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) throws IllegalStateException {

    // The Process-Engine-API has no isolation mechanism of its own, so the
    // DEFAULT mode 'by-adapter' cannot be served - fail with a guiding message
    // instead of silently deploying every workflow module into one scope
    if (scoping != null) {
      scoping.validateNativeIsolationSupported(adapterId, workflowModuleId, "the Process-Engine-API");
    }

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
      // remember what was deployed: the viewer API is served from these models -
      // the Process-Engine-API cannot be asked for definitions or BPMN XML
      bpmsProcessingContext
          .getModels()
          .forEach(model -> deployedProcesses
              .record(workflowModuleId, model, deploymentInformation.getDeploymentKey()));
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

    // This adapter registers no version catalog (the API cannot be asked
    // which versions of a process exist - GAPS.md 19), so this call only reports the
    // version tags the application names and nobody can resolve

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
    final var processesByTaskDefinition = new LinkedHashMap<String, List<ServedTask>>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> model
            .tasks()
            .stream()
            .filter(task -> task.taskDefinition() != null)
            .forEach(task -> processesByTaskDefinition
                .computeIfAbsent(
                    scopedTaskDefinition(workflowModuleId, model.bpmnProcessId(), task.taskDefinition()),
                    key -> new ArrayList<>())
                .add(new ServedTask(model.bpmnProcessId(), task.taskDefinition()))));

    // user-task notifications: one USER-type subscription per distinct
    // external form reference; the handler is a notification-only variant
    final var processesByUserTaskReference = new LinkedHashMap<String, List<ServedTask>>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> model
            .userTasks()
            .forEach(userTask -> processesByUserTaskReference
                .computeIfAbsent(
                    scopedTaskDefinition(workflowModuleId, model.bpmnProcessId(), userTask.taskDefinition()),
                    key -> new ArrayList<>())
                .add(new ServedTask(model.bpmnProcessId(), userTask.taskDefinition()))));
    processesByUserTaskReference.forEach((
        externalFormReference,
        served) -> {
      final var bpmnProcessIds = served
          .stream()
          .map(ServedTask::bpmnProcessId)
          .toList();
      // A user-task notification carries a payload too, so it is narrowed the
      // same way as a service task
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = new io.vanillabp.pea.wiring.PeaUserTaskHandler(
          adapterId, workflowModuleId, externalFormReference, List
              .copyOf(bpmnProcessIds), workflowTaskInvoker, scoping, fetchVariables);
      try {
        final var subscription = taskSubscriptionApi
            .subscribeForTask(new SubscribeForTaskCmd(
                Map.of(), TaskType.USER, externalFormReference, fetchVariables
                    .payloadDescription(), handler, (java.util.function.Consumer<String>) taskId -> log.debug(
                        "Process-Engine-API adapter '{}': user task '{}' terminated", adapterId, taskId)))
            .get();
        bpmsProcessingContext.getSubscriptions().add(subscription);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while subscribing for user tasks '%s'".formatted(externalFormReference), e);
      } catch (final ExecutionException e) {
        throw new IllegalStateException(
            "Could not subscribe for user tasks '%s' of workflow module '%s' (adapter '%s')!"
                .formatted(externalFormReference, workflowModuleId, adapterId), e.getCause());
      }
      log.info(
          "Process-Engine-API adapter '{}': subscribed for user tasks '{}' of workflow module '{}' "
              + "(asking for {})",
          adapterId,
          externalFormReference,
          workflowModuleId,
          fetchVariables.describe());
    });

    processesByTaskDefinition.forEach((
        taskDefinition,
        served) -> {
      final var bpmnProcessIds = served
          .stream()
          .map(ServedTask::bpmnProcessId)
          .toList();
      // What the delivered payload has to carry - the aggregate's ID and the
      // variables the handlers read, instead of everything the process instance holds
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = new PeaTaskHandler(
          adapterId, workflowModuleId, taskDefinition, List
              .copyOf(bpmnProcessIds), workflowTaskInvoker, serviceTaskCompletionApi, scoping, fetchVariables);
      try {
        final var subscription = taskSubscriptionApi
            .subscribeForTask(new SubscribeForTaskCmd(
                Map.of(), // no restrictions
                TaskType.EXTERNAL, taskDefinition, fetchVariables
                    .payloadDescription(), handler, (java.util.function.Consumer<String>) taskId -> log.debug(
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
          "Process-Engine-API adapter '{}': subscribed for task definition '{}' of workflow module "
              + "'{}' (asking for {})",
          adapterId,
          taskDefinition,
          workflowModuleId,
          fetchVariables.describe());
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
        // shutting down is what interrupts this thread, so stop trying - but the
        // engine keeps every subscription not yet given back and will deliver tasks
        // to an application which is going away, which nobody sees unless it is said
        log.warn(
            "Process-Engine-API adapter '{}': interrupted while unsubscribing from the tasks of "
                + "workflow module '{}' - {} subscription(s) stay open in the engine and their "
                + "deliveries are lost until it drops them",
            adapterId,
            workflowModuleId,
            i + 1);
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
