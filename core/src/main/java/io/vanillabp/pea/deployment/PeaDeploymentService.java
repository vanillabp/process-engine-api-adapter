package io.vanillabp.pea.deployment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import dev.bpmcrafters.processengineapi.task.TaskInformation;
import dev.bpmcrafters.processengineapi.task.TaskSubscriptionApi;
import dev.bpmcrafters.processengineapi.task.TaskTerminationHandler;
import dev.bpmcrafters.processengineapi.task.TaskType;
import dev.bpmcrafters.processengineapi.task.UnsubscribeFromTaskCmd;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterPlatformVersion;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObservers;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaFetchVariablesResolver;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;
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
  private final WorkflowEndedInvoker workflowEndedInvoker;

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
      final AdapterCollaborators collaborators,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this(adapterId, deploymentApi, collaborators, taskSubscriptionApi, serviceTaskCompletionApi, new PeaDeployedProcesses());

  }

  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final AdapterCollaborators collaborators,
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
    this.userTaskObservers = PeaUserTaskObservers.of(adapterId, List.of());

  }

  /**
   * The core's name-clash-avoidance model. The Process-Engine-API has no
   * isolation mechanism of its own, so only {@code none} and {@code use-prefix} can
   * be served - {@code by-adapter} (the default!) is rejected at startup with a
   * guiding message. May be <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * Resolves whether a subscription asks for the DERIVED payload variables or for all of
   * them, supplied by the platform modules. May be <code>null</code> (tests):
   * the derived set applies.
   */
  private PeaFetchVariablesResolver fetchVariablesResolver;

  /**
   * Who watches the user tasks this adapter is delivered - hook beans of the application,
   * collected by the platform modules. Empty unless something registered one, and then this
   * service behaves exactly as it did before the seam existed.
   */
  private PeaUserTaskObservers userTaskObservers;

  /**
   * Sets the <code>fetch-variables</code> resolver (the platform modules construct this
   * service and inject it afterwards).
   *
   * @param fetchVariablesResolver The resolver, or <code>null</code> for the default
   */
  public void setFetchVariablesResolver(
      final PeaFetchVariablesResolver fetchVariablesResolver) {

    this.fetchVariablesResolver = fetchVariablesResolver;

  }

  /**
   * Sets who watches this adapter's user-task deliveries (the platform modules collect the
   * beans of the application and inject them after construction, like the resolver above).
   *
   * @param userTaskObservers The observers in the order the platform resolved them, or
   *          <code>null</code> for none
   */
  public void setUserTaskObservers(
      final List<PeaUserTaskObserver> userTaskObservers) {

    this.userTaskObservers = PeaUserTaskObservers.of(adapterId, userTaskObservers);

  }

  /**
   * One BPMN task a subscription serves - what its payload set is derived from.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param taskDefinition The PLAIN task definition (the external form reference for a
   *          user task)
   * @param declaredWithoutAModel Whether the process is one the module DECLARES without
   *          deploying anything under it - the old id of a renamed process. Such a task
   *          comes from what the application's methods serve rather than from a model
   *          (see decision 11 in the repository's DECISIONS.md)
   */
  record ServedTask(String bpmnProcessId,
                    String taskDefinition,
                    boolean declaredWithoutAModel) {

    static ServedTask ofADeployedModel(
        final String bpmnProcessId,
        final String taskDefinition) {

      return new ServedTask(bpmnProcessId, taskDefinition, false);

    }

    static ServedTask ofADeclaredId(
        final String bpmnProcessId,
        final String taskDefinition) {

      return new ServedTask(bpmnProcessId, taskDefinition, true);

    }

  }

  /**
   * The processes a subscription may deliver from, told apart by whether this application
   * deployed a model for them.
   *
   * @param deployed The processes of the deployed models, which is what a delivery
   *          without the {@code bpmnProcessId} meta entry can be routed to
   * @param declaredWithoutAModel The ids the module only declares, which a delivery
   *          reaches only where the name carries the process id or the engine names the
   *          process itself
   */
  private record RoutableProcesses(List<String> deployed,
                                   List<String> declaredWithoutAModel) {

    static RoutableProcesses of(
        final List<ServedTask> served) {

      return new RoutableProcesses(
          idsOf(served, false), idsOf(served, true));

    }

    private static List<String> idsOf(
        final List<ServedTask> served,
        final boolean declaredWithoutAModel) {

      return served
          .stream()
          .filter(task -> task.declaredWithoutAModel() == declaredWithoutAModel)
          .map(ServedTask::bpmnProcessId)
          .distinct()
          .toList();

    }

    /**
     * What a delivery which names no process may belong to: the deployed processes, or
     * the declared id where this subscription was opened for that id alone.
     *
     * @return The plain BPMN process ids, never empty
     */
    List<String> routingCandidates() {

      return deployed.isEmpty()
          ? declaredWithoutAModel
          : deployed;

    }

    /**
     * The declared ids a routing failure has to name as a third possibility: they share
     * this subscription with deployed processes, so a delivery may belong to a workflow
     * still running under the old id of a renamed process.
     *
     * @return The plain BPMN process ids, empty where there is nothing to add
     */
    List<String> declaredSharingTheName() {

      return deployed.isEmpty()
          ? List.of()
          : declaredWithoutAModel;

    }

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
  PeaFetchVariables.Selection fetchVariablesOf(
      final String workflowModuleId,
      final List<ServedTask> served) {

    final var variables = new TreeSet<String>();
    for (final var task : served) {
      final var mode = PeaFetchVariablesResolver
          .resolve(fetchVariablesResolver, workflowModuleId, task.bpmnProcessId(), task.taskDefinition());
      if (mode == PeaFetchVariables.Mode.ALL) {
        // one subscription serves a task definition, so the two values cannot both
        // apply - and asking for more than derived is never wrong, only more expensive
        return PeaFetchVariables.Selection.everything();
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
        return PeaFetchVariables.Selection.everything();
      }
      variables.add(aggregateIdName);
      // what the handlers of this task read with @TaskParam: the core scanned those
      // names off the methods while wiring, and this adapter has no model to guess from
      variables
          .addAll(
              workflowTaskWiring
                  .taskParameterNames(workflowModuleId, task.bpmnProcessId(), task.taskDefinition()));
    }
    return PeaFetchVariables.Selection.of(variables);

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

  /**
   * Nothing is kept apart here. There is no tenant and no namespace behind this API, so every
   * workflow module reaches one engine in one scope (see {@code GAPS.md}, entry 15).
   * <p>
   * The core asks this while it judges whether two BPMN processes reach the BPMS under one
   * identifier, and it asks only where the mode leaves the identifiers plain and the BPMS is
   * meant to do the separating. This adapter refuses that mode while deploying, so the
   * question rarely gets here, and the answer would be the same one if it did.
   * <p>
   * The inherited default answers <code>false</code> as well, so this override changes no
   * behaviour. It is here to put the answer and its reason into the adapter the gap belongs
   * to, where a reader of this adapter looks for it. That is its whole job, so do not drop it
   * as redundant.
   */
  @Override
  public boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return false;

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
          new PeaBpmnModel(
              filename, resource, process.bpmnProcessId(), process.processName(), process.tasks(), process
                  .userTasks())));
    }
    return result;

  }

  /**
   * One executable process parsed from a BPMN file: its id, the name a modeller wrote
   * on it and its service-like tasks (activity id +
   * <code>zeebe:taskDefinition</code> type).
   */
  private record ParsedProcess(
                               String bpmnProcessId,
                               String processName,
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
  /**
   * Whether the element the reader stands on carries an attribute of the given local
   * name, whatever namespace it is in - the reference to a decision is spelled
   * differently per engine, and this parser reads raw XML rather than a model.
   *
   * @param reader The reader, standing on a start element
   * @param localName The attribute's local name
   * @return Whether it is there and holds something
   */
  private static boolean hasAttribute(
      final XMLStreamReader reader,
      final String localName) {

    for (var i = 0; i < reader.getAttributeCount(); i++) {
      if (localName.equals(reader.getAttributeLocalName(i))) {
        final var value = reader.getAttributeValue(i);
        return (value != null) && !value.isBlank();
      }
    }
    return false;

  }

  /**
   * An attribute a modeller left out and one they left empty are the same thing, and
   * <code>null</code> is what the adapter SPI says for "none".
   *
   * @param value The attribute value, or <code>null</code>
   * @return The value, or <code>null</code> where there is nothing in it
   */
  private static String blankToNull(
      final String value) {

    return (value == null) || value.isBlank()
        ? null
        : value;

  }

  private List<ParsedProcess> parseBpmn(
      final String workflowModuleId,
      final String filename,
      final byte[] resource) throws BpmnParseException {

    final var factory = XMLInputFactory.newFactory();
    // harden the parser: no external entities, no DTDs (defence against XXE)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

    final var serviceLikeTasks = Set.of("serviceTask", "sendTask", "businessRuleTask", "scriptTask");
    final var BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    final var processes = new ArrayList<ParsedProcess>();
    ParsedProcess currentProcess = null;
    String currentTaskId = null;
    boolean currentTaskHasDefinition = false;
    boolean currentTaskCallsADecision = false;
    String currentUserTaskId = null;
    String currentUserTaskName = null;
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
                    ? new ParsedProcess(
                        bpmnProcessId, blankToNull(
                            reader.getAttributeValue(null, "name")), new ArrayList<>(), new ArrayList<>())
                    : null;
            if (currentProcess != null) {
              processes.add(currentProcess);
            }
          } else if ((currentProcess != null) && serviceLikeTasks.contains(element)) {
            currentTaskId = reader.getAttributeValue(null, "id");
            currentTaskHasDefinition = false;
            // a business rule task naming a decision is served by the ENGINE, whichever
            // way the engine spells that reference - it expects no @WorkflowTask method
            currentTaskCallsADecision = hasAttribute(reader, "decisionRef");
          } else if ((currentTaskId != null) && "calledDecision".equals(element)) {
            currentTaskCallsADecision = true;
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
              // what the modeller wrote on the element: the only human-readable name a task
              // list has for this task, since the engine reports none of its own
              currentUserTaskName = blankToNull(reader.getAttributeValue(null, "name"));
              currentUserTaskHasFormReference = false;
            } else if ((currentUserTaskId != null) && "formDefinition".equals(element)) {
              // user tasks: the zeebe:formDefinition external reference
              // IS the task definition (Camunda-8-style convention); the handler is
              // OPTIONAL (notification only)
              final var externalReference = reader.getAttributeValue(null, "externalReference");
              if ((externalReference != null) && !externalReference.isBlank()) {
                currentProcess
                    .userTasks()
                    .add(BpmnTaskSpec.userTask(currentUserTaskId, externalReference, currentUserTaskName));
                currentUserTaskHasFormReference = true;
              }
            }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          final var element = reader.getLocalName();
          if (serviceLikeTasks.contains(element) && (currentTaskId != null)) {
            if (!currentTaskHasDefinition && !currentTaskCallsADecision && (currentProcess != null)) {
              // no zeebe:taskDefinition: reported by the wiring validation
              currentProcess
                  .tasks()
                  .add(new BpmnTaskSpec(currentTaskId, null));
            }
            currentTaskId = null;
            currentTaskCallsADecision = false;
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
              currentUserTaskName = null;
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
    // read what this model declares while the PLAIN bytes are at hand - the rewrite below
    // hands the engine prefixed ones, and the core wants the names the application wrote.
    // Read in EVERY mode: the clash the core looks for needs two modules whose names reach
    // the engine in one form, which is what the mode 'none' does, so collecting only while
    // prefixing would look exactly where there is nothing to find
    context
        .getDeclaredIdentifiers()
        .addAll(PeaDeclaredIdentifiers.of(model));
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
                model.filename(), scopedResource, model.bpmnProcessId(), model.processName(), model.tasks(), model
                    .userTasks()));
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
    reportTheMissingVersionCatalog(workflowModuleId, bpmnProcessId);

    log.info(
        "Process-Engine-API adapter '{}': wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        model.tasks().size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  /**
   * Tells the core that this BPMS keeps no catalog of the versions of a process, and
   * what a delivery carries instead.
   * <p>
   * The Process-Engine-API has no version notion (see entries 19 and 20 of
   * <code>GAPS.md</code>): no numeric version, no deployment order, no way to ask which
   * versions of a process the engine still holds. What a delivered task may carry is the
   * version tag of its process definition, in the <code>meta</code> map and only where the
   * engine behind the API fills it, which is why the answer is
   * {@link ReportedProcessVersion#VERSION_TAG}.
   * <p>
   * Registering no catalog would say the same thing to a machine and something else to a
   * person. It is what the core sees before an adapter was asked, so it kept quiet about
   * the methods of such a process and its messages about a version spoke of a BPMS which
   * could not be reached. Saying it lets the core name, while the application boots, the
   * methods whose version a delivery here can never meet.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   */
  private void reportTheMissingVersionCatalog(
      final String workflowModuleId,
      final String bpmnProcessId) {

    workflowTaskWiring
        .reportNoProcessVersionCatalog(
            adapterId,
            workflowModuleId,
            bpmnProcessId,
            ReportedProcessVersion.VERSION_TAG);

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

  /**
   * What one BPMN process id the module declares WITHOUT deploying a model under it got
   * while the subscriptions were composed.
   *
   * @param bpmnProcessId The plain BPMN process id nothing was deployed under
   * @param taskDefinitions What the application's methods serve for it, possibly empty
   * @param openedNames The subscription keys which exist because of this id
   * @param sharedNames The subscription keys a deployed process already reaches
   */
  private record DeclaredId(String bpmnProcessId,
                            Collection<String> taskDefinitions,
                            Set<String> openedNames,
                            Set<String> sharedNames) {
  }

  /**
   * Adds the subscriptions which reach the workflows of a BPMN process id the application
   * DECLARES without deploying a model under it - the old id of a renamed process.
   * <p>
   * A subscription asks for a task definition, and under {@code use-prefix} a task
   * definition carries the id of the process it was deployed with: the tasks of the
   * workflows under the old id are named after the OLD id, so no subscription of the
   * deployed processes asks for them and nobody notices, because an unfetched task is not
   * a failed one. What those workflows need is one more subscription per name they
   * produce, and the names are composed the same way the deployed ones were - the task
   * definitions the application serves for that id, scoped by it. Nothing is read from the
   * engine, which is what makes this possible at all here (see decision 11 in the
   * repository's DECISIONS.md).
   * <p>
   * Where a name is already served nothing is added as a subscription of its own, which is
   * every mode but {@code use-prefix} and {@code use-prefix} with
   * {@code prefix-task-definitions-per-process: false}. The declared id is still added to
   * that subscription, so its <code>&#64;TaskParam</code> names reach the payload the
   * subscription asks for and a delivery which cannot be routed says that an old id is a
   * possibility. It is NOT added as a routing candidate: a delivery over a shared name
   * would then be ambiguous for every workflow, including the ones which are served
   * correctly today.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   * @param processesByTaskDefinition The service-task subscriptions, added to
   * @param processesByUserTaskReference The user-task subscriptions, added to
   * @return What each declared id got, in the order the core named them
   */
  private List<DeclaredId> composeTheSubscriptionsOfProcessesNobodyDeployed(
      final String workflowModuleId,
      final Map<String, List<ServedTask>> processesByTaskDefinition,
      final Map<String, List<ServedTask>> processesByUserTaskReference) {

    final var declaredIds = new ArrayList<DeclaredId>();
    workflowTaskWiring
        .taskWiringOfProcessesNobodyDeployed(workflowModuleId)
        .forEach((
            bpmnProcessId,
            taskDefinitions) -> {
          // recorded in every mode, so the viewer API can say why it has nothing to show
          // for a workflow running under this id instead of answering an empty list
          deployedProcesses.recordDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
          final var openedNames = new TreeSet<String>();
          final var sharedNames = new TreeSet<String>();
          taskDefinitions
              .forEach(taskDefinition -> {
                final var name = scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition);
                final var served = ServedTask.ofADeclaredId(bpmnProcessId, taskDefinition);
                if (processesByTaskDefinition.containsKey(name) || processesByUserTaskReference.containsKey(name)) {
                  // a deployed process of this module already asks the engine for that name,
                  // so the tasks of the old id arrive at its subscription. The declared id
                  // joins that subscription without becoming a routing candidate: it is what
                  // the payload set has to cover, and what a delivery nobody can place names
                  // as a further possibility
                  sharedNames.add(name);
                  joinIfSubscribed(processesByTaskDefinition, name, served);
                  joinIfSubscribed(processesByUserTaskReference, name, served);
                  return;
                }
                openedNames.add(name);
                // a served task definition is either a service task's or a user task's, and
                // which of the two cannot be told without the model this application no
                // longer has. Both subscriptions are opened therefore, and the one whose
                // kind the task never was stays idle - an idle subscription of this API
                // costs nothing, not even an activation request
                processesByTaskDefinition
                    .computeIfAbsent(name, key -> new ArrayList<>())
                    .add(served);
                processesByUserTaskReference
                    .computeIfAbsent(name, key -> new ArrayList<>())
                    .add(served);
              });
          declaredIds.add(new DeclaredId(bpmnProcessId, taskDefinitions, openedNames, sharedNames));
        });
    return declaredIds;

  }

  /**
   * Adds a declared id's task to a subscription which exists, and leaves the map alone
   * where it does not: the two maps hold the service tasks and the user tasks of the
   * deployed models, and a name lives in one of them or in the other.
   *
   * @param subscriptions The service-task or the user-task subscriptions
   * @param name The name as the engine knows it
   * @param served What the declared id serves under that name
   */
  private static void joinIfSubscribed(
      final Map<String, List<ServedTask>> subscriptions,
      final String name,
      final ServedTask served) {

    final var subscribed = subscriptions.get(name);
    if (subscribed != null) {
      subscribed.add(served);
    }

  }

  /**
   * Says what the workflows of a declared BPMN process id are served with, once per start
   * and per id, and warns about the <code>&#64;WorkflowEnded</code> method which is not
   * served for it - that one needs no model at all, only the id, so it is warned about the
   * same way it is for a deployed process.
   * <p>
   * Three things can be true of such an id. Subscriptions were opened for it, which is the
   * normal case under the default mode. Its names are shared with a deployed process,
   * which serves the workflows but cannot tell a delivery of the old id apart unless the
   * engine names the process. Or its methods name no task definition at all, which is the
   * one case worth a warning: a method wired to a BPMN element id
   * (<code>&#64;WorkflowTask(id = ...)</code>) is matched through the model, and the model
   * of that id is what this application does not have, so no name can be composed and
   * those workflows stand still.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   * @param declaredId What the id got while the subscriptions were composed
   */
  private void reportWhatADeclaredIdIsServedWith(
      final String workflowModuleId,
      final DeclaredId declaredId) {

    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, declaredId.bpmnProcessId());
    if (!declaredId.openedNames().isEmpty()) {
      log.info(
          """
              Process-Engine-API adapter '{}': opened subscriptions for {} task definition(s) of the \
              declared BPMN process '{}' of workflow module '{}', so the workflows still running \
              under that id keep being served: {}. Each of these names is subscribed twice, once for \
              an asynchronous task and once for a user task, because nothing outside the model says \
              which of the two it was.""",
          adapterId,
          declaredId.openedNames().size(),
          declaredId.bpmnProcessId(),
          workflowModuleId,
          String.join(", ", declaredId.openedNames()));
    }
    if (!declaredId.sharedNames().isEmpty()) {
      log.info(
          """
              Process-Engine-API adapter '{}': the workflows of the declared BPMN process '{}' \
              (workflow module '{}') are served by the subscriptions of the deployed processes ({}) \
              - the task definitions of this module do not carry the BPMN process id, so a task of \
              the old id is named like any other. Such a delivery is attributed to a deployed \
              process unless the engine supplies the meta entry '{}' (see GAPS.md).""",
          adapterId,
          declaredId.bpmnProcessId(),
          workflowModuleId,
          String.join(", ", declaredId.sharedNames()),
          PeaTaskMeta.BPMN_PROCESS_ID);
    }
    if (!declaredId.taskDefinitions().isEmpty()) {
      return;
    }
    log.warn(
        """
            Process-Engine-API adapter '{}': workflow module '{}' declares BPMN process '{}' without \
            deploying a model under it, and no @WorkflowTask method serving that id names a task \
            definition - every one of them is wired to a BPMN element id instead. A subscription \
            asks for a task definition, and composing one needs the model of that process, which \
            this application does not bring any more and cannot read back from the engine (see \
            GAPS.md). The workflows still running under that id therefore stand still at their next \
            task, without anything being logged, because a task nobody subscribed for is not a \
            failed task. Either wire those methods by task definition \
            ('@WorkflowTask(taskDefinition = ...)', which is what the model's \
            'zeebe:taskDefinition' carries), or keep deploying the old model under its old id until \
            those workflows have ended.""",
        adapterId,
        workflowModuleId,
        declaredId.bpmnProcessId());

  }

  @Override
  public PeaProcessingContext readDmn(
      final String workflowModuleId,
      final PeaProcessingContext existingContext,
      final String filename,
      final java.io.InputStream dmn) {

    // the file travels to the engine as it is. The decision id is NOT scoped, unlike a
    // process id under prefix scoping: this API knows no binding from a business rule
    // task to a decision, so there is no reference which could be rewritten to match a
    // renamed decision - renaming one side only would break every model. See GAPS.md.
    existingContext
        .addDecision(filename, io.vanillabp.integration.adapter.spi.DmnDecisionIds.bytesOf(dmn));
    log.debug(
        "Process-Engine-API adapter '{}': decision table '{}' of workflow module '{}' will be "
            + "deployed with its processes",
        adapterId,
        filename,
        workflowModuleId);
    return existingContext;

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

    // nothing reaches the engine before it is clear that it can tell the processes apart
    failOnCollidingProcessIds(workflowModuleId, bpmsProcessingContext);

    // A BPMN file may contain several executable processes, so the same resource can show
    // up multiple times in the context - deploy each file (by name) exactly once.
    final var resourcesByFilename = new LinkedHashMap<String, byte[]>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> resourcesByFilename.putIfAbsent(model.filename(), model.resource()));
    // the module's decision tables are resources of the same bundle: a business rule task
    // calls a decision of its own module, so both are deployed together
    bpmsProcessingContext
        .getDecisions()
        .forEach(resourcesByFilename::putIfAbsent);
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

    reportWhatTheModelsDeclare(workflowModuleId, bpmsProcessingContext);

  }

  /**
   * Refuses a deployment whose BPMN process ids the engine could not tell apart, and does
   * it before anything of that deployment is sent.
   * <p>
   * What goes over is the workflow module being deployed and nothing else. The core keeps
   * which module reached the BPMS under which identifier, so a clash with a module deployed
   * earlier in the same boot is found there, and the boot ends while the second of the two
   * modules deploys. The core also composes the form each id reaches the engine in and
   * words the message, so the configured mode decides what counts as a collision here.
   * <p>
   * Under {@code none} two workflow modules using one process id collide, and nothing else
   * would say so: the engine keeps one of the two models and loses the other. Under
   * {@code use-prefix} the module id is part of every id, so two modules
   * collide only where one module id plus one process id compose what another such pair
   * composes, which a module id carrying the prefix separator can do. The remaining mode
   * never gets this far, because this adapter refuses it while deploying.
   * <p>
   * This ends the boot, while a name the models declare is only warned about. Two modules
   * sharing a message name leave both models as they are and only make the name ambiguous,
   * whereas two processes under one id mean one of the models is not in the engine at all.
   *
   * @param workflowModuleId The workflow module about to be deployed
   * @param bpmsProcessingContext What the pipeline collected for it
   * @throws IllegalStateException Naming the colliding pairs and the fix
   */
  private void failOnCollidingProcessIds(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    if (scoping == null) {
      return;
    }
    // a set, because one BPMN file may hold several processes and a module may declare one
    // process id in two of its files: the same pair twice is not a collision
    final var processesReachingTheEngine = new LinkedHashSet<NameClashAvoidanceSupport.DeployedProcess>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> processesReachingTheEngine
            .add(
                new NameClashAvoidanceSupport.DeployedProcess(
                    workflowModuleId, model.bpmnProcessId())));
    scoping.validateNoCollidingProcessIds(adapterId, processesReachingTheEngine);

  }

  /**
   * Hands the core the identifiers the module's models declare, once the module is
   * deployed. The core composes the form each of them reaches the engine in and warns
   * where another workflow module of this application ends up under the same one.
   * <p>
   * This asks the engine nothing, and that is the whole reason it is here: what the engine
   * already holds cannot be asked of this API at all, for no kind of identifier (see
   * {@code GAPS.md}, entry 24), while the names of the models being deployed are in the
   * adapter's hands anyway. What reaches the core is what the rewrite of the raw BPMN
   * recognises, no more.
   * <p>
   * A diagnostic must not end a deployment the engine accepted, so a failure in here is a
   * debug line and nothing else.
   *
   * @param workflowModuleId The workflow module which was deployed
   * @param bpmsProcessingContext What the pipeline collected for it
   */
  private void reportWhatTheModelsDeclare(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    if (scoping == null) {
      return;
    }
    try {
      scoping
          .reportIdentifiersTheModelsDeclare(
              adapterId,
              workflowModuleId,
              bpmsProcessingContext.getDeclaredIdentifiers());
    } catch (final RuntimeException e) {
      log.debug(
          "Process-Engine-API adapter '{}': the identifiers declared by workflow module '{}' were "
              + "not checked against the other workflow modules",
          adapterId,
          workflowModuleId,
          e);
    }

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
                .add(ServedTask.ofADeployedModel(model.bpmnProcessId(), task.taskDefinition()))));

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
                .add(ServedTask.ofADeployedModel(model.bpmnProcessId(), userTask.taskDefinition()))));

    // and what the application still serves for a BPMN process id it declares without
    // deploying a model under it - the old id of a renamed process. Composed after both
    // maps are complete, because a name a deployed process already reaches needs no
    // subscription of its own
    final var declaredIds = composeTheSubscriptionsOfProcessesNobodyDeployed(
        workflowModuleId, processesByTaskDefinition, processesByUserTaskReference);

    if (!userTaskObservers.isEmpty()) {
      // said while starting rather than at the first delivery: an observer the platform
      // did not pick up behaves like one which has nothing to say, and starting is the
      // only moment where the two can still be told apart
      log.info(
          "Process-Engine-API adapter '{}': the user tasks of workflow module '{}' are observed by {}",
          adapterId,
          workflowModuleId,
          userTaskObservers.names());
    }
    processesByUserTaskReference.forEach((
        externalFormReference,
        served) -> {
      final var processes = RoutableProcesses.of(served);
      // A user-task notification carries a payload too, so it is narrowed the
      // same way as a service task
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = PeaUserTaskHandler
          .builder()
          .adapterId(adapterId)
          .workflowModuleId(workflowModuleId)
          .externalFormReference(externalFormReference)
          .bpmnProcessIds(processes.routingCandidates())
          .declaredBpmnProcessIds(processes.declaredSharingTheName())
          .workflowTaskInvoker(workflowTaskInvoker)
          .scoping(scoping)
          .fetchVariables(fetchVariables)
          .observers(userTaskObservers)
          .build();
      try {
        final var subscription = taskSubscriptionApi
            .subscribeForTask(new SubscribeForTaskCmd(
                Map.of(), TaskType.USER, externalFormReference, fetchVariables
                    .payloadDescription(), handler,
                // the TaskTerminationHandler overload (Process-Engine-API 1.5 and up): the
                // older Consumer<String> keeps the task id and drops the reason with the rest
                // of the engine's meta map, which is what tells a finished task from a
                // withdrawn one
                (TaskTerminationHandler) handler::terminated))
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
      final var processes = RoutableProcesses.of(served);
      // What the delivered payload has to carry - the aggregate's ID and the
      // variables the handlers read, instead of everything the process instance holds
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = PeaTaskHandler
          .builder()
          .adapterId(adapterId)
          .workflowModuleId(workflowModuleId)
          .taskDefinition(taskDefinition)
          .bpmnProcessIds(processes.routingCandidates())
          .declaredBpmnProcessIds(processes.declaredSharingTheName())
          .workflowTaskInvoker(workflowTaskInvoker)
          .serviceTaskCompletionApi(serviceTaskCompletionApi)
          .scoping(scoping)
          .fetchVariables(fetchVariables)
          .build();
      try {
        final var subscription = taskSubscriptionApi
            .subscribeForTask(new SubscribeForTaskCmd(
                Map.of(), // no restrictions
                TaskType.EXTERNAL, taskDefinition, fetchVariables
                    .payloadDescription(), handler,
                // the same overload as for user tasks: nobody observes an async task, but
                // the log line saying one is gone is worth the engine's reason
                (TaskTerminationHandler) terminated -> log.debug(
                    "Process-Engine-API adapter '{}': task '{}' terminated ({})", adapterId, terminated
                        .getTaskId(),
                    terminated
                        .getMeta()
                        .getOrDefault(TaskInformation.REASON, "no reason given"))))
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

    declaredIds.forEach(declaredId -> reportWhatADeclaredIdIsServedWith(workflowModuleId, declaredId));

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
