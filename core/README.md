# core — Process-Engine-API adapter (platform-neutral)

This page is for contributors. The documentation for users is in
[the wiki of this adapter](https://github.com/vanillabp/process-engine-api-adapter/wiki). The `core`
module holds the parts of the adapter which do **not depend on a platform**: the implementations of
the VanillaBP adapter SPI, and the client code which talks to the bpm-crafters Process-Engine-API.
It has no Spring or Quarkus code. On purpose, it **does not depend on the `mock/` module**. The
implementation of the Process-Engine-API is injected from outside: the mock in tests and early
applications, a real implementation later.

Dependencies: `io.vanillabp:vanillabp-adapter-spi` (the adapter SPI, which brings the integration
SPI and the extension SPI as transitive dependencies) and
`dev.bpm-crafters.process-engine-api:process-engine-api` (the plain API artifact).

## Contents

- `PeaAdapter`: constants, above all the adapter type `"process-engine-api"`.
- `PeaBpmnModel`: the BPMN model type of the adapter (`record PeaBpmnModel(String filename,
  byte[] resource, String bpmnProcessId)`). The Process-Engine-API has no model type of its
  own, see [`../GAPS.md`](../GAPS.md), entry 1.
- `PeaProcessingContext`: the processing context (`PC`). The deployment pipeline fills it with
  the `PeaBpmnModel`s of all BPMN files of one workflow module.
- `deployment/PeaDeploymentService`: implements
  `AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext>`. It parses BPMN, collects the
  models and deploys them (see below). A `DeploymentApi` is a constructor parameter, so the
  platform modules inject the implementation (the mock by default).
- `processservice/PeaProcessService<A>`: implements `MigratableProcessService<A>`.
  `startWorkflowPhaseOne` asks with `PREFLIGHT_CHECK`, and `startWorkflowPhaseTwo` acts with
  `SYNC` (see below). The Process-Engine-API interfaces are constructor parameters, so the
  platform modules inject an implementation.
- `processservice/PeaStartProcessCommand`: the adapter's own `StartProcessCommand`. It carries
  the BPMN process id, the payload and the `ExecutionMode`. The built-in commands cannot carry
  an `ExecutionMode` other than the default, see [`../GAPS.md`](../GAPS.md), entry 2.
- `observation/`: the place where something outside the adapter can watch its user tasks (see
  below). This API belongs to this adapter only, just as `Camunda7EngineCustomizer` belongs to
  the Camunda 7 adapter only. None of it is part of the VanillaBP adapter SPI, because none of
  it is true for another BPMS.

## Reading BPMN (`readBpmn`)

The Process-Engine-API has no BPMN model type. So `readBpmn` parses the BPMN XML itself, but
only far enough to learn the ids of the executable processes. It uses the **StAX** streaming
parser of the JDK (`javax.xml.stream`). The parser is protected against XXE (XML external entity)
attacks: external entities and DTDs are switched off. It collects the `id` of every
`<bpmn:process isExecutable="true">` element, and one file may hold several. It returns one
`PeaBpmnModel(filename, rawBytes, bpmnProcessId)` per executable process. A failure while
reading or parsing is wrapped in a `BpmnParseException`.

## Deploying (`prepareBpmn` → `deployResources`)

`prepareBpmn` collects the models of a workflow module in the `PeaProcessingContext`. It
creates the context on the first call, because the core passes `null` at first. `wireBpmn`
checks the task wiring against the core's `WorkflowTaskInvoker`, and it collects the task
definitions to subscribe for. `deployResources` builds one `DeployBundleCommand` per workflow
module, with one `NamedResource` per **file**, so the models of one file are deployed once. Then
it calls the injected `DeploymentApi.deploy(...)` and waits for it. A workflow module cannot be
kept apart as a tenant of its own, so the bundle is deployed to the default tenant (see
[`../GAPS.md`](../GAPS.md), entry 4).

## Two-phase start ↔ `ExecutionMode` mapping

VanillaBP starts a workflow in two phases. The `ExecutionMode` of the Process-Engine-API
(bpm-crafters/process-engine-api issue 281) expresses exactly what these phases need, so the
mapping is direct:

|           VanillaBP phase           |                   When                   | Process-Engine-API `ExecutionMode` |                                          Effect                                          |
|-------------------------------------|------------------------------------------|------------------------------------|------------------------------------------------------------------------------------------|
| phase one (`startWorkflowPhaseOne`) | inside the caller's DB transaction       | `PREFLIGHT_CHECK`                  | validate only — no instance is created (no ghost workflow if the transaction rolls back) |
| phase two (`startWorkflowPhaseTwo`) | after commit, via the transaction outbox | `SYNC`                             | actually create the process instance                                                     |

In both phases the command is a `PeaStartProcessCommand` with the same BPMN process id. The
aggregate id is passed as a payload variable named after the aggregate's id property
(`getAggregateIdName()` of its persistence). The two commands differ only in
the `ExecutionMode`. The platform passes the workflow module id and the BPMN process id to both
`startWorkflowPhaseOne(module, process, aggregatePersistence, aggregate)` and
`startWorkflowPhaseTwo(module, process, aggregateId)`.

`PeaTwoPhaseStartOutboxTest` in the `spring-boot` module proves this end to end. It runs
`ProcessService#startWorkflow` inside a JPA transaction with the phase-two outbox. Exactly one
`PREFLIGHT_CHECK` is recorded while the transaction is open, and it creates no instance. One
`SYNC` after the commit creates the instance, with the matching process id and aggregate id. A
rollback records the `PREFLIGHT_CHECK` but never dispatches a `SYNC`.

**Idempotency limitation:** phase two runs at least once and maybe more often, because it goes
through the outbox. So a crash after a successful create, but before the outbox entry is
removed, can create the instance twice. There is no registry which could strictly remove the
duplicate: a workflow is found by asking, not by remembering it (decision 5 of the platform's
`DECISIONS.md`). The core makes the window smaller with a probe before it dispatches a start
again. This adapter cannot ask the engine ([`GAPS.md`](../GAPS.md), entry 11), so it answers that
probe with `UNKNOWN_TO_BPMS`, and the start goes on. So the remaining risk is bigger here than on
an adapter which can be asked.

## Observing user tasks (`io.vanillabp.pea.observation`)

The Process-Engine-API delivers a task to exactly ONE subscription. So an extension which
wants to watch the user tasks of an application cannot open a subscription next to the adapter.
It would either see nothing or take the delivery away from the workflow application. Which one
happens depends on the order the two subscriptions were registered in. So the place to watch from
is inside the adapter's own subscription.

The package holds four types:

- `PeaUserTaskObserver`: what an application or an extension implements. It has two methods,
  `userTaskDelivered` and `userTaskTerminated`. The contract for users is in
  [`../README.md`](../README.md), section "Observing the user tasks of an application".
- `PeaUserTaskObservation`: the value which is handed over. It holds the adapter id, the
  workflow module, the BPMN process, the task definition, the workflow aggregate id, the engine's
  `TaskInformation` and the payload the subscription asked for. The identifiers are the PLAIN
  ones. The subscription key is the SCOPED task definition, and the engine reports the scoped
  BPMN process id. Both are translated back through `NameClashAvoidanceSupport` before the
  observation is built (decision 2 in [`../DECISIONS.md`](../DECISIONS.md)). A delivery may leave
  two of them open, `bpmnProcessId` and `workflowAggregateId`. They are documented as nullable,
  not filled with made-up values.
- `PeaUserTaskObservers`: the observers of one adapter id, and the one place they are called
  from. So what a failing observer means for the task it watches is decided once, for both
  platforms. An observer which throws makes the delivery fail, but every other observer is told
  first. The first failure leaves the class, and the failures of the others hang on it as
  suppressed exceptions. A failure while DESCRIBING the task is the same case (decision 12 in
  [`../DECISIONS.md`](../DECISIONS.md)). An application which registered nothing never builds
  an observation, because the callers hand over a `Supplier`.
- `PeaUserTaskObserverFailure`: what leaves `PeaUserTaskObservers` when an observer failed. The
  handler throws it after the application got its notification, so the engine learns that the
  delivery did not work.

Where it is called from:

- `PeaDeploymentService` gets the observers through `setUserTaskObservers(...)`. It hands them
  to every `PeaUserTaskHandler` it builds while it opens the user task subscriptions of a
  workflow module.
- `PeaUserTaskHandler.accept` calls `userTaskDelivered` before anything can drop the delivery.
  That is before the routing fails for a form reference which several BPMN processes share, and
  before the check which skips a delivery no `@WorkflowTask` method claims. What the delivery
  leaves open, it says. In the first case the BPMN process is `null`. In the second case the
  aggregate id is `null`, where the process has no workflow aggregate at all. The variable with
  the aggregate id is resolved once, in a lenient way. The strict path afterwards throws the
  failure this lookup kept.
- `PeaUserTaskHandler.terminated` is registered as the `TaskTerminationHandler` of the
  subscription. This is the overload which carries the engine's `TaskInformation`, and the
  reason travels in it. The subscriptions for service tasks register the same overload, but
  without observers. There is nobody to tell, but the engine's reason is still worth having in
  the DEBUG line which says that a task is gone.

## Process-Engine-API interfaces used

Checked against `dev.bpm-crafters.process-engine-api:process-engine-api:1.7`
(package `dev.bpmcrafters.processengineapi`). The whole API, each part an interface of its own,
is:

|       Interface (package)       |            Purpose            |                                                                 Key method(s)                                                                 |
|---------------------------------|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `deploy.DeploymentApi`          | deploy resource bundles       | `deploy(DeployBundleCommand): CompletableFuture<DeploymentInformation>`                                                                       |
| `process.StartProcessApi`       | start process instances       | `startProcess(StartProcessCommand): CompletableFuture<ProcessInformation>`                                                                    |
| `correlation.CorrelationApi`    | correlate messages            | `correlateMessage(CorrelateMessageCmd): CompletableFuture<Empty>`                                                                             |
| `correlation.SignalApi`         | broadcast signals             | `sendSignal(SendSignalCmd): CompletableFuture<Empty>`                                                                                         |
| `task.TaskSubscriptionApi`      | subscribe/unsubscribe workers | `subscribeForTask(SubscribeForTaskCmd): CompletableFuture<TaskSubscription>`, `unsubscribe(UnsubscribeFromTaskCmd): CompletableFuture<Empty>` |
| `task.ServiceTaskCompletionApi` | complete service tasks        | `completeTask`, `completeTaskByError`, `failTask` → `CompletableFuture<Empty>`                                                                |
| `task.UserTaskCompletionApi`    | complete user tasks           | `completeTask`, `completeTaskByError` → `CompletableFuture<Empty>`                                                                            |
| `task.UserTaskModificationApi`  | modify user tasks             | assignment / dates / payload modifications                                                                                                    |
| `decision.EvaluateDecisionApi`  | evaluate DMN decisions        | `evaluateDecision(...)`                                                                                                                       |

Across all of them: command interfaces implement `ExecutionModeAware.executionMode()`, which
returns a `dev.bpmcrafters.processengineapi.ExecutionMode` (`DEFAULT`, `ASYNC`, `SYNC`,
`PREFLIGHT_CHECK`; see issue #281). Several APIs also extend `MetaInfoAware` (`meta(...)`) and
`RestrictionAware` (`getSupportedRestrictions()`).

`PeaProcessService` uses the part VanillaBP's operations need: starting a process, correlating a
message, signals where a `SignalApi` was provided, task subscriptions, and completing service and
user tasks. Deployment goes through `PeaDeploymentService`. APIs which VanillaBP does not use yet
(changing user tasks, evaluating decisions) are simply not called. The gaps the API leaves for the
features VanillaBP DOES implement are collected in [`../GAPS.md`](../GAPS.md).

## Platform version guard

`META-INF/vanillabp/adapter-process-engine-api.properties` holds the version of this adapter,
its Maven coordinates and the version of the VanillaBP platform integration it was built against
(`platform.version=${adapter-platform.version}`). Resource filtering, configured in `pom.xml`,
fills in the value. The `PeaDeploymentService` constructor passes the file to
`VanillaBpParts.requireAdapterFitsPlatform(...)`, and the platform checks the same file once
more while it boots. The boot stops in two cases: if the platform integration on the classpath is
older than the one this adapter was built against, or if this adapter is older than the oldest
adapter that platform integration still serves. The message names both versions and the
dependency to change. Maven reports neither case as a conflict. A version the application manages
always wins over the version this adapter requires transitively, even if it is a downgrade. See
`migration-adapter/README.md`, section "Parts which do not belong together", in the VanillaBP
platform repository.
