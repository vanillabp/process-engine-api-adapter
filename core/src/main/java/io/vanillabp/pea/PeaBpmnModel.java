package io.vanillabp.pea;

/**
 * The adapter's BPMN model type ({@code BPMN} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}).
 * <p>
 * The bpm-crafters Process-Engine-API deliberately has no BPMN model abstraction: its
 * {@code DeploymentApi} only accepts opaque {@code NamedResource}s (filename + bytes).
 * Because VanillaBP wires business code per BPMN process id, the adapter needs at least
 * the process id in addition to the raw resource - so this record carries it explicitly.
 * <p>
 * The {@code bpmnProcessId} is extracted from the BPMN XML by
 * {@code PeaDeploymentService.readBpmn} using a JDK StAX parser. See {@code GAPS.md} (first
 * entry): a BPMS-agnostic API without a model type forces every consumer to parse BPMN
 * itself.
 *
 * @param filename The name of the BPMN resource (used for logging and deployment)
 * @param resource The raw BPMN XML bytes
 * @param bpmnProcessId The id of the executable process contained in the resource
 */
public record PeaBpmnModel(String filename, byte[] resource, String bpmnProcessId) {

}
