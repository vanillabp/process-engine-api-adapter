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
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;
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

  public PeaDeploymentService(
      final String adapterId,
      final DeploymentApi deploymentApi) {

    this.adapterId = adapterId;
    this.deploymentApi = deploymentApi;

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
    for (final var bpmnProcessId : readExecutableProcessIds(workflowModuleId, filename, resource)) {
      result.add(Map.entry(bpmnProcessId, new PeaBpmnModel(filename, resource, bpmnProcessId)));
    }
    return result;

  }

  /**
   * Streams over the BPMN XML with StAX and collects the ids of all
   * {@code <bpmn:process isExecutable="true">} elements. StAX is used (instead of building
   * a DOM or depending on a BPMS-specific model API) because the Process-Engine-API has no
   * BPMN model type and the adapter only needs the executable process ids.
   *
   * @param workflowModuleId The workflow module id (used for error messages)
   * @param filename The BPMN filename (used for error messages)
   * @param resource The raw BPMN XML bytes
   * @return The ids of the executable processes contained in the resource
   * @throws BpmnParseException If the XML cannot be parsed
   */
  private List<String> readExecutableProcessIds(
      final String workflowModuleId,
      final String filename,
      final byte[] resource) throws BpmnParseException {

    final var factory = XMLInputFactory.newFactory();
    // harden the parser: no external entities, no DTDs (defence against XXE)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

    final var processIds = new ArrayList<String>();
    XMLStreamReader reader = null;
    try (var in = new ByteArrayInputStream(resource)) {
      reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        if (reader.next() != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"process".equals(reader.getLocalName())) {
          continue;
        }
        if (!Boolean.parseBoolean(reader.getAttributeValue(null, "isExecutable"))) {
          continue;
        }
        final var bpmnProcessId = reader.getAttributeValue(null, "id");
        if ((bpmnProcessId != null) && !bpmnProcessId.isBlank()) {
          processIds.add(bpmnProcessId);
        }
      }
      return processIds;
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

    // Wiring the business code (@WorkflowTask methods) to the BPMN tasks is a later story.
    log.debug(
        "Process-Engine-API adapter '{}': wiring of BPMN process '{}' (file '{}', workflow module '{}') is implemented in a later story",
        adapterId,
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

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    // Task subscription (polling workers) is a later story; the Process-Engine-API has no
    // per-module "start processing" concept, so there is nothing to do here yet.
    log.debug(
        "Process-Engine-API adapter '{}': start workflow processing of workflow module '{}'",
        adapterId,
        workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    log.debug(
        "Process-Engine-API adapter '{}': stop workflow processing of workflow module '{}'",
        adapterId,
        workflowModuleId);

  }

}
