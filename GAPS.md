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

## 9. The BPMN attribute carrying the task definition is engine-specific

**Needed by VanillaBP:** at deployment time the adapter parses the BPMN itself (gap 7)
and must know WHERE a service-like task's definition is written - and that place
differs per engine underneath the Process-Engine-API: Camunda 8 uses
`<bpmn:extensionElements><zeebe:taskDefinition type="kkk"/></bpmn:extensionElements>`,
ZenBPM uses `zenbpm:taskDefinition`, other engines use just the task element's id
(`<bpmn:serviceTask id="kkk">`).

**Offered by the Process-Engine-API:** nothing - the API neither parses BPMN nor
defines the mapping.

**Consequence for the adapter:** currently hard-wired to the `zeebe:taskDefinition`
convention. The extraction strategy should become CONFIGURABLE per adapter instance
(e.g. `vanillabp.adapters.<id>.task-definition-source` = `zeebe` | `zenbpm` |
`task-id`, extensible for other namespaces) so the same adapter serves different
engines. To be implemented in a later story; documented here so the limitation is
tracked.

## 10. Command failures are untyped - awareness cannot distinguish "unknown" from "unavailable"

**Needed by VanillaBP:** the awareness contract distinguishes `UNKNOWN_TO_BPMS` (a
successful query found nothing - the next prioritized adapter may be probed) from
`BPMS_UNAVAILABLE` (infrastructure failure - NEVER fall back, the unavailable engine
might hold the task). Wrongly mapping an outage to "unknown" could route an operation
to the wrong BPMS in a migration scenario.

**Offered by the Process-Engine-API:** command futures fail with untyped exceptions -
there is no error classification (no "not found" vs. "unreachable" distinction, no
typed exception hierarchy).

**Consequence for the adapter:** the awareness probe (a `PREFLIGHT_CHECK` completion)
maps EVERY failure to `UNKNOWN_TO_BPMS`. Acceptable mock-first (the in-memory engine
is never unavailable), but a real PEA implementation underneath needs typed errors -
or the adapter needs an engine-specific failure classifier - before multi-BPMS
migration setups are safe. A defined exception taxonomy in the API would solve this.

## 11. `CorrelateMessageCmd`/`StartProcessByMessageCmd` are FINAL - no execution-mode transport

**Needed by VanillaBP:** message correlation and start-by-message follow the two-phase
pattern like every other engine-advancing operation: phase one should express
`ExecutionMode.PREFLIGHT_CHECK` (validate/probe without advancing) and phase two
`ExecutionMode.SYNC`. For service/user-task completions the adapter subclasses the
command classes to carry the mode (entry 8).

**Offered by the Process-Engine-API:** `CorrelateMessageCmd` and
`StartProcessByMessageCmd` are FINAL Kotlin data classes whose
`ExecutionModeAware.executionMode()` default (`DEFAULT`) cannot be overridden, and
`CorrelationApi.correlateMessage` takes the concrete class (no interface to implement
instead). `StartProcessApi` takes the `StartProcessCommand` interface, but a custom
implementation would not be recognized by real PEA implementations which type-check
on the final command classes.

**Consequence for the adapter:** correlation and start-by-message travel with
`ExecutionMode.DEFAULT` - the intended SYNC phase-two semantics cannot be signalled,
NO phase-one preflight is possible, and `awarenessOfWorkflow` cannot be probed at all
(no query API either): the adapter answers workflow awareness OPTIMISTICALLY
(`ACTIVE`, warned once) - fine for single-BPMS setups, unsafe for multi-BPMS
migration scenarios. Opening the command classes (or accepting interfaces) plus a
workflow-existence query would resolve this. The mock treats a DEFAULT-mode
`StartProcessByMessageCmd` as the phase-two start (documented in the mock).

Follow-up (election story 25): the START re-dispatch mitigation probes
`awarenessOfWorkflowForRedispatch` before re-dispatching a recovered start entry.
That probe must NEVER be optimistic (a wrong "known" skips the start and LOSES the
workflow), so the adapter overrides it to an honest `UNKNOWN_TO_BPMS` - recovered
starts always proceed and duplicate starts remain possible within the documented
at-least-once residual. A workflow-existence query in the Process-Engine-API would
enable the mitigation here, too.

## 12. No repository API - deployed processes cannot be read back

**Needed by VanillaBP:** the viewer API (`ProcessService#getProcessDefinitions` and
`#getBpmnXml`, story 26) answers "which process definitions does this workflow use, and
what is their BPMN XML" - a BPMN viewer renders the diagram from it.

**Offered by the Process-Engine-API:** nothing. `DeploymentApi.deploy` returns a
`DeploymentInformation` (deployment key, time, tenant) and that is it: there is no way to
ask the engine for deployed processes, their versions or their BPMN resources - not even
for the deployment just made. There is also no process-definition identity at all (the
deployment key identifies a BUNDLE, not a process version).

**Consequence for the adapter:** the adapter keeps what VanillaBP's deployment pipeline
read at boot (`PeaDeployedProcesses`, one instance per adapter id, shared by the
deployment and the process service) and serves the viewer API from it:

- the adapter-native process definition id is composed as `<workflow module>|<bpmn process
  id>` (the core namespaces it with the adapter id),
- the reported "version" is the deployment key - the only version-ish information the API
  offers,
- a workflow running on a definition deployed by a PREVIOUS application version is served
  with the currently deployed model.

A lightweight repository API (list deployed processes, fetch a resource by process
definition id/version) would resolve this.

## 13. No query/history API - workflows have no observable timeline

**Needed by VanillaBP:** `ProcessService#getWorkflowHistory` (story 26) reports when a
workflow started/ended and which elements were executed (start/end time, canceled, error,
and for call activities a context to dig into the called instance).

**Offered by the Process-Engine-API:** nothing. Neither running nor ended workflows can be
queried; task subscriptions are the only observation channel, and they only report tasks
this very application is subscribed to while they are open.

**Consequence for the adapter:** `getWorkflowHistory` reports the definition plus
`elementsHistory == null` - the SPI's documented "not supported by the underlying BPMS" -
and therefore never hands out a `secondaryWorkflowHistoryContext`; call activities cannot
be drilled into. Call-activity definitions are not reported either (gap 1: no BPMN model
type, so which sub-process a call activity addresses is not resolvable BPMS-agnostically).
Together with gap 12 this makes the Process-Engine-API the weakest of the supported BPMS
for viewing workflows - a query API for process instances and their element instances
would resolve it.

## 14. One engine per application - two adapter ids of this type are not expressible

**Needed by VanillaBP:** VanillaBP's migration feature configures several adapter ids of
ONE BPMS type side by side (e.g. two Camunda 8 clusters) - new workflows start in the
first-priority one while existing workflows are located in the others. Story 34 asks
every adapter to validate that its configured ids actually address DIFFERENT systems.

**Offered by the Process-Engine-API:** nothing to distinguish them by. The engine is
handed to the adapter as a set of application-provided beans (`StartProcessApi`,
`DeploymentApi`, `TaskSubscriptionApi`, ...) and there is no per-instance connection
configuration - not even a notion of "which engine is this". Two configured
`process-engine-api` adapter ids therefore end up talking to the very same engine.

**Consequence for the adapter:** `validateDistinctAdapterInstances` fails the boot as
soon as more than one adapter id of type `process-engine-api` is configured, naming the
reason. A migration between two engines behind the Process-Engine-API cannot be
expressed today; qualifying the API beans per adapter instance (or a connection
configuration owned by the API) would resolve it.
