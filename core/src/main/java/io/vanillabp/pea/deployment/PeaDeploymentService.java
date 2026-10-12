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
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
import io.vanillabp.pea.observation.PeaUserTaskObserver;
import io.vanillabp.pea.observation.PeaUserTaskObservers;
import io.vanillabp.pea.wiring.PeaFetchVariables;
import io.vanillabp.pea.wiring.PeaTaskHandler;
import io.vanillabp.pea.wiring.PeaTaskMeta;
import io.vanillabp.pea.wiring.PeaUserTaskHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * The deployment service of the Process-Engine-API adapter. There is one instance per
 * configured adapter id, not per type.
 * <p>
 * The Process-Engine-API has no BPMN model type. Its {@code DeploymentApi} deploys a
 * {@code NamedResource}, which is only a name and bytes. So {@link #readBpmn} parses the
 * BPMN XML itself, with the StAX streaming parser of the JDK ({@code javax.xml.stream}). It
 * reads only as far as it has to, for example to find the executable process ids. See
 * {@code GAPS.md}.
 * <p>
 * The {@link DeploymentApi} comes in through the constructor, so the platform modules
 * choose the implementation. By default that is the in-memory mock. A real
 * Process-Engine-API implementation replaces it.
 */
@Slf4j
// see decision 3 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class PeaDeploymentService implements AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext> {

  private final String adapterId;

  private final DeploymentApi deploymentApi;

  /**
   * Where the core processes tasks. It checks the wiring during {@link #wireBpmn} and
   * hands out the tasks at runtime.
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * The runtime half of the split SPI. This service does more than wire. It opens the task
   * subscriptions in {@code startWorkflowProcessing}, and their handlers pass every delivery
   * to the core. So it holds both halves and passes this one on.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Where the core hears about workflows which ended. This service uses it ONLY to warn
   * about methods this adapter cannot serve. It is <code>null</code> whenever the
   * collaborators carry none.
   */
  private final WorkflowEndedInvoker workflowEndedInvoker;

  private final TaskSubscriptionApi taskSubscriptionApi;

  private final ServiceTaskCompletionApi serviceTaskCompletionApi;

  /**
   * What this application version deployed. It is the ONLY source of process definitions
   * and BPMN XML for the viewer API, because the Process-Engine-API has no repository API
   * (see {@code GAPS.md}). The {@code PeaProcessService} of the same adapter id shares it.
   */
  private final PeaDeployedProcesses deployedProcesses;

  /**
   * A constructor without a shared record of deployed processes, for tests. The service
   * then records into an instance of its own.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param deploymentApi Where the module's resources are handed to the engine
   * @param collaborators Everything the platform hands over, wiring and invoker included
   * @param taskSubscriptionApi Where the subscriptions for the deployed tasks are opened
   * @param serviceTaskCompletionApi What a delivered service task is completed through
   */
  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final AdapterCollaborators collaborators,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi) {

    this(adapterId, deploymentApi, collaborators, taskSubscriptionApi, serviceTaskCompletionApi, new PeaDeployedProcesses());

  }

  /**
   * The constructor a platform integration uses. The record of deployed processes is
   * shared with the process service of the same adapter id. That record is the only source
   * of process definitions this engine has.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param deploymentApi Where the module's resources are handed to the engine
   * @param collaborators Everything the platform hands over, wiring and invoker included
   * @param taskSubscriptionApi Where the subscriptions for the deployed tasks are opened
   * @param serviceTaskCompletionApi What a delivered service task is completed through
   * @param deployedProcesses The record this service fills and the process service reads
   */
  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi,
      final AdapterCollaborators collaborators,
      final TaskSubscriptionApi taskSubscriptionApi,
      final ServiceTaskCompletionApi serviceTaskCompletionApi,
      final PeaDeployedProcesses deployedProcesses) {

    VanillaBpParts.requireAdapterFitsPlatform(PeaAdapter.ADAPTER_TYPE, PeaDeploymentService.class);

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
   * The name-clash-avoidance model of the core. The Process-Engine-API has no isolation
   * mechanism of its own, so only {@code none} and {@code use-prefix} work here.
   * {@code by-adapter} is the default, and it is refused at startup with a message which
   * says what to do. May be <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Everything the platform hands over. An adapter with an incomplete registration is never
   * created (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * Who watches the user tasks delivered to this adapter. These are beans of the
   * application, and the platform modules collect them. The list is empty unless the
   * application registered one, and then nobody is told about a user task.
   */
  private PeaUserTaskObservers userTaskObservers;

  /**
   * Sets who watches this adapter's user-task deliveries (the platform modules collect the
   * beans of the application and inject them after construction).
   *
   * @param userTaskObservers The observers in the order the platform resolved them, or
   *          <code>null</code> for none
   */
  public void setUserTaskObservers(
      final List<PeaUserTaskObserver> userTaskObservers) {

    this.userTaskObservers = PeaUserTaskObservers.of(adapterId, userTaskObservers);

  }

  /**
   * One BPMN task a subscription serves. The variables the subscription asks for are
   * derived from these.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param elementId The id of the BPMN element, as the modeller wrote it. It is the SECOND
   *          key a <code>&#64;WorkflowTask</code> method can be wired by
   *          (<code>&#64;WorkflowTask(id = ...)</code>). So everything asked with the task
   *          definition is asked with this one too. <code>null</code> if the task comes
   *          from no model, which {@code declaredWithoutAModel} says
   * @param taskDefinition The PLAIN task definition (the external form reference for a
   *          user task)
   * @param declaredWithoutAModel Whether the module DECLARES the process without deploying
   *          anything under it, as with the old id of a renamed process. Such a task comes
   *          from what the methods of the application serve, not from a model (see decision
   *          11 in the repository's DECISIONS.md)
   */
  record ServedTask(String bpmnProcessId,
                    String elementId,
                    String taskDefinition,
                    boolean declaredWithoutAModel) {

    static ServedTask ofADeployedModel(
        final String bpmnProcessId,
        final String elementId,
        final String taskDefinition) {

      return new ServedTask(bpmnProcessId, elementId, taskDefinition, false);

    }

    static ServedTask ofADeclaredId(
        final String bpmnProcessId,
        final String taskDefinition) {

      // no model, so no element. The name is built from what the methods of the application
      // serve. A method wired by an element id names no task definition to build from
      return new ServedTask(bpmnProcessId, null, taskDefinition, true);

    }

  }

  /**
   * The processes a subscription may deliver from, split by whether this application
   * deployed a model for them.
   *
   * @param deployed The processes of the deployed models. A delivery without the
   *          {@code bpmnProcessId} meta entry can be routed to these
   * @param declaredWithoutAModel The ids the module only declares. A delivery reaches them
   *          only if the name carries the process id or the engine names the process
   *          itself
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
     * What a delivery which names no process may belong to. These are the deployed
     * processes, or the declared id if this subscription was opened for that id alone.
     *
     * @return The plain BPMN process ids, never empty
     */
    List<String> routingCandidates() {

      return deployed.isEmpty()
          ? declaredWithoutAModel
          : deployed;

    }

    /**
     * The declared ids a routing failure has to name as a third possibility. They share
     * this subscription with deployed processes. So a delivery may belong to a workflow
     * which still runs under the old id of a renamed process.
     *
     * @return The plain BPMN process ids, empty if there is nothing to add
     */
    List<String> declaredSharingTheName() {

      return deployed.isEmpty()
          ? List.of()
          : declaredWithoutAModel;

    }

  }

  /**
   * What one subscription asks the engine for. It is the aggregate-ID variables plus the
   * declared <code>&#64;TaskParam</code> names of everything it serves.
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
      // a subscription serves only the tasks of claimed processes. A workflow service also
      // declares the old id of a renamed process. So the core knows the name in both cases
      variables.add(workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, task.bpmnProcessId()));
      // what the handlers of this task read with @TaskParam. The core read those names off
      // the methods while wiring, and this adapter has no model to guess from. Ask with
      // BOTH keys a method can be wired by. A method which names the element id serves this
      // task too. If only the task definition were asked, its variables would not be
      // fetched. The delivery would then fail and not pass null, because a @TaskParam the
      // subscription never asked for is refused
      variables
          .addAll(
              workflowTaskWiring
                  .taskParameterNames(workflowModuleId, task.bpmnProcessId(), task.taskDefinition()));
      if (task.elementId() != null) {
        variables
            .addAll(
                workflowTaskWiring
                    .taskParameterNames(workflowModuleId, task.bpmnProcessId(), task.elementId()));
      }
      // and what the extensions read. The engine hands this task to this subscription only.
      // So an extension reads what this delivery carries, and it has no subscription of its
      // own to ask for variables (see decision 20 in the repository's DECISIONS.md)
      variables
          .addAll(
              workflowTaskWiring
                  .extensionTaskParameterNames(workflowModuleId, task.bpmnProcessId(), lookupKeysOf(task)));
    }
    return PeaFetchVariables.Selection.of(variables);

  }

  /**
   * The keys an extension uses to find its methods for a task, in the order it tries them.
   * The element id comes first, then the task definition. The Business Cockpit uses this
   * order, so the variables fetched here are those of the method which runs there.
   *
   * @param task A task one subscription serves
   * @return The keys, without the element id if no model names it
   */
  static List<String> lookupKeysOf(
      final ServedTask task) {

    return task.elementId() == null
        ? List.of(task.taskDefinition())
        : List.of(task.elementId(), task.taskDefinition());

  }

  /**
   * Which BPMN element a delivery of one subscription belongs to, per BPMN process, as far
   * as the deployed models tell.
   * <p>
   * A subscription asks the engine for a task definition. The engine does not have to say
   * anything else about a task it delivers (see {@code GAPS.md}, entry 26). The element id
   * is the SECOND key a <code>&#64;WorkflowTask</code> method is wired by. So a delivery
   * which names no element reaches no method wired that way. The models this adapter
   * deployed hold the missing value. Once a delivery is routed to a BPMN process, it belongs
   * to the element in that process which carries the name of this subscription.
   * <p>
   * This fails if the name sits on SEVERAL elements of one process. Then the model gives no
   * answer, and only the engine can tell them apart. That is logged while starting. Telling
   * two elements with one name apart is exactly why someone wires a method by the element
   * id.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   * @param served The tasks this subscription serves
   * @return The element id for each plain BPMN process id. Processes with more than one
   *         element and ids without a model are left out
   */
  private Map<String, String> theElementEachProcessDeliversFrom(
      final String workflowModuleId,
      final List<ServedTask> served) {

    final var tasksPerProcess = new LinkedHashMap<String, List<ServedTask>>();
    served
        .stream()
        .filter(task -> task.elementId() != null)
        .forEach(task -> tasksPerProcess
            .computeIfAbsent(task.bpmnProcessId(), key -> new ArrayList<>())
            .add(task));
    final var oneEach = new LinkedHashMap<String, String>();
    tasksPerProcess
        .forEach((
            bpmnProcessId,
            tasks) -> {
          final var elementIds = tasks
              .stream()
              .map(ServedTask::elementId)
              .distinct()
              .toList();
          if (elementIds.size() == 1) {
            oneEach.put(bpmnProcessId, elementIds.getFirst());
            return;
          }
          sayThatOnlyTheEngineTellsTheseElementsApart(
              workflowModuleId, bpmnProcessId, tasks.getFirst().taskDefinition(), elementIds);
        });
    return Map.copyOf(oneEach);

  }

  /**
   * Says that several BPMN elements of one process carry one task definition. A delivery
   * can then be matched to one of them only if the engine names the element itself.
   * <p>
   * This is said only if a method is wired by one of those element ids. Everything else is
   * served by the task definition, which every delivery carries, so there is nothing to warn
   * about.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   * @param bpmnProcessId The plain BPMN process id
   * @param taskDefinition The plain task definition the elements share
   * @param elementIds The elements carrying it, more than one
   */
  private void sayThatOnlyTheEngineTellsTheseElementsApart(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final List<String> elementIds) {

    final var wiredByTheirId = elementIds
        .stream()
        .filter(elementId -> workflowTaskInvoker
            .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, elementId))
        .toList();
    if (wiredByTheirId.isEmpty()) {
      return;
    }
    log.warn(
        """
            Process-Engine-API adapter '{}': the BPMN elements {} of process '{}' (workflow \
            module '{}') all carry the task definition '{}', and a @WorkflowTask method is \
            wired to the element id of {}. A delivery of that name can be attributed to one \
            element only where the engine fills the meta entry '{}', which this API does not \
            promise: where it fills none, such a delivery finds no method and fails. Give \
            those elements a task definition each, or wire the methods by task definition.""",
        adapterId,
        elementIds,
        bpmnProcessId,
        workflowModuleId,
        taskDefinition,
        wiredByTheirId,
        PeaTaskMeta.BPMN_TASK_ID);

  }

  /**
   * A task definition in the form the engine knows it. Under {@code use-prefix} it has the
   * prefix.
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
   * Two <code>process-engine-api</code> adapter ids cannot talk to different engines. The
   * APPLICATION provides the Process-Engine-API as a set of CDI or Spring beans. There is no
   * connection configuration per adapter id. So every configured id of this type talks to
   * the very same engine beans. Two of them are a mistake, not a migration setup (see
   * {@code GAPS.md}, entry 14).
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
   * Nothing is kept apart here. There is no tenant and no namespace behind this API, so
   * every workflow module reaches one engine in one scope (see {@code GAPS.md}, entry 15).
   * <p>
   * The core asks this when it checks whether two BPMN processes reach the BPMS under one
   * identifier. It asks only if the mode leaves the identifiers plain and the BPMS is meant
   * to keep them apart. This adapter refuses that mode while deploying. So the question
   * rarely gets here, and if it does, the answer is the same.
   * <p>
   * The inherited default answers <code>false</code> as well, so this override changes no
   * behaviour. It is here to put the answer and its reason into the adapter the gap belongs
   * to, where a reader looks for it. That is its whole job, so do not remove it as
   * redundant.
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
                  .userTasks(), process.userTasksWithoutAFormReference())));
    }
    return result;

  }

  /**
   * One executable process parsed from a BPMN file. It has its id, the name a modeller
   * wrote on it and its service-like tasks. Each task has its activity id and the type of
   * its <code>zeebe:taskDefinition</code>.
   */
  private record ParsedProcess(
                               String bpmnProcessId,
                               String processName,
                               List<BpmnTaskSpec> tasks,
                               List<BpmnTaskSpec> userTasks,
                               List<BpmnTaskSpec> userTasksWithoutAFormReference) {
  }

  /**
   * Whether the element the reader stands on has an attribute with the given local name,
   * in any namespace. Each engine spells the reference to a decision in its own way, and
   * this parser reads raw XML, not a model.
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
   * An attribute a modeller left out and one they left empty mean the same. The adapter SPI
   * says "none" with <code>null</code>.
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

  /**
   * Reads the BPMN XML with StAX and collects all {@code <bpmn:process isExecutable="true">}
   * elements with their service-like tasks. The Process-Engine-API has no BPMN model type,
   * so StAX is used. It needs no DOM and no model API of a particular BPMS.
   * <p>
   * The task definition is read from the <code>zeebe:taskDefinition</code> extension, as in
   * Camunda 8. The Process-Engine-API does not define how BPMN names task definitions (see
   * {@code GAPS.md}). The wiring validation reports a task without one.
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
    // no external entities and no DTDs, against XXE attacks
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
                            reader.getAttributeValue(null,
                                "name")), new ArrayList<>(), new ArrayList<>(), new ArrayList<>())
                    : null;
            if (currentProcess != null) {
              processes.add(currentProcess);
            }
          } else if ((currentProcess != null) && serviceLikeTasks.contains(element)) {
            currentTaskId = reader.getAttributeValue(null, "id");
            currentTaskHasDefinition = false;
            // the ENGINE serves a business rule task which names a decision, however the
            // engine spells that reference. Such a task needs no @WorkflowTask method
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
              // the namespace is checked because the marker extension <zeebe:userTask/>
              // has the same local name as the BPMN element
              currentUserTaskId = reader.getAttributeValue(null, "id");
              // what the modeller wrote on the element. It is the only readable name a task
              // list has for this task, because the engine reports none of its own
              currentUserTaskName = blankToNull(reader.getAttributeValue(null, "name"));
              currentUserTaskHasFormReference = false;
            } else if ((currentUserTaskId != null) && "formDefinition".equals(element)) {
              // for a user task, the external reference of zeebe:formDefinition IS the task
              // definition, as in Camunda 8. Like every task, it needs a method or the
              // property implemented-externally=true
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
              // no zeebe:taskDefinition. The wiring validation reports it
              currentProcess
                  .tasks()
                  .add(new BpmnTaskSpec(currentTaskId, null));
            }
            currentTaskId = null;
            currentTaskCallsADecision = false;
          } else
            if ("userTask".equals(element) && (currentUserTaskId != null) && BPMN_NS.equals(reader.getNamespaceURI())) {
              if (!currentUserTaskHasFormReference && (currentProcess != null)) {
                // nobody can subscribe for a user task without an external form reference. So
                // it becomes no spec of the core and no subscription of this adapter. It is
                // still carried along, so the boot can name it to whoever claims the process. A
                // method meant for such a task is never called, and nothing else would say so
                currentProcess
                    .userTasksWithoutAFormReference()
                    .add(BpmnTaskSpec.userTask(currentUserTaskId, null, currentUserTaskName));
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
          // the reader works on bytes in memory, so a failed close loses nothing
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
    // read what this model declares while the PLAIN bytes are still here. The rewrite below
    // gives the engine prefixed names, but the core wants the names the application wrote.
    // This is read in EVERY mode. The core looks for two modules whose names reach the
    // engine in the same form, and that happens under the mode 'none'. Reading only while
    // prefixing would look exactly where there is nothing to find
    context
        .getDeclaredIdentifiers()
        .addAll(PeaDeclaredIdentifiers.of(model));
    // the deployed BYTES carry the scoped identifiers. The bpmnProcessId and the tasks of
    // the model stay PLAIN, because the registries of the core use them as keys (see
    // decision 2 in the repository's DECISIONS.md)
    final var scopedResource = PeaScoping.apply(
        model.resource(), workflowModuleId, model.bpmnProcessId(), adapterId, scoping);
    context
        .getModels()
        .add(scopedResource == model.resource()
            ? model
            : new PeaBpmnModel(
                model.filename(), scopedResource, model.bpmnProcessId(), model.processName(), model.tasks(), model
                    .userTasks(), model.userTasksWithoutAFormReference()));
    return context;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model,
      final PeaProcessingContext context) {

    // a process nobody claims is deployed with its file and left alone. Nothing subscribes
    // to its tasks, and no check ends the boot because of it (see decision 18 of
    // DECISIONS.md). The core has already ended the start because of it, unless the
    // application marked it as somebody else's
    if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
      log.debug(
          "Process-Engine-API adapter '{}': BPMN process '{}' of file '{}' (workflow module '{}') is "
              + "claimed by no @WorkflowService and is deployed as it was modelled",
          adapterId,
          bpmnProcessId,
          filename,
          workflowModuleId);
      return;
    }

    // check the tasks of the BPMN against the registered @WorkflowTask methods. An
    // exception thrown here follows the deployment-failure policy
    final var specs = new ArrayList<BpmnTaskSpec>(model.tasks());
    specs.addAll(model.userTasks());
    specs.addAll(userTasksWithoutAFormReferenceSomebodyElseServes(workflowModuleId, bpmnProcessId, model));
    workflowTaskWiring.validateTaskWiring(adapterId, workflowModuleId, bpmnProcessId, specs);

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
   * Tells the core that this BPMS keeps no catalog of the versions of a process, and what a
   * delivery carries instead.
   * <p>
   * The Process-Engine-API knows no versions (see entries 19 and 20 of
   * <code>GAPS.md</code>). There is no version number, no deployment order and no way to ask
   * which versions of a process the engine still holds. A delivered task may carry the
   * version tag of its process definition. It is in the <code>meta</code> map, and only if
   * the engine behind the API fills it. That is why the answer is
   * {@link ReportedProcessVersion#VERSION_TAG}.
   * <p>
   * Not registering a catalog would mean the same to a program, but not to a person. The
   * core sees exactly that before an adapter was asked. It would then say nothing about the
   * methods of such a process, and its messages about a version would speak of a BPMS which
   * cannot be reached. Saying it lets the core name, while the application boots, the methods
   * whose version a delivery here can never match.
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
   * Fails the deployment of a process the ENGINE would start on its own, with a timer,
   * signal or conditional start event. The Process-Engine-API cannot tell an application
   * that its engine started a process (see {@code GAPS.md}). So such a workflow would run
   * without a workflow aggregate. No task could be routed, and no expression could be
   * resolved. Failing the deployment is the honest answer. It follows the deployment-failure
   * policy, so an adapter which is not first in priority can turn it into a warning.
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
   * The user tasks of one process which name no external form reference and which the
   * application marked as served by something else. Any other such task ends the boot.
   * <p>
   * This adapter subscribes for a user task by its external form reference. So a user task
   * without one never reaches a method. In a process which a <code>&#64;WorkflowService</code>
   * class claims, every task needs a method or the property
   * <code>implemented-externally=true</code>. For such a task only the property is possible.
   * So the boot ends because of such a task, unless the property says that somebody else
   * serves it. A marked task goes to the core with the other tasks. The core refuses a method
   * for it which is there next to the property.
   * <p>
   * A process nobody claims never gets here.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param model The model this boot deploys
   * @return The marked user tasks without a form reference
   * @throws IllegalStateException If the application claims the process and a user task without
   *           a form reference is not marked
   */
  private List<BpmnTaskSpec> userTasksWithoutAFormReferenceSomebodyElseServes(
      final String workflowModuleId,
      final String bpmnProcessId,
      final PeaBpmnModel model) {

    if (model.userTasksWithoutAFormReference().isEmpty()) {
      return List.of();
    }
    final var marked = model
        .userTasksWithoutAFormReference()
        .stream()
        .filter(userTask -> workflowTaskWiring
            .isImplementedExternally(adapterId, workflowModuleId, bpmnProcessId, userTask))
        .toList();
    final var unmarked = model
        .userTasksWithoutAFormReference()
        .stream()
        .filter(userTask -> !marked.contains(userTask))
        .toList();
    if (unmarked.isEmpty()) {
      return marked;
    }
    throw new IllegalStateException(
        """
            Process-Engine-API adapter '%s' does not deploy BPMN process '%s' of workflow module \
            '%s': %d user task(s) name no external form reference (zeebe:formDefinition \
            externalReference): %s. This adapter subscribes for a user task by that reference, so \
            such a task never reaches a @WorkflowTask method. Add the reference and a method \
            named after it, or say that something other than this application serves the task:
            %s"""
            .formatted(
                adapterId,
                bpmnProcessId,
                workflowModuleId,
                unmarked.size(),
                unmarked
                    .stream()
                    .map(userTask -> "'%s'".formatted(userTask.activityId()))
                    .collect(java.util.stream.Collectors.joining(", ")),
                unmarked
                    .stream()
                    .map(userTask -> io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally
                        .propertyLine(workflowModuleId, bpmnProcessId, userTask.activityId()))
                    .collect(java.util.stream.Collectors.joining("\n"))));

  }

  /**
   * Warns about a <code>&#64;WorkflowEnded</code> method this adapter cannot serve. The
   * Process-Engine-API delivers TASKS. It has no event, subscription or callback which says
   * that a process instance ended (see {@code GAPS.md}). So the notification never arrives.
   * <p>
   * A start event the engine fires on its own fails the deployment, but this does NOT. The
   * workflow itself runs well, only the notification is missing. Failing the boot because
   * of it would be too much. The warning says what does not happen, so nobody waits for
   * it.
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
   * What one BPMN process id got while the subscriptions were built. The module declares
   * the id WITHOUT deploying a model under it.
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
   * DECLARES without deploying a model under it. Such an id is the old id of a renamed
   * process.
   * <p>
   * A subscription asks for a task definition. Under {@code use-prefix} a task definition
   * carries the id of the process it was deployed with. So the tasks of the workflows under
   * the old id are named after the OLD id. No subscription of the deployed processes asks
   * for them, and nobody notices, because a task nobody fetches has not failed. Those
   * workflows need one more subscription per name they produce. The names are built the
   * same way as the deployed ones: the task definitions the application serves for that
   * id, scoped by it. Nothing is read from the engine, and that is why this works here at
   * all (see decision 11 in the repository's DECISIONS.md).
   * <p>
   * If a name is already served, no subscription of its own is added. That is the case in
   * every mode except {@code use-prefix}, and under {@code use-prefix} with
   * {@code prefix-task-definitions-per-process: false}. The declared id is still added to
   * that subscription. So its <code>&#64;TaskParam</code> names reach the variables the
   * subscription asks for. And a delivery which cannot be routed says that an old id is
   * possible. It is NOT added as a routing candidate. A delivery under a shared name would
   * then be ambiguous for every workflow, also for the ones which are served correctly
   * now.
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
          // recorded in every mode. So the viewer API can say why it has nothing to show for
          // a workflow under this id, and does not just answer an empty list
          deployedProcesses.recordDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
          final var openedNames = new TreeSet<String>();
          final var sharedNames = new TreeSet<String>();
          taskDefinitions
              .forEach(taskDefinition -> {
                final var name = scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition);
                final var served = ServedTask.ofADeclaredId(bpmnProcessId, taskDefinition);
                if (processesByTaskDefinition.containsKey(name) || processesByUserTaskReference.containsKey(name)) {
                  // a deployed process of this module already asks the engine for that name.
                  // So the tasks of the old id arrive at its subscription. The declared id
                  // joins that subscription, but it is no routing candidate. Its variables
                  // have to be fetched as well. And a delivery nobody can place names it as
                  // one more possibility
                  sharedNames.add(name);
                  joinIfSubscribed(processesByTaskDefinition, name, served);
                  joinIfSubscribed(processesByUserTaskReference, name, served);
                  return;
                }
                openedNames.add(name);
                // a served task definition belongs to a service task or to a user task.
                // Which of the two needs the model, and this application no longer has it.
                // So both subscriptions are opened, and the one of the wrong kind stays idle.
                // An idle subscription of this API costs nothing, not even an activation
                // request
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
   * Adds the task of a declared id to a subscription which exists. If there is none, the
   * map stays as it is. The two maps hold the service tasks and the user tasks of the
   * deployed models, and a name is in one of them or in the other.
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
   * and per id. It also warns about a <code>&#64;WorkflowEnded</code> method which is not
   * served for the id. That method needs no model at all, only the id. So the warning is the
   * same as for a deployed process.
   * <p>
   * Three things can be true of such an id. First, subscriptions were opened for it. That is
   * the normal case under the default mode. Second, its names are shared with a deployed
   * process. That process serves the workflows, but it cannot tell a delivery of the old id
   * apart unless the engine names the process. Third, its methods name no task definition
   * at all. Only this case is worth a warning. A method wired to a BPMN element id
   * (<code>&#64;WorkflowTask(id = ...)</code>) is matched through the model. This
   * application does not have the model of that id. So no name can be built, and those
   * workflows stand still.
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

    // the file goes to the engine as it is. The decision id is NOT scoped, unlike a process
    // id under prefix scoping. This API knows no link from a business rule task to a
    // decision. So there is no reference which could be rewritten to match a renamed
    // decision, and renaming one side only would break every model. See GAPS.md.
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

    // the Process-Engine-API has no isolation mechanism of its own, so the DEFAULT mode
    // 'by-adapter' cannot work. Fail with a message which says what to do, and do not
    // quietly deploy every workflow module into one scope
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

    // nothing goes to the engine before it is clear that it can tell the processes apart
    failOnCollidingProcessIds(workflowModuleId, bpmsProcessingContext);

    // a BPMN file may hold several executable processes, so the same resource can be in the
    // context more than once. Deploy each file (by name) exactly once.
    final var resourcesByFilename = new LinkedHashMap<String, byte[]>();
    bpmsProcessingContext
        .getModels()
        .forEach(model -> resourcesByFilename.putIfAbsent(model.filename(), model.resource()));
    // the decision tables of the module go into the same bundle. A business rule task
    // calls a decision of its own module, so both are deployed together
    bpmsProcessingContext
        .getDecisions()
        .forEach(resourcesByFilename::putIfAbsent);
    final var resources = resourcesByFilename
        .entrySet()
        .stream()
        .map(entry -> new NamedResource(entry.getKey(), new ByteArrayInputStream(entry.getValue()), Map.of()))
        .toList();

    // the DeployBundleCommand of the Process-Engine-API has an optional tenantId. But that
    // tenant is the multi-tenancy of the BPMS underneath, not a namespace for a VanillaBP
    // workflow module. So "deploy for workflow module X", with the module as tenant like in
    // Camunda 7, cannot be said. The bundle goes to the default tenant. See GAPS.md.
    final var command = new DeployBundleCommand(resources, null);
    try {
      final var deploymentInformation = deploymentApi
          .deploy(command)
          .get();
      // remember what was deployed. The viewer API answers from these models, because the
      // Process-Engine-API cannot be asked for definitions or BPMN XML
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
   * Refuses a deployment whose BPMN process ids the engine could not tell apart. It does so
   * before anything of that deployment is sent.
   * <p>
   * Only the workflow module being deployed is passed to the core. The core keeps which
   * module reached the BPMS under which identifier. So the core finds a clash with a module
   * deployed earlier in the same boot, and the boot ends while the second of the two
   * modules deploys. The core also builds the form in which each id reaches the engine, and
   * it writes the message. So the configured mode decides what counts as a collision here.
   * <p>
   * Under {@code none}, two workflow modules with one process id collide, and nothing else
   * would say so. The engine keeps one of the two models and loses the other. Under
   * {@code use-prefix}, the module id is part of every id. So two modules collide only if one
   * module id plus one process id give the same string as another such pair. A module id
   * which contains the prefix separator can do that. The third mode never gets this far,
   * because this adapter refuses it while deploying.
   * <p>
   * This ends the boot, but a name the models declare only gets a warning. If two modules
   * share a message name, both models stay as they are and only the name is ambiguous. If
   * two processes share one id, one of the models is not in the engine at all.
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
    // a set, because one BPMN file may hold several processes, and a module may declare one
    // process id in two of its files. The same pair twice is not a collision
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
   * Gives the core the identifiers the models of the module declare, once the module is
   * deployed. The core builds the form in which each of them reaches the engine. It warns if
   * another workflow module of this application ends up under the same one.
   * <p>
   * This asks the engine nothing, and that is the whole point. This API cannot say what the
   * engine already holds, for no kind of identifier (see {@code GAPS.md}, entry 24). But the
   * adapter has the names of the models being deployed anyway. The core gets what the
   * rewrite of the raw BPMN knows, and nothing more.
   * <p>
   * A check must not end a deployment the engine accepted. So a failure in here gives a
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

    // one task subscription per DISTINCT task definition of the module. The task handler
    // passes the task on through the WorkflowTaskInvoker of the core. The BPMN process of a
    // delivered task comes in TaskInformation.meta, under the key 'bpmnProcessId' this
    // adapter agreed on (see GAPS.md). Without it, the task definition has to be unique
    // across the processes of the module. Only a claimed process gets subscriptions. A
    // process nobody claims is deployed with its file and left alone
    final var processesByTaskDefinition = new LinkedHashMap<String, List<ServedTask>>();
    bpmsProcessingContext
        .getModels()
        .stream()
        .filter(model -> workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, model.bpmnProcessId()))
        .forEach(model -> model
            .tasks()
            .stream()
            .filter(task -> task.taskDefinition() != null)
            .forEach(task -> processesByTaskDefinition
                .computeIfAbsent(
                    scopedTaskDefinition(workflowModuleId, model.bpmnProcessId(), task.taskDefinition()),
                    key -> new ArrayList<>())
                .add(ServedTask
                    .ofADeployedModel(model.bpmnProcessId(), task.activityId(), task.taskDefinition()))));

    // user tasks: one subscription of type USER per distinct external form reference. Its
    // handler only passes the task on and completes nothing
    final var processesByUserTaskReference = new LinkedHashMap<String, List<ServedTask>>();
    bpmsProcessingContext
        .getModels()
        .stream()
        .filter(model -> workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, model.bpmnProcessId()))
        .forEach(model -> model
            .userTasks()
            .forEach(userTask -> processesByUserTaskReference
                .computeIfAbsent(
                    scopedTaskDefinition(workflowModuleId, model.bpmnProcessId(), userTask.taskDefinition()),
                    key -> new ArrayList<>())
                .add(ServedTask
                    .ofADeployedModel(model.bpmnProcessId(), userTask.activityId(), userTask.taskDefinition()))));

    // and what the application still serves for a BPMN process id it declares without
    // deploying a model under it, such as the old id of a renamed process. This is built
    // after both maps are complete, because a name a deployed process already reaches needs
    // no subscription of its own
    final var declaredIds = composeTheSubscriptionsOfProcessesNobodyDeployed(
        workflowModuleId, processesByTaskDefinition, processesByUserTaskReference);

    if (!userTaskObservers.isEmpty()) {
      // said while starting, not at the first delivery. An observer the platform did not
      // pick up looks like one which has nothing to say. Starting is the only moment where
      // the two can still be told apart
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
      // a user task carries variables too, so the subscription asks only for the needed
      // ones, the same way as for a service task
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = PeaUserTaskHandler
          .builder()
          .adapterId(adapterId)
          .workflowModuleId(workflowModuleId)
          .externalFormReference(externalFormReference)
          .bpmnProcessIds(processes.routingCandidates())
          .declaredBpmnProcessIds(processes.declaredSharingTheName())
          .bpmnElementIds(theElementEachProcessDeliversFrom(workflowModuleId, served))
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
                // the overload with a TaskTerminationHandler (Process-Engine-API 1.5 and up).
                // The older Consumer<String> keeps only the task id. It drops the reason with
                // the rest of the meta map of the engine, and the reason tells a finished task
                // from a withdrawn one
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
      // what the delivery has to carry: the ID of the aggregate and the variables the
      // handlers read, not everything the process instance holds
      final var fetchVariables = fetchVariablesOf(workflowModuleId, served);
      final var handler = PeaTaskHandler
          .builder()
          .adapterId(adapterId)
          .workflowModuleId(workflowModuleId)
          .taskDefinition(taskDefinition)
          .bpmnProcessIds(processes.routingCandidates())
          .declaredBpmnProcessIds(processes.declaredSharingTheName())
          .bpmnElementIds(theElementEachProcessDeliversFrom(workflowModuleId, served))
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
                // the same overload as for user tasks. Nobody observes an async task, but the
                // log line which says one is gone should carry the reason of the engine
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
    // unsubscribe in reverse order, so the shutdown mirrors the start
    final var subscriptions = bpmsProcessingContext.getSubscriptions();
    for (var i = subscriptions.size() - 1; i >= 0; --i) {
      try {
        taskSubscriptionApi
            .unsubscribe(new UnsubscribeFromTaskCmd(subscriptions.get(i)))
            .get();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        // the shutdown interrupts this thread, so stop trying. But the engine keeps every
        // subscription which was not given back yet. It will deliver tasks to an
        // application which is going away, and nobody sees that unless it is logged
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
