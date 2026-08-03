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

**Resolved for now:** `PeaDeploymentService.readBpmn` parses the BPMN XML with the JDK's
StAX streaming parser (`javax.xml.stream`) and returns one `PeaBpmnModel(filename, resource,
bpmnProcessId)` per `<bpmn:process isExecutable="true">`. This is exactly the BPMS-specific
work VanillaBP tries to hide - a proper fix on the Process-Engine-API side would be a
lightweight, engine-independent "deployment model" type exposing at least the executable
process ids of a resource.

## 2. Built-in start commands cannot carry a non-default `ExecutionMode`

**Needed by VanillaBP:** VanillaBP's two-phase start maps onto the Process-Engine-API's
`ExecutionMode` - **phase one = `PREFLIGHT_CHECK`, phase two = `SYNC`** (issue #281). So the
adapter must start a process with a specific, non-default execution mode.

**Offered by the Process-Engine-API:** `ExecutionModeAware.executionMode()` is a Kotlin
`default` method returning `ExecutionMode.DEFAULT`, and the concrete start commands
(`StartProcessByDefinitionCmd`, `StartProcessByMessageCmd`, …) are `data class`es that do
**not** override it and are `final` (not extensible). There is therefore no built-in start
command whose execution mode is `PREFLIGHT_CHECK` or `SYNC`.

**Consequence for the adapter:** the adapter supplies its own `StartProcessCommand`
implementation, `PeaStartProcessCommand`, carrying the BPMN process id, the payload and the
`ExecutionMode`. A real (non-mock) engine that dispatches by matching the *concrete* command
type would not recognise it - so a clean fix would be either an execution-mode setter/field
on the built-in commands or an official "generic" start command.

## 3. No dedicated business-key / correlation slot on the start command

**Needed by VanillaBP:** the workflow aggregate has a 1:1 relation to the workflow instance;
the aggregate id must travel with the start so the instance can later be found by aggregate
id.

**Offered by the Process-Engine-API:** `StartProcessCommand` only carries a payload
(`PayloadSupplier`, i.e. process variables) - there is no dedicated business-key /
correlation-id slot (unlike Camunda 7's business key).

**Consequence for the adapter:** the aggregate id is passed as an ordinary payload variable
named after the aggregate's ID property (`AggregatePersistenceAware.getAggregateIdName()`) -
how the aggregate's ID is stored in the BPMS is the adapter's decision. This matches how
Camunda 8 handles it, so it is acceptable; documented here because it is not a first-class
concept of the API.

## 4. Deploying "for a workflow module" (module-as-tenant) is not expressible

**Needed by VanillaBP:** workflow-module isolation - the same BPMN process id may exist in
two modules and must not clash (Camunda 7 uses the module id as tenant id for this).

**Offered by the Process-Engine-API:** `DeployBundleCommand` has an optional `tenantId`, but
that is the underlying BPMS' multi-tenancy - not a VanillaBP workflow-module namespace.
There is no notion of "deploy these resources for workflow module X".

**Consequence for the adapter:** `deployResources` deploys to the default tenant (no
`tenantId`). Module isolation currently relies on unique BPMN process ids across modules
(same limitation as Camunda 8). Confirmed with the mock: the deployed bundle has
`tenantId == null`.

## 5. A `PREFLIGHT_CHECK` cannot be validated against deployed processes (mock)

The in-memory mock cannot honestly validate a `PREFLIGHT_CHECK` against the
deployed processes: deployed resources are opaque byte streams (see gap 1 - the
Process-Engine-API has no BPMN model type), so the mock does not know which BPMN
process ids a deployment contains. A `PREFLIGHT_CHECK` for an undeployed process id
therefore succeeds in the mock instead of failing. Tests needing a failing
preflight inject it explicitly via `InMemoryProcessEngine.failPreflightFor(...)`
(and `failNextSyncFor(...)` for phase-two retry testing) - pretending validation
would hide exactly the class of bugs the mock-first approach is meant to surface.

## 6. Task deliveries do not carry the BPMN process id (routing gap)

**Needed by VanillaBP:** a delivered service task must be routed to the
`@WorkflowService` responsible for its BPMN process - the workflow-task registry is
keyed by (workflow module, BPMN process id). The task definition alone is not
sufficient: the same task definition may legally appear in several processes.

**Offered by the Process-Engine-API:** `TaskInformation` has a free-form
`meta: Map<String, String>` but no defined key for the BPMN process id;
`SubscribeForTaskCmd` subscribes by `taskDescriptionKey` only.

**Consequence for the adapter:** adapter convention - the engine (the mock, and any
real PEA implementation used underneath) is expected to supply the meta entry
`bpmnProcessId` (`PeaTaskHandler.META_BPMN_PROCESS_ID`). Without it the adapter
falls back to routing by task definition, which only works while the definition is
unique across the module's processes; an ambiguous definition without the meta
entry fails the delivery with a guiding message. A defined meta-key vocabulary in
the API would remove this convention.

## 7. BPMN task-definition naming is not defined by the API

**Needed by VanillaBP:** at deployment time the adapter must know which service
tasks exist in a BPMN process and under which name ("task definition") the engine
will deliver them, to validate `@WorkflowTask` wiring and to subscribe.

**Offered by the Process-Engine-API:** nothing - resources are opaque
(`NamedResource`), and the API does not define how a BPMN service task maps to a
`taskDescriptionKey`.

**Consequence for the adapter:** the adapter parses the BPMN itself (StAX) and
applies the `zeebe:taskDefinition type="..."` convention (service, send, business
rule and script tasks). Engines with a different convention (e.g. Camunda 7 topic
names) would need an adapter-side switch. A defined mapping in the API would make
this portable.

## 8. Completion commands cannot carry a non-default `ExecutionMode`

**Needed by VanillaBP:** task completions issued by the adapter after the local
transaction committed must run in the engine-synchronous phase-two shape
(`ExecutionMode.SYNC`), consistent with the two-phase pattern used everywhere else.

**Offered by the Process-Engine-API:** `CompleteTaskCmd`, `CompleteTaskByErrorCmd`
and `FailTaskCmd` are `ExecutionModeAware` via a `default` method returning
`ExecutionMode.DEFAULT` - the built-in command classes offer no constructor or
setter to choose a different mode.

**Consequence for the adapter:** own subclasses (`PeaCompleteTaskCmd`,
`PeaCompleteTaskByErrorCmd`, `PeaFailTaskCmd`) override `executionMode()` to return
`SYNC`. Constructor/builder support for the execution mode on the built-in commands
would remove the subclasses.
