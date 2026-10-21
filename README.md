![Header](./readme/vanillabp-headline.png)

# VanillaBP Process-Engine-API adapter

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This is a BPMS adapter for [VanillaBP](https://www.vanillabp.io) Version 2. It is built on the
[bpm-crafters Process-Engine-API](https://github.com/bpm-crafters/process-engine-api)
(`dev.bpm-crafters.process-engine-api:process-engine-api`), an API which does not depend on one
BPMS (business process management system).

If you want to **use** this adapter, read the
[wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki). The VanillaBP concepts it
builds on are explained in the [VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).
This `README.md` is for contributors.

> **Experimental, mock first.** This adapter is developed against an in-memory fake of the
> Process-Engine-API (module `mock/`). The goal is to find out, one feature at a time, where
> VanillaBP or the Process-Engine-API needs an extension. These findings are collected in
> [`GAPS.md`](GAPS.md). What works so far: **BPMN parsing and deployment**, the
> **two-phase `startWorkflow`** (phase one `PREFLIGHT_CHECK`, phase two `SYNC`, proven end to end
> through `ProcessService#startWorkflow` with the JPA outbox against the mock), **task
> processing** (`@WorkflowTask` via task subscriptions), **completing and canceling asynchronous
> and user tasks**, **message correlation** (start by message included), the **BPMS election
> awareness probes**, the **viewer and history API** and the **aggregate sync**. The viewer API
> answers from what this application version deployed, because the Process-Engine-API has
> neither a repository API nor a history API. The aggregate sync sends the shared attributes as
> the payload of every command.

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fprocess-engine-api-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/process-engine-api-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fprocess-engine-api-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/process-engine-api-adapter/quarkus-report)

Coverage is measured for each platform on its own. The tests of one platform never count for the
code of the other. Click a badge to open its report.

## Why an adapter against the Process-Engine-API?

Like VanillaBP, the Process-Engine-API does not depend on one BPMS. It works on a lower level,
though: it offers commands, while VanillaBP describes the aspects of a workflow. A VanillaBP
adapter on top of it can therefore reach every BPMS the Process-Engine-API supports. Where the
Process-Engine-API cannot express a feature, the gap is written down, and an adapter for one
specific BPMS can fill it later.

This adapter uses **only the plain Process-Engine-API artifact**. It uses none of the existing
platform implementations or adapters of the Process-Engine-API, because they bind to a platform
and read their configuration in a different way than VanillaBP does. The Process-Engine-API is
written in Kotlin but can be used from Java without restrictions. This repository is pure Java and
adds no Kotlin build plugins. The Kotlin standard library comes in as a transitive dependency.

## ExecutionMode and VanillaBP's two-phase start

Since version 1.6, every Process-Engine-API command carries an
[`ExecutionMode`](https://github.com/bpm-crafters/process-engine-api/issues/281)
(`dev.bpmcrafters.processengineapi.ExecutionMode`): `DEFAULT`, `ASYNC`, `SYNC` or
`PREFLIGHT_CHECK`. This fits VanillaBP's two-phase start of a workflow directly:

- **phase one ≈ `PREFLIGHT_CHECK`**: validate only, run nothing;
- **phase two ≈ `SYNC`**: run it, in the transaction context of the caller.

So the adapter treats the Process-Engine-API as a **remote BPMS**. A workflow start goes through
VanillaBP's generic transaction outbox, like everything else this adapter sends to the engine.

`migration-adapter/README.md` of `adapter-platform-integration` draws that path twice, under
[Phase one and phase two](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#phase-one-and-phase-two-phasetwooutbox-spi):
once as the plain start, and once on a time line with a crash in it. The other mechanisms this
adapter plugs into are drawn there too, each in the section about it.
[`diagrams/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/diagrams)
lists them. In most pictures the Process-Engine-API is one branch next to Camunda 7 and
Camunda 8. This repository could not draw that comparison on its own.

## Coordinates

Parent (all modules are `2.0.0-SNAPSHOT`, groupId `io.vanillabp`):

|        Module         |                    Artifact                     |                   Purpose                    |
|-----------------------|-------------------------------------------------|----------------------------------------------|
| `core/`               | `process-engine-api-adapter`                    | platform-neutral adapter SPI implementations |
| `mock/`               | `process-engine-api-mock`                       | in-memory fake of the Process-Engine-API     |
| `spring-boot/`        | `process-engine-api-adapter-spring-boot`        | Spring Boot auto-configuration               |
| `quarkus/runtime/`    | `process-engine-api-adapter-quarkus`            | Quarkus extension runtime                    |
| `quarkus/deployment/` | `process-engine-api-adapter-quarkus-deployment` | Quarkus extension deployment                 |

### Spring Boot

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>process-engine-api-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Quarkus

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>process-engine-api-adapter-quarkus</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## Supported Process-Engine-API version

This adapter is built against `dev.bpm-crafters.process-engine-api` **1.7**. It has no release
lines. The
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter#release-lines)
has them: its artifacts carry the minor version of the cluster in their own version. Release lines
only pay off when two things are true. The engine a user runs is a remote service the adapter has
to be compiled against, and new minor versions of it keep coming. With the Process-Engine-API, the
engine sits behind the API, and the user chooses which engine that is.

What can change here is the API contract itself. So a new minor version of it arrives as a pull
request to look at, not as an automatic upgrade (`renovate.json`). That pull request also has to
bring the version above into this README. This adapter runs against exactly the one minor version
named here.

## Configuration

Configure the adapter as a VanillaBP adapter of type `process-engine-api`:

```yaml
vanillabp:
  prioritized-adapters:
    - pea
  adapters:
    pea:
      type: process-engine-api
```

By default the adapter runs against the in-memory mock engine
(`io.vanillabp.pea.mock.InMemoryProcessEngine`). To use a real Process-Engine-API implementation
instead, provide your own beans of the Process-Engine-API interfaces. On Spring Boot any bean
does this, on Quarkus any bean which is not a default bean.

### What the IDE knows about the keys

The Spring Boot module ships `META-INF/spring-configuration-metadata.json`. An IDE reads it to
offer the keys in `application.yaml` and to mark a key it does not know. The file has two parts,
see decision 21 in [`DECISIONS.md`](./DECISIONS.md):

- The configuration processor writes one entry for each map of `VanillaBpPeaProperties`, with the
  type of the map's values. An IDE which follows that type below a map key offers the keys for every
  adapter id, workflow module, workflow and task. We expect IntelliJ IDEA to do so, but nobody tried
  it in an IDE yet.
- `spring-boot/src/main/resources/META-INF/additional-spring-configuration-metadata.json` is
  written by hand. It describes every key of the adapter level once more, under the adapter id
  `process-engine-api`, with a type, a description and a default where there is one. Today the
  only key is the removed `fetch-variables`, deprecated with level `error`.

So a new key needs its field in the overlay of both platforms and an entry in the hand-written
file. `EveryKeyIsKnownToTheIdeTest` fails when the entry is missing, when an entry describes a key
nobody reads, and when a type or a default differs from the code.

The processor merges the hand-written file only while `javac` compiles. A change to that file alone
leaves the old metadata in `target`, and the test then reads the old file. Build with `clean` after
such a change.

The Quarkus module needs none of this. It is a Quarkus extension, and its build writes a
description of every key into `META-INF/quarkus-config-doc/`.

## Behavior and limitations

- **Deployment:** at startup the adapter parses the BPMN resources of each workflow module. It
  uses the StAX parser of the JDK and creates one entry per `<bpmn:process isExecutable="true">`.
  Then it deploys them through the `DeploymentApi` of the Process-Engine-API. The API has no
  namespace for a workflow module or a tenant which matches VanillaBP's, so the resources go to the
  default tenant. **Workflow modules are only kept apart if their BPMN process ids are unique
  across modules** ([`GAPS.md`](GAPS.md), entry 4).
- **Decision tables** of a workflow module are deployed together with its processes, as one more
  resource of the same bundle. The API takes resources without looking into them, so a DMN file
  needs nothing special here. A business rule task which names a decision does not need a
  `@WorkflowTask` method: whatever engine sits behind the API serves it. What this adapter cannot
  do is SCOPE a decision id under `use-prefix`. The API does not link a business rule task to its
  decision, so nothing could rename the two together ([`GAPS.md`](GAPS.md), entry 22). An
  application of this kind gives its decisions ids which are unique across its modules, just as it
  has to for its process ids (entry 4).
- **Name clashes between workflow modules** are checked as far as this adapter can check them.
  While a module deploys, the adapter reads the message names, signal names, error codes,
  escalation codes and task definitions from its BPMN files. It hands the plain names to the core.
  The core warns where another workflow module of the same application uses one of them. This is a
  warning and never stops the boot. A BPMN process id is different, it does stop the boot. The
  adapter hands the core the module id and the process id of every process it is about to deploy.
  The core remembers what the earlier workflow modules deployed. It refuses a module which would
  reach the engine under an id an earlier module already took. Nothing behind this API would keep
  the two apart ([`GAPS.md`](GAPS.md), entry 15), so the engine would keep one model and lose the
  other. These two checks are all there is here. The Process-Engine-API cannot tell which
  identifiers the engine already holds, of any kind. So nothing is said about what somebody else
  deployed to it ([`GAPS.md`](GAPS.md), entry 24). The reading covers the BPMN dialects whose
  element names the adapter knows (entry 1). A decision id is left out, because this adapter cannot
  scope one (entry 22).
- **Starting workflows** has two phases, because the Process-Engine-API is treated as a remote
  BPMS. Phase one maps to `ExecutionMode.PREFLIGHT_CHECK`: validate only, inside the transaction.
  Phase two runs after the commit, through the outbox, and maps to `ExecutionMode.SYNC`: create the
  instance. The aggregate id travels as a process variable. The variable is named after the ID
  property of the aggregate (`AggregatePersistenceAware.getAggregateIdName()`), because there is
  no slot for a business key ([`GAPS.md`](GAPS.md), entry 3). Phase two runs at least once and
  maybe more often. A crash after a successful start, but before the outbox entry is marked as
  done, can therefore create the instance twice. No registry will come to prevent this: a
  workflow is found by asking, not by remembering it (decision 5 of the platform's
  `DECISIONS.md`). What makes the window smaller is the probe the core runs before it dispatches
  a start again. This adapter cannot answer that probe, see [`GAPS.md`](GAPS.md), entry 11, and
  the remaining risk described by the
  [Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/blob/main/README.md#idempotency-limitation).
- **Platform coverage:** deployment is wired and has integration tests on **both platforms**.
  On Spring Boot these are `PeaDeploymentServiceTest` and `DeploymentIntegrationTest`. On Quarkus
  it is `PeaDeploymentPipelineTest`: the Quarkus platform integration runs the deployment
  pipeline at boot, and the test proves that `deployResources` reaches the in-memory mock engine
  (`InMemoryProcessEngine.getDeployments()`).
- **Task processing:** at `startWorkflowProcessing` the adapter opens ONE `TaskSubscriptionApi`
  subscription per distinct task definition in the BPMN files of the module. The task definition
  is the `zeebe:taskDefinition` type of a service, send, business rule or script task. The API
  does not define this mapping ([`GAPS.md`](GAPS.md), entry 7). The task wiring is checked during
  `wireBpmn`. A `@WorkflowTask` method which matches no task of any BPMN process of its workflow
  module stops the boot. The core checks this itself, once every adapter of the module has
  finished deploying (story 158). `OrphanMethodBootTest` holds it.
  A delivered task runs the `@WorkflowTask` method in a NEW local transaction. That transaction
  commits BEFORE the completion command is sent. This order means at-least-once, so handlers must
  be idempotent. The outcomes:
  a normal return leads to `completeTask`;
  a `TaskException` leads to `completeTaskByError` with the error code, and the changes to the
  aggregate stay committed (the contract of version 1);
  a handler with `@TaskId` leaves the task open, to be completed asynchronously;
  any other exception rolls back the local transaction and leads to `failTask`, and the engine
  underneath decides about retries.
  All completion commands carry **`ExecutionMode.SYNC`**. They run AFTER the local commit, which
  is the shape of phase two. The built-in command classes cannot carry a mode other than the
  default, so the adapter subclasses them ([`GAPS.md`](GAPS.md), entry 8). To route a delivered
  task to its BPMN process, the adapter relies on its own meta key `bpmnProcessId`
  ([`GAPS.md`](GAPS.md), entry 6). Without that meta entry, a task definition has to be unique
  across the processes of the module. If a completion fails AFTER the local commit, the adapter
  only writes a WARN: the engine delivers the task again, and the idempotent handler reaches the
  same result. A completion INTERRUPTED after that commit gets the same WARN. A shutdown
  interrupts the subscription thread, and the outcome does not reach the engine either way.
  Without the line, the delivery which follows would look unexplained.
- **Completing and canceling async tasks** (`ProcessService#completeTask` and `#cancelTask`):
  the awareness probe and the existence check of phase one are `ExecutionMode.PREFLIGHT_CHECK`
  completions. They validate only, which is exactly what the mode is for. The real completion or
  cancellation runs after the caller's commit, through the outbox, as a SYNC `completeTask` or
  `completeTaskByError`. A task which is already gone is accepted, as part of the at-least-once
  risk. Failures of the Process-Engine-API have no type. So a probe which fails cannot be told
  apart from an engine which cannot be reached, and the answer is "unknown"
  ([`GAPS.md`](GAPS.md), entry 10). This matters for setups which migrate between several BPMS.
  `@TaskEvent CANCELED` cannot be delivered. When a task ends, the subscription's termination
  callback carries the engine's `TaskInformation`: the task id, the reason and the rest of its
  meta map. It carries no payload, so the adapter cannot tell for which workflow aggregate the
  notification would run. The core cannot work the cancellation out for this adapter either,
  because there is no way to ask about a task. See
  [What an application hears about a canceled task](#what-an-application-hears-about-a-canceled-task).
  The mock keeps track of open tasks (`deliverTask` opens one, a SYNC completion closes it), so a
  preflight checks against real state.
- **User tasks:** a user task with an EXTERNAL `zeebe:formDefinition` reference is subscribed
  through the Task Subscription API with `TaskType.USER`. The reference is its task definition.
  A delivered user task is a CREATED notification to its `@WorkflowTask` method. The method never
  completes the task, and the task's ID arrives as `@TaskId`.
  CANCELED cannot be delivered: a termination carries no payload and so no reference to an
  aggregate. What it does carry reaches the user task observers of the application. See
  [Observing the user tasks of an application](#observing-the-user-tasks-of-an-application),
  and [What an application hears about a canceled task](#what-an-application-hears-about-a-canceled-task)
  for what this means for an application.
  `completeUserTask` and `cancelUserTask` run through the `UserTaskCompletionApi`. They use the
  same mapping as service tasks: PREFLIGHT_CHECK in phase one, SYNC in phase two. A notification
  which fails is logged loudly, but it never breaks the user task itself.
  A user task of a process which one of your `@WorkflowService` classes claims needs a method.
  Or it needs the line `implemented-externally=true`, which says that something else serves it, a
  task list for example. Without one of the two, the boot stops with the message of the core
  ([decision 17](./DECISIONS.md#17-a-user-task-needs-a-method-or-a-line-one-without-a-form-reference-the-line-only)).
  A user task without an external form reference never arrives here. The reference is the name a
  subscription asks for, so no method could serve such a task. In a claimed process it stops the
  boot unless the line marks it. The message says to add either the reference and a method, or
  the line.
- **Message correlation:** after the caller's commit, `correlateMessage` sends a
  `CorrelateMessageCmd` through the outbox, with `correlationKey = correlationId ?? aggregate ID`.
  No payload travels. `startWorkflowByMessage` sends a `StartProcessByMessageCmd` which carries
  only the variable with the aggregate ID. A LIMITATION ([`GAPS.md`](GAPS.md), entry 11): both
  command classes are FINAL. So they cannot carry the execution mode. There is no PREFLIGHT_CHECK
  in phase one, and the commands travel with the DEFAULT mode. It is also not possible to probe
  whether the BPMS knows a workflow. The adapter answers optimistically and writes a guiding WARN
  once. This is unsafe for setups which migrate between several BPMS.
- **Aggregate sync:** the API is a remote BPMS, so every attribute is shared unless
  `@NoSyncWithBPMS` excludes it. The shared attributes travel as the payload of every command sent
  for a workflow: the start, a task completion including `completeTaskByError`, the completion of
  an async task or a user task, and a correlation. The aggregate is read AFTER the local
  transaction of the handler has committed, in a transaction of its own. If that read fails, the
  task is still completed, with only the value of the aggregate ID, and a warning names the
  workflow. `aggregateChanged` cannot be expressed at all. The API changes the payload of TASKS
  only, so both phases throw with a guiding message ([`GAPS.md`](GAPS.md), entry 18). Phase ONE
  throws already, so the application sees the problem at its own call and not later in an outbox
  dispatch.
- **Starts the engine performs itself, and the end of a workflow** ([`GAPS.md`](GAPS.md),
  entries 16 and 17): nothing in the API reports either of them. A claimed process with a timer,
  signal or conditional start event is rejected during `wireBpmn`, because deploying it would
  create workflows without an aggregate. The rejection goes through the deployment failure policy,
  so an adapter which is not first in priority turns it into a warning. A `@WorkflowEnded` method
  only causes a WARN: the workflow runs fine, and only the notification is missing. A
  `@WorkflowStartedByBpms` method for a BPMN process id the application only DECLARES
  (`secondaryBpmnProcesses`) is not checked here at all. To tell whether a version the engine
  holds starts on that event, the adapter would need the model, and it cannot read it. So the
  check stays silent for that id.
- **Versions of a process** ([`GAPS.md`](GAPS.md), entry 19): the API has no version of a
  deployed process definition. So the adapter keeps no catalog of versions. It only reports the
  version TAG from the task meta, where the engine supplies it. The key is
  `processDefinitionVersionTag`, named by the API's `CommonRestrictions`. An exact tag therefore
  works, while a range of tags or any version given as numbers matches nothing
  (`PeaTaskHandlerTest#theVersionTagOfTheTaskMetaIsReported`). While it wires a process, the
  adapter SAYS that the catalog is missing (`WorkflowTaskWiring#reportNoProcessVersionCatalog`
  with `ReportedProcessVersion.VERSION_TAG`,
  `PeaDeploymentServiceTest#wiringSaysThereIsNoVersionCatalog`). This is more than just
  registering no catalog. The core then names the affected methods while the application boots,
  in one warning per BPMN process. Otherwise the developer would get messages about a BPMS which
  could not be reached. With no catalog, no question about a version the engine still holds can
  be answered: its task definitions, its start events, the elements which can fork one of its
  workflows, and how many workflows still run on it. So every check which reads one of these says
  nothing for this adapter ([`GAPS.md`](GAPS.md), entries 20 and 21).
- **Viewing workflows** (`ProcessService#getProcessDefinitions`, `#getBpmnXml` and
  `#getWorkflowHistory`): the answers come from what THIS application version deployed. The
  Process-Engine-API has neither a repository nor a query or history API ([`GAPS.md`](GAPS.md),
  entries 12 and 13). The process definition id native to this adapter is
  `<workflow module>|<bpmn process id>`. The reported version is the deployment key, and the BPMN
  XML is the deployed resource, byte for byte. The history reports the definition with
  `elementsHistory == null`, which in the SPI means "not supported by the underlying BPMS". So
  there are no secondary history contexts, and you cannot drill into call activities. Definitions
  of call activities are not reported either, because there is no BPMN model type.

Each bullet above has its tests. The two-phase start is `PeaTwoPhaseStartOutboxTest`. Task
processing and its four outcomes are `TaskProcessingIntegrationTest` on Spring Boot and
`PeaWorkflowLifecycleTest` on Quarkus. The delivered task itself is `PeaTaskHandlerTest` and
`PeaUserTaskHandlerTest`. The task operations and probes based on a preflight are
`PeaProcessServiceTaskOpsTest` (`awarenessProbesViaPreflight`, `phaseOneChecksExistence`,
`redispatchProbeIsNeverOptimistic`, `workflowsCannotBeLocated`). The payload is
`PeaSharedValuesTest`, the refusal of `aggregateChanged` is `PeaAggregateChangedTest`, the
signal is `PeaSendSignalTest`, and the viewer API is `PeaViewerApiTest`. That a start event the
engine fires itself is refused while deploying is held by `PeaDeploymentServiceTest`. That a
`@WorkflowEnded` method only warns is held by the WARN the Quarkus lifecycle test boots with.

## Only a claimed process is served

A BPMN file goes to the engine as a whole. So a workflow module may deploy a process which no
`@WorkflowService` class claims. The core stops the start because of such a process, unless the
application marks it with `vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`.
So a process which reaches this adapter unclaimed belongs to somebody else. `wireBpmn` returns
for it before it checks anything, and `startWorkflowProcessing` opens no subscription for its
tasks. The question is asked through `WorkflowTaskWiring#isClaimedByAWorkflowService`. The reason
is [decision 18](./DECISIONS.md#18-a-process-nobody-claims-gets-no-subscription-and-no-check).
`PeaUnclaimedProcessTest` holds it.

## What a subscription asks the engine for

A `SubscribeForTaskCmd` carries a set of payload variables. An EMPTY set means "give me
everything the process instance holds". A subscription which names nothing gets a copy of the
workflow aggregate with every delivery. But the handler reads the aggregate from its own database
anyway. So every subscription of this adapter names its set.

`PeaFetchVariables` holds both halves of the answer: the set a subscription names, and the
messages a delivery writes when somebody asks for a variable outside the set.
`PeaDeploymentService#fetchVariablesOf` works out the set once per subscription, while
`startWorkflowProcessing` opens the subscriptions. It starts from one `ServedTask` per BPMN task
the subscription serves, and it asks the core two questions:
`resolveWorkflowAggregateIdName` per BPMN process, and `taskParameterNames` per BPMN task. The
second question is asked with BOTH keys a method can be wired by: the task definition and the
BPMN element id. The reason is that a method with `@WorkflowTask(id = ...)` never mentions the
name the subscription was opened for. With the task definition alone, such a method could declare
a `@TaskParam` nobody fetched. Here a parameter which was not fetched makes the delivery fail, it
does not arrive as `null`. Subscriptions for service tasks and for user tasks both use this,
because a notification carries a payload like every other delivery.

There is a third question per BPMN task, `extensionTaskParameterNames`. It asks for the variables
the methods of the extensions read. The engine hands a task to exactly one subscription, so an
extension has no subscription of its own here. It reads what this subscription delivered, through
a user task observer. The question uses the keys an extension looks a task up by: the element id
first and the task definition second, which is the order of the Business Cockpit. A task of a
process nobody deployed has no element id, so it is asked by the task definition alone
(decision 20 in [`DECISIONS.md`](DECISIONS.md)).
`PeaFetchVariablesTest#aVariableOnlyAnExtensionReadsIsAskedFor` holds this in the core.
`UserTaskObserverIntegrationTest` on Spring Boot and `PeaUserTaskObserverTest` on Quarkus deliver
such a variable to an observer.

Only the core can answer these questions. This adapter never sees the BPMN model of a deployed
process ([`GAPS.md`](GAPS.md), entry 1). Unlike Camunda 8, it could not even guess the names from
the model. What it can do is ask which variables the `@WorkflowTask` methods and the extensions
read.

Two answers are the same as on Camunda 8, on purpose. Two adapters which answer one question
differently are exactly what the migration design is there to prevent. First, no configuration
asks for more than the derived set. Second, a BPMN process which no workflow service claims gets
no subscription at all. A handler which needs a value no `@TaskParam` declares reads it from the
workflow aggregate.

Snapshots of version 2.0 had the key `vanillabp.adapters.<id>.fetch-variables: all`. It made a
subscription ask for everything. The key is gone. The overlay in each platform module
(`VanillaBpPeaProperties`) still binds it on all four levels. It does this only so that
`PeaFetchVariables#rejectTheRemovedKey` can stop the start with a message which names every key
that sets it. On Quarkus this also keeps SmallRye from stopping the start with a message of its
own: there, a key which no registered mapping models makes the startup fail.

The handlers carry the `Selection` for their messages. `PeaTaskHandler` and `PeaUserTaskHandler`
name it when the variable with the aggregate id is missing. Their invocation contexts throw when
`getTaskParameter` is asked for a name outside the selection. For a `@TaskParam` with a fixed
name this can hardly happen. It is kept for a name a handler computes at runtime. That message
points to the workflow aggregate.

The mock engine cuts a delivered payload down to what the subscription asked for
(`InMemoryProcessEngine.ActiveSubscription#narrow`). Without this, the derived set would only be
asserted and never used. `PeaFetchVariablesTest` holds all of it, from
`theSubscriptionNamesWhatItReads` and `theUnionCoversEverythingTheSubscriptionServes`
through `aParameterOfAnIdWiredMethodIsAskedFor` to `aProcessNobodyClaimsGetsNoSubscription`
and `theRemovedKeyEndsTheStart`. `RemovedFetchVariablesBootTest` on Spring Boot and
`PeaRemovedFetchVariablesTest` on Quarkus hold that a start with the key set stops.

## What a `@TaskParam` receives

Turning a delivered value into the type a parameter declares is the job of the platform, not of
this adapter. The invocation context of `PeaTaskHandler` answers `getTaskParameter` with what the
delivered payload holds under that name, and it converts nothing. The platform's
`ValueConversion` takes it from there. Which types it accepts is written in one place:
[What a `@TaskParam` may be declared as](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#what-a-taskparam-may-be-declared-as-valueconversion)
in `migration-adapter/README.md`. It also says why a number which does not fit makes the
invocation fail instead of arriving cut down. The table there is the full answer and is not
copied here.

What belongs here is what this engine does to a value on the way. The answer is: nothing. The
Process-Engine-API carries a payload as `Map<String, Object>` from the start command to the
delivery, and the in-memory mock hands back the objects it was given. So a `BigDecimal` of
`120.50` reaches a `BigDecimal` parameter with its scale. The same value bound to a `Double`
reads `120.5`, which is the same number without the scale. Bound to an `int` it makes the task
fail, because 120 is a different number.

All of this was measured on 2026-09-16, against the in-memory mock in the Spring Boot module of
this repository. `TaskParameterTypesIntegrationTest` starts a workflow whose aggregate shares a
decimal, a big integer, a float and a long. It takes the payload the start command carried and
delivers it back as a task, with one test per variable and declared type.

A real Process-Engine-API backend was NOT measured. A backend running on Camunda 7 or Camunda 8
stores a variable in its own format. A decimal which went through that format can come back
without its scale. So the sentence about the scale holds for the mock. Anywhere else it is an
assumption.

A value which is refused costs the task, like any other failure of a handler. The local
transaction is rolled back, and the message the platform wrote goes to `failTask` as the reason.
The engine then applies its own retry rules, and whoever reads the incident reads what to change.

This adapter has no input mapping. A `@TaskParam` names a process variable directly. This is also
what the subscription asks the engine for (see
[What a subscription asks the engine for](#what-a-subscription-asks-the-engine-for)). So a value
nested inside another value can only be reached as the whole map. A parameter declared as `Object`
gets that map. Every other type is refused, with the message about a value which cannot be
converted at all.

## Observing the user tasks of an application

A task list, a cockpit or anything else which wants to WATCH the user tasks of an application
cannot open a subscription next to it. The Process-Engine-API delivers a task to exactly one
subscription. Its engine adapters pick the first subscription which matches a task and remember
it as the task's subscription. So a second subscription for the same task definition either sees
nothing or takes the task away from the workflow application. Which one happens depends on the
order the two were registered in. To watch along, a caller has to be called by the adapter's own
subscription, and only the adapter can set that up.

`io.vanillabp.pea.observation` does this:

```java
public interface PeaUserTaskObserver {
  void userTaskDelivered(PeaUserTaskObservation observation);
  void userTaskTerminated(PeaUserTaskObservation observation);
}

public record PeaUserTaskObservation(
    String adapterId, String workflowModuleId, String bpmnProcessId, String taskDefinition,
    String workflowAggregateId, TaskInformation taskInformation, Map<String, Object> payload) { }
```

On Spring Boot an observer is a bean of the interface, on Quarkus a CDI bean of it. Both
platform modules collect the observers they find. They hand them to every `PeaDeploymentService`
they build, which passes them on to the handlers of its user task subscriptions. An application
configures only one adapter id of this type, because a second one stops the boot
([`GAPS.md`](GAPS.md), entry 14). So one list serves it. The observation names the adapter its
task came from, and an observer reports under that name (decision 9 in
[`DECISIONS.md`](DECISIONS.md)).

An observer hears about EVERY delivery. It is called before the check which drops a delivery no
`@WorkflowTask` method claims. A task list shows a user task whether the application has code for
it or not.

An observer hears about the termination, with the reason the engine gave. The subscription
registers the `TaskTerminationHandler` overload of the API, not the older `Consumer<String>`, so
the whole `TaskInformation` arrives. What the reason means is up to the engine, and this adapter
passes it on without reading it. The reference adapter for an embedded Camunda 7 says `complete`
where a task was finished through the completion API, and `delete` for everything else it notices.
So there, a task somebody finished in a task list looks like a canceled one.

An observer acts neither on the task nor on the engine. It cannot claim, complete, cancel or
change a task, and it cannot stop a delivery from reaching the application. A user task is
completed through `ProcessService#completeUserTask`, as everywhere else.

The one thing about an observer the engine gets to see is a failure. An observer which throws
makes the delivery fail. All other observers are told first, so who hears about a task does not
depend on the order of the list. Then the first failure leaves the handler, with the failures of
the others attached to it as suppressed exceptions. A failure while describing the task for the
observers does the same, because an observer which sees nothing could not do anything either. The
message names the observer, the task and the workflow module. It is written to the log as well as
thrown, because an engine may print the message of a failed delivery without its stack trace.

The application does not pay for that. Its `@WorkflowTask` notification runs before the failure
leaves the handler. The core recognizes a repeated delivery by its task id, so the method is
called once per task, however often the engine delivers it.

What an engine does with a failed delivery is up to that engine, and the API says nothing about
it ([`GAPS.md`](GAPS.md), entry 25). The API's reference implementation for an embedded Camunda 7
logs the failure. It offers the task to the subscription again on its next pull, as a new
delivery, until it goes through. The user task stays where it is, and no incident is raised. A
termination is not offered again there. So an observer which fails on a termination does not hear
about that task a second time. Write an observer so that it survives being called again. Read
decision 12 in [`DECISIONS.md`](DECISIONS.md) for why swallowing the failure would have been
worse.

An application which registered no observer builds no observation at all. It behaves exactly as
it did before observers existed.

What an observation leaves open, it says, instead of guessing. `workflowAggregateId` is `null` in
three cases: where the BPMN process has no workflow aggregate in this application, where the
subscription did not ask the engine for the variable which holds it
([What a subscription asks the engine for](#what-a-subscription-asks-the-engine-for)), and for
every termination, because a termination carries no payload. `bpmnProcessId` is `null` where the
engine names none in its meta map and the subscription serves several processes.

`UserTaskObserverIntegrationTest` (Spring Boot) and `PeaUserTaskObserverTest` (Quarkus and
core) hold all of it.

## What an application hears about a canceled task

An application on this BPMS is told when a task arrives. It is never told when the workflow
moved on without it. Take a `@WorkflowTask` method which waits for a user task, or for an async
task it left open with `@TaskId`. It gets its CREATED notification, and no
`TaskEvent.Event.CANCELED` follows, whatever the workflow does next. A boundary event which takes
the task away does not reach the method, and neither does a canceled workflow. The application
has to close its own record of that task.

Two things do happen, and they are easy to mistake for this. The user task observers DO hear
about the termination, because the engine pushes it to the subscription. But they are the
technical audience described above, not the workflow's own method. And `cancelUserTask` or
`cancelTask`, which the application calls itself, work as they always did. The application
already knows about these, so there is nothing to tell it.

The core can close this gap for a BPMS which reports no cancellation. At every delivery it would
ask the adapter about the other tasks of the same workflow it thinks are open, and report the ones
which are gone. The question has three answers, and only "gone" leads to a cancellation. This
adapter does not supply such a probe. The Process-Engine-API has no operation to ask about a
task. And a failed command comes back without a type, so a refusal looks just like an engine which
cannot be reached ([`GAPS.md`](GAPS.md), entry 27). A probe which guessed would cancel the open
work of every workflow it woke up whenever the engine has a short problem. So no probe is supplied
at all (decision 13 in [`DECISIONS.md`](DECISIONS.md), held by `PeaOpenTaskProbeTest`).

What an application does instead: model the cancellation. An event subprocess, or a boundary
event with a service task behind it, turns the moment into a delivery. A delivery is the one
thing this API reports reliably. The same BPMN then behaves the same way on every BPMS. That is
worth more than a notification which only some of them send.

## What the adapter remembers about the deployed models

The Process-Engine-API has no repository API. A deployed process cannot be read back, not even
right after the deployment ([`GAPS.md`](GAPS.md), entry 12). So the only source is what
VanillaBP's deployment pipeline read at boot. This adapter keeps it in `PeaDeployedProcesses`,
one per configured adapter id.

What it answers comes from those models and from nothing else:

- a process by its workflow module and its BPMN process id, with the name a modeller gave it,
- a user task by its BPMN element id, which is one of the two keys a form is chosen by,
- a user task by its external form reference, which is the other key, and the key this adapter
  subscribes the task under.

The answer is the model itself, not a copy of a few fields. Whoever asks usually reads more of it
than a small record would hold, and the adapter keeps the model anyway. A form reference is NOT
unique inside a workflow module, because two processes may show the same form. So that lookup
answers a collection, and the caller decides. A BPMN element id is unique within one file and is
treated the same way, because nothing keeps two processes of one module apart there either.

The identifiers are the plain ones the application wrote, never the scoped ones in the deployed
bytes (decision 2 in [`DECISIONS.md`](DECISIONS.md)). A delivery is translated back to them as
well.

The processes of a workflow module are in the store from the moment this adapter deployed that
module. VanillaBP deploys every module before it starts workflow processing for any of them. So
anything which asks at runtime sees all of them: a task delivery, an observer, the viewer API. An
extension which wires its own half while the pipeline is still going through the modules sees the
modules deployed so far, and not the ones after them.

`PeaDeployedProcessesTest` holds the lookups. `UserTaskObserverIntegrationTest` (Spring Boot)
and `PeaUserTaskObserverTest` (Quarkus) hold that a booted application answers them.

## What an id without a model still gets

A workflow module may declare a BPMN process id under which it deploys nothing. This is what
renaming a BPMN process leaves behind. The engine still holds the old model, and workflows still
run on it. The application brings only the new model. It declares the old id through
`@WorkflowService(secondaryBpmnProcesses = ...)`, so its methods keep serving those workflows.

The adapter cannot read the old model back, because this API has no repository
([`GAPS.md`](GAPS.md), entry 12). What it can do is ask the core two things: which ids a module
declares without a model, and which task definitions the application serves for each of them. It
builds the names from that, in exactly the way the deployment built the names of the models it
deployed (decision 11 in [`DECISIONS.md`](DECISIONS.md)).

What that means for each mode:

|                              Mode                              |    The name an old-id task carries    |                          What the start does                          |
|----------------------------------------------------------------|---------------------------------------|-----------------------------------------------------------------------|
| `use-prefix`, task definitions per process (the default)       | the OLD process id is part of it      | one subscription per name, and every delivery is routed to the old id |
| `use-prefix` with `prefix-task-definitions-per-process: false` | the same name as the deployed process | nothing of its own, and the start says what that costs                |
| `none`                                                         | the same name as the deployed process | nothing of its own, and the start says what that costs                |
| `by-adapter`                                                   | does not arise                        | this adapter refuses the mode while deploying                         |

Each name built this way is subscribed twice: once as an asynchronous task and once as a user
task. Nothing outside the model says which of the two the task was. And a subscription of this
API which has nothing to do costs nothing, not even an activation request.

Where the name is shared with a deployed process, the old id joins that subscription. It does not
become a candidate for routing, though. It joins for two reasons: the payload the subscription
asks for then covers what the methods of the old id read with `@TaskParam`, and a delivery which
cannot be routed names the old id as one of the reasons. A delivery under a shared name is
assigned to a deployed process, as before. To tell the two apart, the adapter would need the
`bpmnProcessId` meta entry ([`GAPS.md`](GAPS.md), entry 6), and no engine behind this API fills
it today.

One case stays stuck. A `@WorkflowTask` method wired to a BPMN element id
(`@WorkflowTask(id = ...)`) names no task definition. And no name can be built from a model this
application does not have any more. The start warns and names both ways out: wire the method by
its task definition, or keep deploying the old model under its old id until its workflows have
ended.

What such an id does NOT get: the `@WorkflowEnded` notification, which this adapter cannot report
for any process (entry 17). And anything the viewer API would show about its model, because that
needs the model. The adapter says both, at the start and when a viewer asks, instead of answering
with silence.

`PeaDeclaredProcessSubscriptionsTest` holds all of it.

## The meta a delivered task carries

The Process-Engine-API names the keys a subscription may be RESTRICTED by (`CommonRestrictions`).
It names none of the keys a delivered task carries. `TaskInformation` itself defines `reason` and
`retries`. Beyond those, an engine adapter fills in whatever it has.

`PeaTaskMeta` lists the keys which the API's own reference adapter for an embedded Camunda 7
fills: the element id, the process instance, the version tag, the task name, the assignee, the
candidate users and groups, the due date and the follow-up date. It adds `bpmnProcessId`, which is
a convention of this adapter only ([`GAPS.md`](GAPS.md), entry 6). The adapter reads four of them
itself and passes the rest on unchanged. It reads them from `PeaTaskMeta` and not from string
literals in its handlers. So whoever watches its user tasks reads the same names the adapter
writes.

Three of the four go into the delivery record VanillaBP writes: the version tag, the element id
(`activityId`) and the workflow id (`processInstanceId`). Both handlers, the one for service tasks
and the one for user tasks, answer them, and in the same way.

The element id does control something. It is the second key a `@WorkflowTask` method is wired by.
So a delivery which names no element never reaches a method with `@WorkflowTask(id = ...)`. The
workflow id controls nothing. People outside VanillaBP use it to address a task: an operator in
the engine's own tooling, or an extension which links a task to a place in the model.

Where an engine fills in no workflow id, the record carries none. This is the normal case, not a
defect, because the API defines no names for what a delivered task carries. The adapter SPI has a
default of `null` for exactly this case, meaning "this adapter names none".

A start names a workflow id as well. Phase two of both starts, the plain one and the one by
message, gives VanillaBP the `instanceId` the engine answered with. VanillaBP writes it down for
the aggregate. The API does not say that this is the same value a delivered task names under
`processInstanceId`. The reference adapter for an embedded Camunda 7 fills both with the id of
the process instance, and this adapter relies on that. If an engine answers a start with nothing,
there is nothing to write down. `PeaTwoPhaseStartOutboxTest#preflightInTransactionAndSyncAfterCommit`
and `TaskProcessingIntegrationTest#startWorkflowByMessagePublishesTheAggregateState` hold it.

The version of the started process goes with the id. The adapter reads it from the `meta` map of
the answer, under `processDefinitionVersionTag`. That is the key a delivered task carries its tag
under. The reference adapter fills no such key in its answer to a start. So there the version is
empty, and VanillaBP waits for a delivery to bring it (`GAPS.md`, entry 31).
`PeaTwoPhaseStartOutboxTest#theStartRowCarriesTheVersionTagOfTheAnswer` holds it.

The element is answered even then, because this adapter deployed the models and can read them. A
subscription asks the engine for one task definition. So once a delivery is routed to a BPMN
process, the element with that name in that process is the element the delivery belongs to.
Where the engine names an element, the engine still wins: it says which element THIS delivery
came from, in the model it is running.

The model has no answer where one name sits on several elements of one process. This is exactly
the case in which a method is wired by its element id. The start says so. It names the elements
and the meta entry which would tell them apart ([`GAPS.md`](GAPS.md), entry 26). A declared
process id without a model has no answer either, for the reason
[described above](#what-an-id-without-a-model-still-gets).

`TaskProcessingIntegrationTest#theRecordNamesWhatTheEngineNamed`,
`#anEngineWhichNamesNeitherIsAnsweredByTheModel` and `#aMethodWiredByItsElementIdIsReached` hold
it. `PeaTaskHandlerTest` holds which of the two sources answers.

What the keys MEAN is up to the Process-Engine-API. A key an engine leaves out is never an error.
It is just one detail less about a task which is worth reporting anyway. That is why the three
readers next to the constants answer `null` or an empty list instead of throwing. It is also why a
due date which is not a date is logged and dropped, instead of costing the task.

## Outbound operations: one handler per operation

Everything this adapter sends to the engine is a `PhaseOperationHandler`. There is one per
operation, in `PeaProcessService.phaseOperations()`. `phaseOne` asks inside the caller's
transaction, and `phaseTwo` acts after the commit. The operation itself belongs to VanillaBP's
`PhaseOperation`: its stored name, what makes two of them duplicates, which engine serves it and
how a failure is worded. So an operation added later costs this adapter one entry in that map.

Two operations are in the map although this adapter cannot always serve them. Broadcasting a
signal needs a `SignalApi`. And pushing a changed aggregate is not possible at all, because the
API updates the payload of a task, not of a running instance (GAPS entry 18). They stay in the
map, because their handler is where the reason is written. A message which only says "this
adapter cannot" would leave the reader without the fix.
`PeaSendSignalTest#missingSignalApiFailsGuiding` and `PeaAggregateChangedTest` are those two
messages.

## When a phase-one check runs

The phase-one checks of this adapter are `PREFLIGHT_CHECK` completions. They check that the task
still exists and never move the process forward. They used to run when the application made its
call. Now the adapter hands them to the pre-commit hook of the platform, so they run just before
the transaction of the workflow aggregate commits. The later the check runs, the smaller the
window in which its answer can go stale before phase two acts on it. A check which fails still
aborts the caller's transaction, which is the whole point of asking in phase one.

[The transaction the work runs in](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#the-transaction-the-work-runs-in)
shows where that hook sits, compared to the caller's commit and to the dispatcher which runs
phase two.

The platform first looks up the transaction runner of the aggregate. So the check hooks into the
unit of work the aggregate is really stored in, and this may be one the application brought.
Where the runner offers no hook, the platform runs the check at once, as this adapter did before.
A caller which names no aggregate persistence gets its check run at once as well.

A check which finds the task gone throws `io.vanillabp.spi.process.TaskNotFoundException`. The SPI
documents this type for a task no BPMS knows any more, and the platform throws it when its own
probe finds out. So an application catches one type either way, and it does not need to know
which of the two answered. The message names the task and says what a retry does. What the
engine refused the check with goes to the log on INFO. The exception carries a message but no
cause. The Process-Engine-API does not give a typed reason for a refusal anyway (GAPS entry 10).
So that log line is the only place where the reason is ever written.

That check is the only command this adapter is sure to send before the commit of a task
operation. The awareness probe of a task is a `PREFLIGHT_CHECK` completion too, but the platform
asks it only when it must. A task operation is routed by the record the delivery of that task
wrote, and where such a record answers, no adapter is probed. So the engine may see one
`PREFLIGHT_CHECK` per task, or two, and both are right. A test which wants to check what this
adapter promises reads the pre-commit check. `PeaPreCommitHookTest` is that test
(`theCheckIsHandedToTheHook`, `aFailingCheckReachesTheCaller`,
`withoutAPersistenceTheCheckRunsImmediately`).
`PeaWorkflowLifecycleTest#assertThePreflightRanBeforeTheCommitReturned` checks the same in a
booted application. The exception type is held by
`TaskProcessingIntegrationTest#aStaleCompletionRaisesTheGuidingException` and its twin for user
tasks, and on Quarkus by the two tests of the same names in `PeaWorkflowLifecycleTest`. The
Quarkus tests read the exception out of the `RollbackException` in which JTA wraps a failed
`beforeCompletion`.

Correlating a message and broadcasting a signal still have no preflight. The reason is not a
missing query. `CorrelateMessageCmd` and `SendSignalCmd` are Kotlin `data class`es, so they are
final, and they cannot carry the `PREFLIGHT_CHECK` execution mode. Task commands are `open`
classes, which is why the checks above exist at all.

## Which phase-two failures are repeated

The phase-two outbox repeats a failed operation until the entry is blocked. The
Process-Engine-API declares no typed exceptions. Whatever an engine implementation throws arrives
wrapped in an `ExecutionException`. "The engine cannot be reached" looks exactly like "the engine
refused". So only one kind of failure can be told apart, and it is the kind this adapter throws
itself: `UnsupportedOperationException`. The adapter throws it where the API cannot do what
VanillaBP asks, for a signal without a `SignalApi` and for pushing a changed aggregate into a
running instance. Those entries are blocked at once. Everything else is repeated, which is the
safe default (`PeaPhaseTwoClassificationTest#whatTheApiCannotDoIsPermanent` and
`#everythingElseIsRepeatable`).

### A failure of phase two is reported, not dropped

The phase-two completion of a task used to catch every failure. It wrote a WARN line saying the
task was gone and used up the outbox entry. That is right for the case it was written for: a
repeated completion of a task the engine already finished. Every remote BPMS accepts this
at-least-once risk. But it is wrong for every other case, because this API reports no typed
errors. An engine which cannot be reached answers exactly like an engine which finished the task
in the meantime. So whenever the second case was real, the completion was lost, and the workflow
kept waiting at a task the application thought was done.

Now every phase-two operation of the `ProcessService` lets the failure through: completing and
canceling a task, the same for a user task, correlating a message, broadcasting a signal and
starting a workflow by message. An interrupted wait is such a failure too. The engine did not
answer, and that is all the outbox needs to know. `PeaPhaseTwoClassificationTest` holds which
failures are passed on, and which of them the outbox treats as permanent.
`PeaInterruptedOperationsTest` holds that an interrupt survives the call and uses up no entry. The
outbox repeats these operations and blocks the entry when the attempts are used up. So:

- an engine which cannot be reached costs retries and ends in a blocked entry which names the
  cause,
- a task which really is finished costs the same retries and also ends in a blocked entry.

The second case is the price for never losing the first. Its entry does no harm: look at the
cause, then remove the entry. The message of the failure says so.

The DELIVERY path has not changed. When the engine hands this adapter a task and the completion
afterwards fails, the engine delivers the task again. This adapter relies on that for recovery.
Nothing is lost there, even without the outbox.

The core asks this adapter for two identities of one delivery, and it gets the task id for both.
This is not a mistake. This engine creates one task each time an element is activated, and it
delivers the task again under the same id. So the identity of the DELIVERY (the same across
repeated deliveries) and the identity of the ACTIVATION (different between two activations of one
element) really are one value here. An engine which gave a repeated delivery a new id would have
to answer the two differently. That is why they are two methods and not one.
`PeaTaskHandlerTest#everyTaskIsItsOwnActivation` holds the half this adapter can hold. That the
id also stays the same across repeated deliveries is an assumption about the engine behind the
API. No test here can produce it. An implementation which hands out a new task id for each
repeated delivery would prove the assumption wrong.

## What this adapter says about a value type

The platform refuses to start a workflow whose values might not arrive as what they were. It asks
every adapter of that workflow what its BPMS does with a type
(`MigratableProcessService#whatThisBpmsDoesWith`). `PeaValueTypes` is the answer of this adapter,
and it is the shortest of the three.

The Process-Engine-API does not depend on one engine, by design. It takes a payload and gives one
back. Which engine sits behind it, how that engine stores a variable and which expression language
reads it are all outside the API. So this adapter answers for a text, a boolean and an enum. For
everything else the honest answer is "cannot say". That never stops a startup. It does cost the
developer a warning, and the way out of the warning is a test against the engine really in use.

## Decision log

Decisions which several places in this repository rely on are written down in
[`DECISIONS.md`](./DECISIONS.md). It is the one thing the code may cite. A citation reads
`see decision 2 in the repository's DECISIONS.md`. Numbers are never reused. An entry which is
overturned stays and names the entry which replaced it. So a citation written today still points
to the right place in a year.

## Build

First install the prerequisites into the local Maven repository, in this order:
`spi-for-java` → `adapter-platform-integration` → this repository.

```bash
mvn spotless:apply
mvn install
```

Run `install` alone, never `install verify`. `install` already runs every phase `verify` has. If
you name both, Maven walks two lifecycles per module and reports every compiler warning twice.

The tests are pure JVM smoke tests (no Docker, no network).

What a pull request needs beyond a green build is in [`CONTRIBUTING.md`](./CONTRIBUTING.md).

A build also runs once a night, without anybody pushing. The workflow *Nightly against the platform
snapshot* (`.github/workflows/nightly-platform-snapshot.yaml`) builds what a pull request builds.
It uses `--update-snapshots`, so it builds against whatever the platform published since the day
before. A red night opens an issue with the label `nightly-platform-snapshot`. Without this
nightly build, a platform snapshot can break this repository, and nobody sees it until the next
pull request. This happened on the 26th of September 2026. The platform published at 12:19, and
the last build of the Camunda 7 adapter had run at 10:15. The break only showed up a day and a
half later, inside somebody's pull request. The nightly build writes down which platform snapshot
it used, with the timestamp and the build number. So a red night can be read either as a break of
the platform or as a break of this repository.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension + the Quarkus end-to-end tests) - into
   `test-coverage-report/quarkus/report`

The *Build and publish snapshots* workflow publishes both to GitHub Pages on every push to the
default branch. Click the [badge of a platform](#documentation-and-supported-platforms) to open
its report.

The build breaks below a line. `test-coverage-report/coverage-gate` is the last module of the
reactor. It reads both reports and fails whenever a platform is below its threshold in the root
POM (`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`). The thresholds are in
percent of covered instructions, the number the badges above show. Both properties hold 85, the
same number every VanillaBP repository uses as its gate. That is not the target. The rule is 90
per platform, so a report between 85 and 90 passes the build and still shows a gap. The gate marks
the point where the gap has grown too big to accept. That is why it is never edited to make a
build pass. The gate also compares every module which produces a `jacoco.exec` with the two
aggregated reports. So a module which is added to the build but not to its report cannot go
unnoticed. Both measurements happen in `CoverageGateTest`. `TestClassConventionsTest` next to it
makes sure every test class uses the output suppression which the printed lines below depend on.

`TestClassConventionsTest` also reads the main sources of this repository. It looks for a guiding
message whose sentence fell apart: several spaces between two words, or two words which a line
continuation glued into one.

The gate reports what it measured on every run, green runs included. This is the one place in
VanillaBP where a passing test prints something. The angle brackets stand for the numbers of the
run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

A build which stops at `package` never reaches the phase which writes the reports. In that case
the gate prints one line per platform, saying that the coverage was not checked. Those two tests
are then reported as skipped. They do not fail because of a file the run could not have written.

Both platforms are held to the same line. Coverage is measured per platform because the adapter
core does not depend on a platform. Whatever runs the core counts only for the platform its tests
ran on. So the lines of the core which Quarkus never reaches are the features Quarkus never runs.
That is why `quarkus/integration-tests` runs the same workflow lifecycle on a booted Quarkus
application as the Spring Boot integration tests do on their side. This duplication is on
purpose. A core proven once says nothing about whether the glue code of a platform ever calls it.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
