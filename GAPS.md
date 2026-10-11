# Gaps found while building the Process-Engine-API adapter

This adapter is built **mock-first**. It does not talk to a real BPMS (business process
management system). It runs against an in-memory fake of the bpm-crafters
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api), written by hand in
the module `mock/`. We do it this way to find out, feature by feature, where **VanillaBP** or
the **Process-Engine-API** needs something more to express a VanillaBP feature. Each such
finding is written down here, so the list grows with every feature story.

Each entry says three things: what VanillaBP needs, what the Process-Engine-API offers or
lacks, and what that means for the adapter.

## When these go to bpm-crafters

Not one at a time. On 2026-09-14 Stephan decided to take the whole list to bpm-crafters once
VanillaBP 2.0 is finished. So the request arrives with everything we learned, not as a stream
of single asks. Until then this file is the record. An entry which is ready to be sent says so
and carries the wording.

Nothing here blocks the adapter. Where an entry names a way to live without the API change,
that way is built, and the entry says which one.

## 1. The Process-Engine-API has no BPMN model type

**Needed by VanillaBP:** the adapter SPI is generic over a BPMN *model type*
(`AdapterDeploymentService<BPMN, PC>`). VanillaBP wires business code per BPMN process id. So
the adapter must be able to pass one model object per process through the deployment pipeline
(`readBpmn → prepareBpmn → wireBpmn → deployResources`).

**Offered by the Process-Engine-API:** nothing. The `DeploymentApi` only takes a
`DeployBundleCommand`, which carries opaque `NamedResource`s: a file name plus the raw bytes.
There is no parsed model. In particular, nothing says which executable process ids a resource
contains. An API which hides the BPMS but has no model type makes *every* user parse the BPMN
XML on their own, just to learn the process ids. That is exactly the kind of BPMS-specific work
VanillaBP tries to hide.

**Consequence for the adapter:** the adapter defines its own small model record in `core`:

```java
public record PeaBpmnModel(String filename, byte[] resource, String bpmnProcessId) { }
```

**Resolved for now:** `PeaDeploymentService.readBpmn` parses the BPMN XML with the JDK's StAX
streaming parser (`javax.xml.stream`). It returns one `PeaBpmnModel(filename, resource,
bpmnProcessId)` per `<bpmn:process isExecutable="true">`. This is exactly the BPMS-specific
work VanillaBP tries to hide. A proper fix on the Process-Engine-API side would be a small
"deployment model" type which does not depend on any engine. At the least, it would tell the
executable process ids of a resource.

## 2. Built-in start commands cannot carry a non-default `ExecutionMode`

