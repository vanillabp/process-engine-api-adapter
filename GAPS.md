# Gaps found while building the Process-Engine-API adapter

This adapter is developed **mock-first**: instead of binding to a real BPMS it runs
against a hand-written in-memory fake of the bpm-crafters
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api) (module `mock/`).
The point of doing so is to discover, feature by feature, where either **VanillaBP** or
the **Process-Engine-API** needs an extension in order to express a VanillaBP feature.
Every such finding is recorded here, so the list grows as feature stories are
implemented.

Each entry states: what VanillaBP needs, what the Process-Engine-API offers (or lacks),
and the consequence for the adapter.

## 1. The Process-Engine-API has no BPMN model type

**Needed by VanillaBP:** the adapter SPI is generic over a BPMN *model type*
(`AdapterDeploymentService<BPMN, PC>`). VanillaBP wires business code per BPMN process
id, so the adapter must be able to hand a per-process model object through the deployment
pipeline (`readBpmn → prepareBpmn → wireBpmn → deployResources`).

**Offered by the Process-Engine-API:** nothing. The `DeploymentApi` only accepts a
`DeployBundleCommand` carrying opaque `NamedResource`s (a filename plus raw bytes). There
is no parsed model, and in particular no notion of "the executable process id(s)
contained in this resource". A BPMS-agnostic API without a model type forces *every*
consumer to parse the BPMN XML itself just to learn the process ids — exactly the kind of
BPMS-specific work VanillaBP tries to hide.

**Consequence for the adapter:** the adapter defines its own minimal model record in
`core`:

```java
public record PeaBpmnModel(String filename, byte[] resource, String bpmnProcessId) { }
```

For now the `bpmnProcessId` is supplied from the outside. Extracting process ids from the
BPMN XML (so `readBpmn` can return one entry per executable process) is a later story. A
proper fix on the Process-Engine-API side would be a lightweight, engine-independent
"deployment model" type exposing at least the executable process ids of a resource.
