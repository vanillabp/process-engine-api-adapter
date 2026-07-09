package io.vanillabp.pea.deployment;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.pea.PeaAdapter;
import io.vanillabp.pea.PeaBpmnModel;
import io.vanillabp.pea.PeaProcessingContext;

/**
 * The Process-Engine-API adapter's deployment service - one instance per configured
 * adapter id (not per type).
 * <p>
 * Skeleton stage: the type/id getters are implemented so the adapter can be discovered
 * by the platform integrations, but all pipeline methods throw
 * {@link UnsupportedOperationException} - reading, wiring and deploying BPMN against the
 * Process-Engine-API are separate feature stories. Never turn these into silent stubs:
 * a silent stub would hide wiring bugs of later stories.
 */
public class PeaDeploymentService implements AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext> {

  private final String adapterId;

  public PeaDeploymentService(
      final String adapterId) {

    this.adapterId = adapterId;

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

    throw new UnsupportedOperationException("readBpmn is implemented in a later story");

  }

  @Override
  public PeaProcessingContext prepareBpmn(
      final String workflowModuleId,
      final PeaProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model) {

    throw new UnsupportedOperationException("prepareBpmn is implemented in a later story");

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final PeaBpmnModel model,
      final PeaProcessingContext context) {

    throw new UnsupportedOperationException("wireBpmn is implemented in a later story");

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) throws IllegalStateException {

    throw new UnsupportedOperationException("deployResources is implemented in a later story");

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    throw new UnsupportedOperationException("startWorkflowProcessing is implemented in a later story");

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final PeaProcessingContext bpmsProcessingContext) {

    throw new UnsupportedOperationException("stopWorkflowProcessing is implemented in a later story");

  }

}