**Needed by VanillaBP:** VanillaBP's two-phase start maps onto the `ExecutionMode` of the
Process-Engine-API: **phase one is `PREFLIGHT_CHECK`, phase two is `SYNC`** (issue #281). So
the adapter must start a process with a specific execution mode, not the default one.

**Offered by the Process-Engine-API:** `ExecutionModeAware.executionMode()` is a Kotlin
`default` method which returns `ExecutionMode.DEFAULT`. The concrete start commands
(`StartProcessByDefinitionCmd`, `StartProcessByMessageCmd`, …) are `data class`es. They do
**not** override the method, and they are `final`, so nobody can extend them. So no built-in
start command has the execution mode `PREFLIGHT_CHECK` or `SYNC`.

**Consequence for the adapter:** the adapter brings its own implementation of
`StartProcessCommand`, called `PeaStartProcessCommand`. It carries the BPMN process id, the
payload and the `ExecutionMode`. A real engine (not the mock) which picks its action by the
*concrete* command type would not recognise it. A clean fix would be one of two things: a
setter or field for the execution mode on the built-in commands, or an official "generic"
start command.

## 3. No dedicated business-key / correlation slot on the start command

**Needed by VanillaBP:** a workflow aggregate belongs to exactly one workflow instance, and
the other way round. The aggregate id must travel with the start, so the instance can be
found by it later.

**Offered by the Process-Engine-API:** `StartProcessCommand` only carries a payload
(`PayloadSupplier`, which means process variables). There is no slot of its own for a business
key or correlation id, unlike the business key of Camunda 7.

**Consequence for the adapter:** the aggregate id is passed as an ordinary payload variable.
The variable is named after the aggregate's ID property
(`AggregatePersistenceAware.getAggregateIdName()`). How the aggregate's ID is stored in the
BPMS is up to the adapter. Camunda 8 does it the same way, so this is acceptable. It is written
down here because the API has no concept of its own for it.

## 4. Deploying "for a workflow module" (module-as-tenant) is not expressible

**Needed by VanillaBP:** workflow modules are kept apart. The same BPMN process id may exist in
two modules, and the two must not clash. Camunda 7 uses the module id as the tenant id for
this.

**Offered by the Process-Engine-API:** `DeployBundleCommand` has an optional `tenantId`. But
that is the multi-tenancy of the BPMS underneath, not a namespace for a VanillaBP workflow
module. There is no way to say "deploy these resources for workflow module X".

**Consequence for the adapter:** `deployResources` deploys to the default tenant, without a
`tenantId`. For now, keeping modules apart relies on BPMN process ids which are unique across
all modules. Camunda 8 has the same limit. The mock confirms it: the deployed bundle has
`tenantId == null`.

## 5. A `PREFLIGHT_CHECK` cannot be validated against deployed processes (mock)

The in-memory mock cannot honestly check a `PREFLIGHT_CHECK` against the deployed processes.
Deployed resources are opaque byte streams (see gap 1: the Process-Engine-API has no BPMN
model type). So the mock does not know which BPMN process ids a deployment contains. A
`PREFLIGHT_CHECK` for a process id which was never deployed therefore succeeds in the mock
instead of failing. A test which needs a failing preflight sets it up explicitly with
`InMemoryProcessEngine.failPreflightFor(...)`, and with `failNextSyncFor(...)` to test retries
in phase two. A mock which only pretends to check would hide exactly the kind of bug the
mock-first approach is meant to bring to light.

## 6. Task deliveries do not carry the BPMN process id (routing gap)

**Needed by VanillaBP:** a delivered service task must reach the `@WorkflowService` which is
responsible for its BPMN process. The registry of workflow tasks is keyed by workflow module
and BPMN process id. The task definition alone is not enough, because the same task definition
may legally appear in several processes.

**Offered by the Process-Engine-API:** `TaskInformation` has a free-form
`meta: Map<String, String>`, but no defined key for the BPMN process id.
`SubscribeForTaskCmd` subscribes by `taskDescriptionKey` only.

**Consequence for the adapter:** the adapter sets a convention. The engine, which is the mock
or any real PEA implementation underneath, is expected to fill the meta entry `bpmnProcessId`
(`PeaTaskMeta.BPMN_PROCESS_ID`). Without it the adapter falls back to routing by task
definition. That only works while the definition is unique across the processes of the
module. If a definition is ambiguous and the meta entry is missing, the delivery fails with a
guiding message. That message also names the BPMN process ids which the module declares
without deploying a model under them. The reason: a delivery nobody can place may belong to a
workflow which still runs under the old id of a renamed process (entry 23). If the API defined
a set of meta keys, this convention would not be needed.

**The value is the id the ENGINE knows, not the one the application wrote.** Where a workflow
module puts a prefix in front of its identifiers, the engine holds the process under
`<module>__<process>`. It fills the meta entry with that name. Everything inside VanillaBP,
though, is keyed by the plain id. So the entry has to be translated back before anything is
looked up with it, and `PeaTaskMeta.plainBpmnProcessId` is the one place which does that. At
one time the two task handlers read the entry in different ways. As long as that lasted, a
service-task delivery from an engine which fills the entry was routed nowhere.

**Ready to be sent.** The smallest change which unblocks us is a defined meta key with the BPMN
process id of the delivered task, filled by the engine adapters. The constant for it already
exists: `CommonRestrictions.PROCESS_DEFINITION_KEY`. Its documentation calls it the definition
attribute of a BPMN process from XML, holding the id of the element. So no new words are
needed. Another way would also work: `subscribeForTask` could honour
`restrictions[PROCESS_DEFINITION_KEY]` and list that key in `getSupportedRestrictions()`. Then
we could open one subscription per BPMN process id and tell the deliveries apart by their
subscription. The restriction map and the capability query are already part of the API. So
this needs support in the engine adapters, not a change of the interface. We would prefer the
meta key, because it helps with every delivery we cannot place, not only with the renamed
process of gap 23.

## 7. BPMN task-definition naming is not defined by the API

**Needed by VanillaBP:** at deployment time the adapter must know which service tasks a BPMN
process has. It must also know the name under which the engine will deliver each of them (the
"task definition"). It needs both to check the `@WorkflowTask` wiring and to subscribe.

**Offered by the Process-Engine-API:** nothing. Resources are opaque (`NamedResource`), and the
API does not say how a BPMN service task maps to a `taskDescriptionKey`.

**Consequence for the adapter:** the adapter parses the BPMN itself (StAX) and follows the
`zeebe:taskDefinition type="..."` convention. It does so for service, send, business rule and
script tasks. An engine with a different convention, for example the topic names of
Camunda 7, would need a switch in the adapter. If the API defined the mapping, this would work
on every engine.

## 8. Completion commands cannot carry a non-default `ExecutionMode`

**Needed by VanillaBP:** the adapter completes tasks after the local transaction committed.
These completions must run in the shape of phase two, where the engine works synchronously
(`ExecutionMode.SYNC`). That is the same two-phase pattern used everywhere else.

**Offered by the Process-Engine-API:** `CompleteTaskCmd`, `CompleteTaskByErrorCmd` and
`FailTaskCmd` are `ExecutionModeAware` through a `default` method which returns
`ExecutionMode.DEFAULT`. The built-in command classes have no constructor or setter to choose
another mode.

**Consequence for the adapter:** the adapter has its own subclasses (`PeaCompleteTaskCmd`,
`PeaCompleteTaskByErrorCmd`, `PeaFailTaskCmd`). They override `executionMode()` to return
`SYNC`. If the built-in commands took the execution mode in a constructor or builder, the
subclasses could go.

## 9. The BPMN attribute carrying the task definition is engine-specific

**Needed by VanillaBP:** at deployment time the adapter parses the BPMN itself (gap 7). It must
know WHERE the definition of a service-like task is written, and that place differs between
the engines behind the Process-Engine-API. Camunda 8 uses
`<bpmn:extensionElements><zeebe:taskDefinition type="kkk"/></bpmn:extensionElements>`. ZenBPM
uses `zenbpm:taskDefinition`. Other engines just use the id of the task element
(`<bpmn:serviceTask id="kkk">`).

**Offered by the Process-Engine-API:** nothing. The API does not parse BPMN, and it does not
define the mapping.

**Consequence for the adapter:** for now the adapter is fixed to the `zeebe:taskDefinition`
convention. The way it finds the definition should become CONFIGURABLE per adapter instance,
for example `vanillabp.adapters.<id>.task-definition-source` = `zeebe` | `zenbpm` | `task-id`,
open for other namespaces. Then the same adapter serves different engines. A later story will
build it. It is written down here so the limit is not forgotten.

## 10. Command failures are untyped - awareness cannot distinguish "unknown" from "unavailable"

**Needed by VanillaBP:** the awareness contract tells two answers apart. `UNKNOWN_TO_BPMS`
means a query worked and found nothing, so the adapter next in priority may be asked.
`BPMS_UNAVAILABLE` means the infrastructure failed. Then there is NEVER a fall-back, because
the engine which is down might hold the task. If an outage is wrongly mapped to "unknown",
an operation can reach the wrong BPMS during a migration.

**Offered by the Process-Engine-API:** the futures of a command fail with untyped exceptions.
Errors are not classified: there is no "not found" versus "unreachable", and no hierarchy of
typed exceptions.

**Consequence for the adapter:** the awareness probe is a `PREFLIGHT_CHECK` completion, and it
maps EVERY failure to `UNKNOWN_TO_BPMS`. That is acceptable while we work mock-first, because
the in-memory engine is never down. But before migrations between several BPMS are safe, a
real PEA implementation underneath needs typed errors. The other way is a failure classifier in
the adapter for each engine. A defined set of exception types in the API would solve this.

The same blindness reaches phase two. `isPhaseTwoFailureRepeatable` answers `false` for an
`UnsupportedOperationException` only. Everything else the engine refuses arrives as one untyped
`ExecutionException`. So an engine which refuses an operation for good looks like an engine
which is away for a moment. The outbox repeats the call until the entry blocks, and nothing
says so any earlier. This hurts most for a start, because the workflow the application asked
for does not exist while the retries run. If the engine behind the API is Camunda 7, a model
with an expression which cannot be evaluated is exactly such a start.

## 11. `CorrelateMessageCmd`/`StartProcessByMessageCmd` are FINAL - no execution-mode transport

**Needed by VanillaBP:** message correlation and start by message follow the two-phase pattern,
like every other operation which moves the engine on. Phase one should say
`ExecutionMode.PREFLIGHT_CHECK`, which checks without moving anything. Phase two should say
`ExecutionMode.SYNC`. For completions of service tasks and user tasks, the adapter subclasses
the command classes to carry the mode (entry 8).

**Offered by the Process-Engine-API:** `CorrelateMessageCmd` and `StartProcessByMessageCmd` are
FINAL Kotlin data classes. Their `ExecutionModeAware.executionMode()` default (`DEFAULT`)
cannot be overridden. `CorrelationApi.correlateMessage` takes the concrete class, so there is
no interface to implement instead. `StartProcessApi` does take the `StartProcessCommand`
interface. But real PEA implementations check for the final command classes, so they would not
recognise an implementation of our own.

**Consequence for the adapter:** correlation and start by message travel with
`ExecutionMode.DEFAULT`. So the adapter cannot signal the SYNC meaning of phase two, and NO
preflight in phase one is possible. `awarenessOfWorkflow` cannot be probed at all, because
there is no query API either. So the adapter answers workflow awareness OPTIMISTICALLY with
`ACTIVE` and warns once. That is fine with one BPMS, and unsafe for a migration between
several. Two changes would solve it: open command classes (or methods which take interfaces),
and a query whether a workflow exists. The mock treats a `StartProcessByMessageCmd` in DEFAULT
mode as the start of phase two, and the mock says so in its documentation.

A follow-up from the BPMS election: when a start entry is recovered, the adapter probes
`awarenessOfWorkflowForRedispatch` before it dispatches the start again. That probe must NEVER
be optimistic. A wrong "known" skips the start, and the workflow is LOST. So the adapter
overrides it with an honest `UNKNOWN_TO_BPMS`. Recovered starts always go ahead, and a start
may still happen twice, within the documented "at least once" rest. A query in the
Process-Engine-API whether a workflow exists would allow the protection here, too.

## 12. No repository API - deployed processes cannot be read back

**Needed by VanillaBP:** the viewer API (`ProcessService#getProcessDefinitions` and
`#getBpmnXml`) answers two questions: which process definitions does this workflow use, and
what is their BPMN XML? A BPMN viewer draws the diagram from that.

**Offered by the Process-Engine-API:** nothing. `DeploymentApi.deploy` returns a
`DeploymentInformation` with the deployment key, the time and the tenant, and that is all. The
engine cannot be asked for deployed processes, their versions or their BPMN resources, not even
for the deployment just made. There is also no identity of a process definition at all: the
deployment key names a BUNDLE, not a process version.

**Consequence for the adapter:** the adapter keeps what VanillaBP's deployment pipeline read at
boot, in `PeaDeployedProcesses`. There is one instance per adapter id, shared by the deployment
and the process service. The viewer API is answered from it:

- the adapter's own process definition id is built as `<workflow module>|<bpmn process id>`
  (the core puts the adapter id in front of it),
- the "version" it reports is the deployment key, the only thing near a version the API offers,
- a workflow which runs on a definition deployed by an EARLIER version of the application is
  shown with the model deployed now.

An extension asks this same record instead of reading the deployed bytes a second time. It
answers a process by its workflow module and process id. It answers a user task by its BPMN
element id or by its external form reference (decision 10 in the repository's `DECISIONS.md`).
It still only knows what this version of the application deployed. No index can close that
part of the gap.

A small repository API would solve this: list the deployed processes, and fetch a resource by
process definition id and version.

## 13. No query/history API - workflows have no observable timeline

**Needed by VanillaBP:** `ProcessService#getWorkflowHistory` reports when a workflow started
and ended, and which elements ran. For each element it reports the start and end time, whether
it was canceled or ended in an error, and for a call activity a context to look into the called
instance.

**Offered by the Process-Engine-API:** nothing. Workflows cannot be queried, neither running
nor ended ones. Task subscriptions are the only way to watch anything. They only report tasks
this one application subscribed to, and only while the tasks are open.

**Consequence for the adapter:** `getWorkflowHistory` reports the definition plus
`elementsHistory == null`. In the SPI's documentation that means "not supported by the
underlying BPMS". So it never hands out a `secondaryWorkflowHistoryContext`, and nobody can look
into a call activity. Call activity definitions are not reported either. Gap 1 is the reason:
without a BPMN model type, the sub-process a call activity points at cannot be found without
knowing the BPMS. Together with gap 12, this makes the Process-Engine-API the weakest of the
supported BPMS for viewing workflows. A query API for process instances and their element
instances would solve it.

## 14. One engine per application - two adapter ids of this type are not expressible

**Needed by VanillaBP:** VanillaBP's migration feature configures several adapter ids of ONE
BPMS type side by side, for example two Camunda 8 clusters. New workflows start in the one with
the first priority, while existing workflows are found in the others. VanillaBP asks every
adapter to check that its configured ids really point at DIFFERENT systems.

**Offered by the Process-Engine-API:** nothing to tell them apart by. The engine reaches the
adapter as a set of beans the application provides (`StartProcessApi`, `DeploymentApi`,
`TaskSubscriptionApi`, ...). There is no connection configuration per instance, and not even an
idea of "which engine is this". So two configured adapter ids of type `process-engine-api` end
up talking to the very same engine.

**Consequence for the adapter:** as soon as more than one adapter id of type
`process-engine-api` is configured, `validateDistinctAdapterInstances` stops the boot and names
the reason. Today nobody can express a migration between two engines behind the
Process-Engine-API. It would work if the API beans were qualified per adapter instance, or if
the API owned a connection configuration.

## 15. No isolation mechanism - workflow modules cannot be kept apart by the engine

**Needed by VanillaBP:** a workflow module is a scope of visibility. Its BPMN process ids,
message names, error codes and task definitions must not clash with those of another module of
the same application. So the default name-clash-avoidance mode `BY_ADAPTER` asks the adapter to
use whatever isolation its BPMS offers. Camunda 7 and Camunda 8 use a tenant named after the
workflow module.

**Offered by the Process-Engine-API:** nothing. There are no tenants, no namespaces and no scope
per deployment. `DeploymentApi` takes the resources and nothing else, and a task subscription
matches a task type across the whole engine. Whether the engine behind the API has tenants
(Camunda 7 has) cannot be seen through it.

**Consequence for the adapter:** `BY_ADAPTER` is refused while a workflow module is deployed.
The boot fails and names the module and the two other modes (`use-prefix`, `none`). On purpose,
the adapter does not quietly switch to `none`. That would deploy every module into one scope,
and a clash would only show up as the wrong process being started. The core also asks whether
this adapter's own isolation keeps two given workflow modules apart, and it gets the same
answer: it keeps none of them apart. That is why two modules under one BPMN process id end in a
refused boot, and not in a model which is gone without anybody noticing (gap 24). `use-prefix`
works without help from the engine. But then the adapter has to rewrite the raw BPMN XML by
element name (gap 1: no BPMN model type), and that only covers the dialects whose element names
it knows. A tenant or a deployment scope in the API would solve it.

## 16. The API never reports a process the engine started on its own

**Needed by VanillaBP:** a workflow may be started by the BPMS instead of by the application,
through a timer, signal or conditional start event. VanillaBP then has to build the workflow
aggregate before anything of the process runs, because everything after that is addressed by
it: tasks are routed by the aggregate's ID, and expressions read its attributes. Camunda 7
offers an execution listener at the process start for that, and Camunda 8 an execution listener
on the start event.

**Offered by the Process-Engine-API:** nothing. `TaskSubscriptionApi` delivers TASKS, and the
`ProcessApi` starts processes for the application. No subscription, event or callback says "the
engine just started this process". So the application never sees a start it did not ask for.

**Consequence for the adapter:** `wireBpmn` refuses a process with such a start event. Its
guiding message names the element and the ways out: start the workflow from the application,
or run the module on a BPMS whose adapter can serve it. The refusal goes through the policy for
failed deployments, so an adapter which is not first in priority turns it into a warning.
Deploying the process quietly would produce workflows without an aggregate. The first task
delivery would then fail with "no aggregate", once per instance and with no way back. A
"process started" notification, or a subscription for it, would solve it.

That refusal covers the models this application deploys and nothing else. VanillaBP also asks a
BPMS which start events fire on their own in a version it still HOLDS. With that it judges a
`@WorkflowStartedByBpms` method kept for a BPMN process id which the application declares
without deploying a model under it: the old id of a renamed process (gap 23). Reading a held
model needs a repository (gap 12) and a version to read it for (gap 19). So this adapter answers
`null`, and the check says nothing about such an id. A method which names a start event no held
model has goes unnoticed here, as it did before the check existed. And a declared-only id is
the only place where a `@WorkflowStartedByBpms` method can live on this adapter at all, because
a deployed model with such a start event is refused.

## 17. No notification when a process instance ends

**Needed by VanillaBP:** an application may ask to be told when a workflow ended
(`@WorkflowEnded`), instead of putting a service task in front of every end event. Camunda 7
serves that with an execution listener at the process scope, and Camunda 8 with an `end`
execution listener on the process element.

**Offered by the Process-Engine-API:** nothing. `TaskSubscriptionApi` subscribes to TASKS, and
`ProcessInformation` is what a start returns. No event, callback or subscription reports that
an instance ended. Whether the engine behind the API knows it (Camunda 7 does) cannot be seen
through the API.

**Consequence for the adapter:** `wireBpmn` logs a WARN. It names the BPMN process and says
that the method will never be called. It does not fail the deployment, on purpose. A start
event the engine fires on its own is different: there the workflow could not run at all. Here
the workflow runs fine, and only the notification is missing, so stopping the boot would be too
much. A "process instance ended" subscription in the API would solve it.

Both gaps are part of a bigger one: the API has no concept of listeners at all. Some models
have elements which hand an application a moment of the engine's own, such as a Camunda 7
`camunda:executionListener` or a Camunda 8 `zeebe:taskListener`. A `@WorkflowTask` method
serves those where the adapter's `allow-listeners` allows it. Such a model cannot move to this
adapter, because there is nothing here to map it onto.

## 18. The payload of a running process instance cannot be updated

**Needed by VanillaBP:** an application may tell the BPMS that the workflow aggregate changed
(`aggregateChanged`), so the engine sees the current state before it evaluates anything else.
Camunda 7 writes the shared values at the scope of the workflow or of a task. Camunda 8 sends
`SetVariables` for the process instance or for the element instance.

**Offered by the Process-Engine-API:** changes to the payload for TASKS only.
`UserTaskModificationApi` takes a `ChangePayloadModifyTaskCmd` (update, delete, clear) which
names a task id. `CorrelateMessageCmd` carries a payload into a waiting message subscription.
No command and no API works on a running process instance.

**Consequence for the adapter:** both phases of `aggregateChanged` throw an exception with a
guiding message. It names the ways out: complete a task of the workflow, which does send the
shared values, or run the workflow module on a BPMS whose adapter can do it. Phase ONE already
refuses. So the application sees the failure where it makes the call, and not later in an
outbox dispatch after the commit. A command to change the payload of a process instance would
solve it.

## 19. No version of the deployed process definition

**Needed by VanillaBP:** an application may serve several versions of a deployed process with
different methods (`@WorkflowTask(version = ...)`, and the same for `@WorkflowStartedByBpms`
and `@WorkflowEnded`). That needs two things from the BPMS. First, the version a delivered task
belongs to. Second, where the method names a version TAG, which versions carry which tag.

**Offered by the Process-Engine-API:** neither, as a contract. `TaskInformation` carries a
free-form `meta` map, and the API names the key `processDefinitionVersionTag`
(`CommonRestrictions`). But nothing makes an engine implementation fill it. There is no
version number and no order of deployments anywhere. `DeploymentInformation` reports a
deployment key and a time, not the versions the deployment produced. No API answers "the
versions of this process".

**Consequence for the adapter:** where the engine fills the version tag in the meta map of a
task, the adapter reports it, so `version = "release-2024"` works there. The adapter has no
catalog of versions, and it says so while it wires a process
(`WorkflowTaskWiring#reportNoProcessVersionCatalog`, `ReportedProcessVersion.VERSION_TAG`). So
the start names the methods whose version a delivery here can never match: a RANGE over tags
(`>release-2024`, `v1.0..v2.0`), and anything made of numbers. There is one warning per BPMN
process, and the application keeps booting. The reason: the same method can be the right one
on another BPMS of the list in priority order, one which does count versions. Applications which
do not use the attribute are not affected. A method without `version` keeps serving every
version. Where the engine fills no tag, it is the only kind of method a delivery reaches at all.
A task whose methods all name versions fails the delivery, with a message which says so. A
`processDefinitionVersion` in the task meta, plus a query for "the versions of this process",
would solve it.

The same warning at the start reaches the methods of an extension, and one case still slips
through. The Business Cockpit picks the details provider of a user task or of a workflow by the
version of the process. It uses the same attribute and the same matching. The extension tells
the core that its calls carry the version of the process. So the core asks its methods the same
question it asks a `@WorkflowTask` method, and a provider naming a number or a range is named
at the start like any other method. A provider naming exactly one tag gets through that check,
because a delivery here can carry a tag. The delivery then finds nothing whenever the engine
fills no tag, and that is the usual case.

Why nobody hears about it: a `@WorkflowTask` method behaves differently. A task whose methods
all name versions fails the delivery and says why. For a details provider, though, finding no
matching method is an ALLOWED answer. The cockpit then sends the details it filled in itself,
just as it does for every workflow module which brings no provider at all. So a provider chosen
by version never runs here, and nothing is logged about it. The application loses the extra
details it wrote, and the only sign is a case which shows less in the cockpit than the developer
expected. The two additions named above solve this one too. With a version per task and a query
for the versions of a process, such a provider is picked here as on any other BPMS.

## 20. No way to check the older versions the engine still holds

**Needed by VanillaBP:** a BPMS keeps every version of a process it was ever given, and
workflows keep running on them. The application, though, brings only its newest model. So at
startup VanillaBP asks each BPMS which versions it still holds. It reads the models of the older
ones and reports the task definitions which no `@WorkflowTask` method serves any more. That
happens before those workflows run into an incident. It also asks how many workflows still run
on a version. The answer decides whether a finding is a warning or a defect. It also tells an
application which fades a version out (`outfaded-versions`) what it leaves behind.

**Offered by the Process-Engine-API:** none of it. There is no idea of a version at all (see
gap 19). `DeploymentInformation` names a deployment, not the processes or models it produced.
So nobody can ask "which versions exist" or "what does version X look like". Counting the
workflows of a version needs the version first, so that fails for the same reason.

**Consequence for the adapter:** the adapter reports no deployed version, and that switches the
whole check off for it. The core skips a BPMS which counts no versions instead of guessing.
Fading a version out has no effect here either, because there is no version to name. An
application on this adapter learns about a dropped task definition the way it did before: when a
workflow reaches it. What the adapter does say is that the catalog is missing (gap 19). That is
a different statement. It is about the methods of the application, not about the versions of
the engine.

A catalog is asked more than the task definitions of a version, and this adapter answers none of
those questions either: `startEventsOfVersion` for the start events a held version fires on its
own (gap 16), `concurrentTokenElementsOfVersion` for the elements which can put a second token
into one of its workflows (gap 21), and `activeInstanceCountOf` for the workflows still running
on it. Each keeps the SPI default `null`, which means "this BPMS cannot say". So every check
which reads one of them stays silent here. It does not judge a version by an answer nobody gave.

A `processDefinitionVersion` per task, plus a query for the deployed versions and their models,
would close gap 19 and this one together.

The same gap covers a BPMN process which the application RENAMED. The application declares a
process id (`@WorkflowService(secondaryBpmnProcesses = ...)`) without deploying a model under
it. The core asks every adapter what its BPMS holds for such an id. So the workflows which still
run under the old name are checked like every other older version. This adapter answers nothing
there either, because `processVersionCatalogOf` keeps the default. So the check says nothing
about a rename, for the reasons above.

## 21. Models cannot be read, so concurrent tokens cannot be reported

**Needed by VanillaBP:** as soon as a BPMN process can hold more than one token, two branches
write the same workflow aggregate. Without a version attribute, one of the two writes is lost
without a word. So while VanillaBP wires a process, it asks each adapter which elements can put
a second token into a running workflow. These are a non-interrupting boundary event, a forking
parallel or inclusive gateway, a parallel multi-instance activity and a non-interrupting event
subprocess. The core turns the answer into one guiding WARN per process whose aggregate has no
version attribute.

**Offered by the Process-Engine-API:** nothing to read a model with (see gap 1 and gap 12). The
adapter never holds a BPMN model, neither the one it deploys nor the one the engine runs. So
there is nothing to search for those elements.

**Consequence for the adapter:** it reports nothing, and that switches the hint off for
applications on this adapter. The core stays silent instead of guessing from a missing answer.
The rest of the support for concurrent tokens works here, because it does not need the model.
VanillaBP owns the transaction of a task. It notices a version conflict in its commit, logs the
guiding message and passes the exception on to this adapter. What the engine behind the
Process-Engine-API does with the failed task is up to the engine, as with every failure.

The versions the engine still holds are out of reach for the same reason. VanillaBP asks for the
second-token elements of a version which workflows still run on. It asks exactly because those
workflows run longest: a parallel gateway which the newest model dropped still forks every
workflow started before. This adapter has no version to ask about (gap 19) and no model to walk
through, so it answers nothing there either.

## 22. No binding from a business rule task to a decision, so decision ids cannot be scoped

**Needed by VanillaBP:** a workflow module may bring decision tables, and they are deployed with
its processes. A module can be kept apart from another one by a PREFIX on its identifiers,
instead of by an isolation of the engine. Then everything the engine looks up across definitions
is renamed: process ids, message names, and the ids of the decisions. A business rule task
points at the decision it calls, and that reference is renamed with them, so both sides still
name the same thing. The Camunda adapters do exactly that (`camunda:decisionRef` and
`zeebe:calledDecision`).

**Offered by the Process-Engine-API:** it deploys opaque `NamedResource`s and nothing else.
There is no model to read (gap 1). Nothing says how the engine behind the API binds a business
rule task to a decision. So there is no reference this adapter could rewrite.

**Consequence for the adapter:** the decision tables are deployed unchanged, decision ids
included. So under `use-prefix` the decisions of a module are NOT protected against a decision
with the same id in another module. The last deployment wins in whatever the engine uses as its
namespace. Renaming the decisions but not the tasks which call them would be worse: every
business rule task would point at nothing. That is why the decision id stays what the modeller
wrote.

What an application can do about it: give its decisions ids which are unique across all modules
deployed to that engine. It would have to do the same for every identifier if the engine had no
isolation at all (gap 15 is the same story one level up). A binding this adapter could read, or
a deployment which takes a namespace, would close this gap.

## 23. The workflows of a renamed process' old id can only be served where the name says so

**Needed by VanillaBP:** a workflow module may declare a BPMN process id under which it deploys
nothing. That is the old id of a renamed process, declared with
`@WorkflowService(secondaryBpmnProcesses = ...)`. The engine still holds the old models and the
workflows which run on them. Both Camunda adapters keep serving those workflows. They read the
models their BPMS holds under the declared id and build their subscriptions or connectables from
them. The rule behind it is decision 38 of the platform's DECISIONS.md: a check or a delivery
must not depend on which version of the application deployed the model. The two adapters find
the tasks of the old id in different ways. Camunda 7 reads the models its engine holds. Camunda
8 builds its workers from what the application serves and reads no model for it (decision 19 of
its own DECISIONS.md).

**Offered by the Process-Engine-API:** nothing to read. There is no repository (gap 12), so the
models the engine holds under the old id cannot be fetched. What is left is what the application
itself still serves, and the core answers that without any model:
`taskWiringOfProcessesNobodyDeployed` names the declared ids, and for each of them the task
definitions the `@WorkflowTask` methods serve.

**Consequence for the adapter:** the subscriptions of a declared id are BUILT from that answer,
the way the Camunda 8 adapter builds its workers (decision 11 of `DECISIONS.md`). By default a
module puts a prefix on its identifiers. Then a task definition carries the id of the process it
was deployed with. So the name of a task of the old id can be built from the old id alone. One
subscription for it serves exactly one process, and every such delivery is routed correctly.
Each built name is subscribed twice, once as an asynchronous task and once as a user task,
because nothing outside the model says which of the two it was.

**What stays open is the other mode.** Under `none`, and under `use-prefix` with
`prefix-task-definitions-per-process: false`, the old and the new process share one task
definition and so one subscription. The adapter opens nothing of its own. The old id joins the
existing subscription, so that its `@TaskParam` names are asked for, and a delivery is assigned
to a deployed process as before. Only the meta key of gap 6 can tell the two apart, which is why
that entry helps this one. The start says which of the two cases a declared id is in. A delivery
which cannot be routed at all names the old id as one of the possible reasons.

One case is served by nothing. A declared id may have only methods wired by BPMN element id
(`@WorkflowTask(id = ...)`). Then it names no task definition, and no name can be built. The
start warns and names both ways out: wire those methods by task definition, or keep the old
model deployed under its old id until its workflows have ended.

Everything else a declared id cannot get stays as it was. Its end is not reported (gap 17). That
needs no model, and the warning is the same as for a deployed process. Nobody can read what its
held versions start on or where they fork (gaps 16 and 21), so nothing is said about either. And
the viewer API has nothing to show for it, because showing it needs the model. Since 2026-09-14
it says so, instead of answering an empty list without a word. A repository API (gap 12) would
close those, and none of them is what this entry is about.

## 24. The engine cannot be asked which identifiers it already holds

**Needed by VanillaBP:** a name clash check which sees more than the deployment it is part of.
VanillaBP compares the scoped identifiers an application deploys against each other. It also
asks the BPMS which of them somebody else already holds: the BPMN process id of another
application, the decision id of a module deployed years ago, a message name a held version still
declares. The adapter asks its BPMS, and the core writes the warning. It is a warning and never
the end of a boot, because the other side may be an application which runs correctly (decision
38 of the platform's DECISIONS.md).

**Offered by the Process-Engine-API:** nothing, for any kind of identifier. There is no
repository to search (gap 12), so neither a process id nor a decision id can be looked up. There
is no model to read either (gap 1). So the names which live only inside a BPMN model cannot be
read out of what the engine holds: a message name, a signal name, a BPMN error code, an
escalation code. `DeploymentInformation` answers a deployment key, a time and a tenant, and none
of the three says which identifiers are now taken.

**Consequence for the adapter:** the adapter asks nothing and reports nothing about what the
engine holds. In the SPI that silence has a meaning: an adapter which cannot ask its BPMS never
calls `reportIdentifiersTheBpmsAlreadyHolds`. And `processVersionCatalogOf` keeps answering
`null`, so the question about a held version is not asked either.

What this adapter does bring are the two checks which need no engine at all, because they read
the deployment itself.

The first check is about BPMN process ids. Before a workflow module goes to the engine, the
adapter hands the core the module id and the process id of everything that module brings. If
two of those pairs reach the engine under one id, the boot ends there. The comparison spans
workflow modules, and the core is what makes it span them. The core remembers which module
reached the BPMS under which identifier. So the clash is found while the second of the two
modules deploys, with the first one already in the engine. It has to span modules, because the
one engine behind this API keeps no module apart from another (gap 15). An id which an earlier
module took is taken for the next one as well. Where nothing has a prefix and the BPMS is meant
to keep two modules apart, the core asks the adapter whether its own isolation does that. This
adapter answers that it keeps nothing apart, which is gap 15 once more.

The second check is about the names inside a model. While the module deploys, the adapter reads
every message name, signal name, error code, escalation code and task definition out of the
models. Once the deployment succeeded, it hands the plain names to the core. So a name which two
workflow modules of this application share is found here. The adapter reads them in every mode,
and it has to. Under `use-prefix` the module id is part of every name, so two modules cannot
collide. The finding only exists under `none`, where nothing has a prefix and the names would
otherwise not be read. A task definition carries the BPMN process it belongs to, because that is
its scope. It matters as much as the other names here: a task subscription of this API matches a
task type across the whole engine (gap 15). So if two modules use one task definition, one
module's subscription takes the other module's tasks. This check has the same limit as the
prefixing. Both read the raw BPMN by element name, so a dialect whose element names the adapter
does not know declares nothing it can see (gap 1). A decision id stays out of the check. This
adapter never scopes one (gap 22), and the scoped form the core would build is not the form the
engine sees.

The names only warn, while the process ids end the boot. The difference follows what a finding
costs. A shared message name leaves both models in the engine and makes one name ambiguous. Two
processes under one id mean the engine kept one model and lost the other.

What stays invisible is every identifier somebody else put into the engine. Read this together
with gap 15: an engine which keeps nothing apart and cannot be asked what it holds leaves it to
the application to keep identifiers unique, across every module deployed to it. A repository API
(gap 12) would open the same path the Camunda adapters take.

## 25. The API does not say what a failing task handler means

**Needed by VanillaBP:** a delivery which cannot be finished has to be repeatable. Since
decision 12 in the repository's DECISIONS.md, an observer which fails makes the user-task
delivery fail. That only works if the engine behind this API does something with a failed
delivery. Offering it again later is the way to recover. Taking the task away from the
application would be worse than the swallowed failure this replaced. So would moving the
workflow on as if nothing had happened.

**Offered by the Process-Engine-API:** nothing in writing. `TaskHandler` is a `BiConsumer`. Its
documentation says what it receives, and nothing about what an exception out of it means.
`TaskTerminationHandler` is the same. `SubscribeForTaskCmd` has no place to say what a
subscriber wants to happen. So each engine adapter decides on its own, and a subscriber cannot
read anywhere what it will get. Reporting a failure instead of throwing is only half an answer.
`ServiceTaskCompletionApi.failTask` takes a `FailTaskCmd` with an optional retry count and an
optional backoff. What an engine does with either is again not written down. And
`UserTaskCompletionApi` has no such command at all (entry 28).

**Consequence for the adapter:** the adapter measures instead of assuming, and it relies on as
little as it can. We measured on 2026-09-17 against the API's own reference implementation for
an embedded Camunda 7 (`process-engine-adapter-camunda-platform-c7-embedded-core` 2025.11.1,
Camunda 7.24 on H2). A USER-task delivery whose handler throws is logged as
`PROCESS-ENGINE-C7-EMBEDDED-038`. The task is deactivated for its subscription. The next pull
cycle hands it over again as a new delivery, again and again until the handler returns normally.
The task itself stays untouched in the engine, and no incident appears. A termination whose
handler throws is reported once. The failure leaves the pull cycle, and the same termination is
not offered again.

A SERVICE-task delivery works differently. We read it in the same version on 2026-09-27. A
handler which throws is logged as `PROCESS-ENGINE-C7-EMBEDDED-033`. The delivery ends in
`handleFailure` with one attempt less than the task had, so it does run out. A handler which
calls `failTask` instead ends up in `C7ServiceTaskCompletionApiImpl`. That class passes
`cmd.retryCount` on where the command names one. Where it does not, it falls back to its own
`FailureRetrySupplier`. The supplier the reference stage wires counts three attempts down to
zero, and Camunda 7 turns zero into an incident. So the retry count of the command is the one
lever a subscriber has here, and entry 28 is what it does not reach.

What the adapter takes from that: it logs the failure where it builds it. So if an engine only
prints the message, the stack trace and the other observers are still in the log. And the
javadoc of `PeaUserTaskObserver` asks an observer to cope with being called again, because a
working engine repeats the delivery here. An engine which drops a failed delivery without a word
costs that observer its notification, and the log line is what is left.

**Ready to be sent to bpm-crafters:** yes. The ask is one paragraph on `TaskHandler` and
`TaskTerminationHandler` which says what an exception out of them means for the task. The same
paragraph belongs in the guide for authors of engine adapters, so a subscriber can write code
against it. A command to answer a delivery as failed, with the engine deciding whether to repeat
it, would be more than we need.

## 26. A delivered task does not say which BPMN element it belongs to

**Needed by VanillaBP:** a `@WorkflowTask` method is wired by one of two keys. One is the task
definition (`@WorkflowTask(taskDefinition = ...)`), the other the id of the BPMN element
(`@WorkflowTask(id = ...)`). The core routes a delivery by both of them. So an adapter which
reports only the task definition leaves every method of the second kind unreachable. The
delivery record also uses the element id to name a task in the model. An operator needs it in
the engine's own tools, and an extension needs it to link a task to a place in the model.

**Offered by the Process-Engine-API:** nothing in writing. `TaskInformation` carries a free-form
`meta: Map<String, String>` and only defines `reason` and `retries`. So an engine adapter fills
in whatever it has. The API's own reference adapter for an embedded Camunda 7 fills `activityId`
(`CommonRestrictions.ACTIVITY_ID`). That is the value we want, but no engine has to fill it.

**Consequence for the adapter:** the adapter reads the meta entry where an engine fills it.
Otherwise it falls back to the models it deployed itself. A subscription asks for one task
definition. So once a delivery is routed to a BPMN process, the element with that name in that
process is the element the delivery belongs to. That covers the normal model and needs nothing
from the engine.

There are two cases it does not cover. First, a name may sit on SEVERAL elements of one
process. Then the model has no answer, and that is exactly the case in which a method is wired
by the element id. The start says so where such a method exists, and it names the elements and
the meta entry which would tell them apart. Second, a BPMN process id which the module declares
without deploying a model under it has no model to read at all. Entry 23 describes that.

**Ready to be sent.** The ask has the same shape as entry 6: a defined meta key with the BPMN
element id of the delivered task, filled by the engine adapters. The constant exists,
`CommonRestrictions.ACTIVITY_ID`, and the reference adapter already fills it. So this writes
down what one engine already does, without adding new words. Both asks are one paragraph in the
same place, and we would send them together.

## 27. A task cannot be asked about, so a cancellation cannot be worked out

**Needed by VanillaBP:** a BPMS which reports no cancellation leaves an application blind. The
workflow walked away from a task, and nothing tells the `@WorkflowTask` method which waits on
it. The core can work that out on its own. Whenever the BPMS wakes the application up, the core
looks at the other tasks it believes are open in the same workflow. For each one it asks the
adapter: does this BPMS still have it? The answer has three values, and the third is why it is
not a boolean. `GONE` reports the task to the application as canceled. `STILL_THERE` does
nothing, and `CANNOT_SAY` does nothing either. An adapter which turns a failed question into
`GONE` cancels every open task of a workflow whenever the engine has a short hiccup.

**Offered by the Process-Engine-API:** no way to ask. We read version 1.7 on 2026-09-20, the
newest on Maven Central. It has nine interfaces with an operation on them: deploy, start a
process, correlate a message, send a signal, evaluate a decision, subscribe for tasks, complete
a service task, complete a user task, modify a user task. Not one of them can be asked what the
engine holds, and a task is no exception. So the closest thing to a question is a
`PREFLIGHT_CHECK` completion, which is what `awarenessOfTask` already sends. Its failure says
nothing. The futures fail with an untyped `ExecutionException` (entry 10 above), so a refusal
and an engine which cannot be reached arrive as the same thing. A probe built on it could answer
`STILL_THERE` and `CANNOT_SAY`, but never `GONE`. It would cost a round trip per open task and
report nothing.

The API does bring the news once, in the other direction. A subscription registers a
`TaskTerminationHandler` next to its delivery handler. The engine pushes the `TaskInformation`
of a task which it had delivered and then took away. Two things keep that from answering the
question. First, it is a push and not a question. A task taken away while this application was
down is never reported, and nothing asks afterwards. Second, nobody wrote down what a
termination means. `TaskInformation` names `create`, `assign`, `update`, `complete` and `delete`
as constants of its free-form `meta` map. The API nowhere says which of them a termination
carries, or that it carries one at all. So reading a termination as "the task was canceled"
would rest on a string one engine happens to fill.

**Consequence for the adapter:** the adapter supplies no probe, which is the supported way to
say that it cannot answer. An application on this BPMS is told when a user task or an async task
is created, but never when the workflow walked away from it. The termination does reach the
user-task observers, but that is a technical audience and not the workflow. The section "What an
application hears about a canceled task" in `README.md` gives the whole picture for an
application. Decision 13 in the repository's `DECISIONS.md` says why nothing is supplied. And
`PeaOpenTaskProbeTest` makes sure that a later change cannot sneak in a probe which guesses.

**Ready to be sent.** Two asks, and either of them closes this. The first is an operation which
asks about one task by its id and answers whether the engine still has it. That is one round trip
and no search, so an engine can serve it without a read model. The second is the set of typed
exceptions that entry 10 asks for. A `PREFLIGHT_CHECK` completion which fails with a typed "no
such task" is already the question we need. The second is the smaller change and helps in more
places, so we would put it first.

A smaller thing would help even without either of them: say what a termination means. If the API
wrote down that a termination carries `reason`, and which of the values of `TaskInformation` it
carries, an adapter could turn the push into a cancellation for the tasks it was given. That
would not cover a task taken away while the application was down. So it adds to the two asks
above and does not replace them.

## 28. A user task cannot be handed back, so a stranger's user task ends in a log line

**Needed by VanillaBP:** a way to say "this delivery is not mine". An application may be given a
task of a workflow it does not own. It refuses the task, the BPMS keeps it, and whoever runs both
applications can read why. The core expresses that refusal as
`DeliveryOfAnUnknownWorkflowException`. Every adapter carries it out with the means of its BPMS:
Camunda 8 fails the job, and the cluster raises an incident.

**Offered by the Process-Engine-API:** enough on the side of service tasks, nothing on the side
of user tasks. `ServiceTaskCompletionApi` has `failTask`, and `FailTaskCmd` carries an optional
retry count. So a subscriber can report the failure and ask for no further attempt in one
command. `UserTaskCompletionApi` has `completeTask` and `completeTaskByError` and nothing else.
Both of them move the workflow on, and that must never happen to a workflow which belongs to
somebody else. There is no third operation. Throwing out of the handler is no way out either.
Entry 25 measured that the reference engine then offers the same user task again in every pull
cycle.

Nothing narrows the delivery down beforehand, and that is why this can happen at all. A task
subscription matches a task type across the whole engine, and there is no tenant (entry 15).
`SubscribeForTaskCmd` restricts by the values of `CommonRestrictions`, and each of them names one
running instance or one definition. None of them says "only the workflows this application
started", and nothing in the API could say it.

**Consequence for the adapter:** the two sides answer differently, and the difference is the
gap. `PeaTaskHandler` fails the stranger's service task with a retry count of zero. So an engine
which reads the count stops offering it, and the engines which know incidents raise one.
`PeaUserTaskHandler` writes one log line, which names the workflow and says that the task was
left untouched. Then it returns normally, because that is what keeps the same delivery from
coming round again in every pull cycle. So on user tasks the refusal shows in the log of the
application which got the wrong task, and nowhere else.
[Decision 14](./DECISIONS.md#14-a-task-of-a-workflow-this-application-does-not-own-is-refused-as-far-as-this-api-allows)
gives the reasons, and `PeaTaskHandlerTest` and `PeaUserTaskHandlerTest` test both halves.

**Ready to be sent to bpm-crafters:** yes, and it is the same ask as entry 25, seen from the
other end. A `failTask` on `UserTaskCompletionApi`, with the same optional retry count the
service-task command has, would let a subscriber answer a user-task delivery without touching the
workflow. What the engine does with it is still up to the engine. That is why the paragraph
entry 25 asks for is part of this ask and not a separate one.

## 29. Models cannot be read and no expression language is named, so no expression is reported

**Needed by VanillaBP:** an expression in a BPMN model reads the application's data, and what it
reads decides what it costs. An expression which names one variable reads a getter of the
workflow aggregate, and that is the shape VanillaBP recommends. An expression which reads a path
ties the model to the shape of the application's data. One which calls a method ties it to the
code. So while VanillaBP wires a process, it asks each adapter for every expression of that
process: the element it sits in, the place inside that element, and the text the engine
evaluates. The core turns that into one guiding message per process. It also counts how many of
the expressions already have the recommended shape.

**Offered by the Process-Engine-API:** nothing to read a model with (see entry 1 and entry 12),
and no expression language either. The adapter never holds a BPMN model, so there is no element
to walk through. It does parse the deployed BPMN XML with the JDK's streaming reader, far enough
to find the executable process ids. So the text of an attribute is within reach. But the text
alone does not answer the question. Nothing in the API says whether an expression is written as
`${...}`, as `#{...}`, with a leading `=` or in some other way. So the adapter cannot tell an
expression from a literal. Nothing says which places the engine behind the API evaluates either.
Calling a place an expression where the engine reads plain text would be wrong. If the adapter
guessed the delimiters of whatever engine somebody runs, its startup message would soon tell
people things which are not true.

**Consequence for the adapter:** it reports nothing, and that switches the message off for
applications on this adapter. The core stays silent instead of guessing from a missing answer,
the same way it does for the concurrent-token hint of entry 21. Nothing else of the feature
depends on it. The core reads the property which accepts the expressions of a model, and it works
here as everywhere. It just never has anything to accept.

The versions the engine still holds are out of reach for the same reason, and every adapter
gives that answer. Even where a model can be read, the expressions reported are those of the
model being deployed, because that is the model a developer can still change.

**Ready to be sent to bpm-crafters:** not on its own. It is entry 1 and entry 12 again, plus one
more thing an engine would have to say about itself: which expression language it evaluates, and
in which places. That belongs in the same ask as the model type, not next to it.

## 30. A start by message is not checked against the caller's process

**Needed by VanillaBP:** `ProcessService#startWorkflowByMessage` starts the process of the
`ProcessService` it is called on. A message which fires the start event of a different process
would start that other process instead, and VanillaBP would record the start under the process
of the caller. So while VanillaBP wires a process, the core asks each adapter which messages
start that process. Where an adapter answers, the core refuses a message the process does not
know with an `IllegalArgumentException`, before anything is started.

**Offered by the Process-Engine-API:** nothing to read a model with (see entry 1 and entry 12).
And `StartProcessByMessageCmd` names a message only. It cannot say which process definition the
message is meant for, so the engine starts whatever process knows the message.

**Consequence for the adapter:** it does not report the start messages of a process, so the core
does not check them here. It says so once per process while the application boots. A message
which starts a different process starts it, and the start row in VanillaBP stands under the
process of the caller. The reason: this adapter reports the `instanceId` of the answer as the
workflow it started, and it cannot learn about a start the engine made on its own (entry 16). An
application which only sends a process its own messages is not affected.

**Ready to be sent to bpm-crafters:** not on its own. Either of two things would close it: a way
to read the models (entry 1 and entry 12 again), or a start command which limits the message to
one process definition.

## 31. The answer to a start names no version, so the start row has one only where the engine adds it

**Needed by VanillaBP:** VanillaBP writes a row when a workflow starts. The row names the version
of the process definition the workflow runs on. An extension reads it to pick what it shows for
that version, from the moment the workflow starts. The adapter reports the version in phase two
of the start, next to the id of the started workflow. It uses the same form a delivered task of
that workflow carries, and here that form is the version tag (entry 19).

**Offered by the Process-Engine-API:** `ProcessInformation`, the answer to a start command, has
the `instanceId` and a free `meta` map. The API names no key for the version in that map. The
API's own reference adapter for an embedded Camunda 7 fills in the definition key, the business
key, the tenant and the root instance there, but no version tag.

**Consequence for the adapter:** it reads the tag from the `meta` map of the answer, under
`processDefinitionVersionTag`. A delivered task uses the same key for it. Where an engine adapter
puts the tag there, the start row carries it. Where it does not, as with the reference adapter
today, the row has no version. This adapter reports `ReportedProcessVersion.VERSION_TAG`. So a
reader of such a row learns that versions are reported here, and that a later delivery may still
bring the tag. It therefore waits for a delivery which carries one. Where the engine fills no tag
on its tasks either, that delivery never brings one.

**Ready to be sent to bpm-crafters:** together with entry 19. The answer to a start should name
the version tag of the started process definition, under the key `processDefinitionVersionTag`
which tasks already use.
