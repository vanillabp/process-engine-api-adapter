# core — Process-Engine-API adapter (platform-neutral)

Contributor documentation (user-facing documentation lives in
[this adapter's wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki)). The `core` module holds the
**platform-neutral** parts of the adapter: the VanillaBP adapter-SPI implementations and the client logic against
the bpm-crafters Process-Engine-API. It contains no Spring or Quarkus code and — by
design — **does not depend on the `mock/` module**. The Process-Engine-API implementation
is injected from the outside (the mock in tests/early apps, a real implementation later).

Dependencies: `io.vanillabp:vanillabp-adapter-spi` (adapter SPI, brings the integration
SPI and the extension SPI transitively) and `dev.bpm-crafters.process-engine-api:process-engine-api`
(the pure API artifact).

## Contents

- `PeaAdapter` — constants, notably the adapter type `"process-engine-api"`.
- `PeaBpmnModel` — the adapter's BPMN model type (`record PeaBpmnModel(String filename,
  byte[] resource, String bpmnProcessId)`). The Process-Engine-API has no model type of
  its own — see [`../GAPS.md`](../GAPS.md), entry 1.
- `PeaProcessingContext` — the processing context (`PC`) accumulated across all BPMN files
  of one workflow module during the deployment pipeline (it collects the `PeaBpmnModel`s).
- `deployment/PeaDeploymentService` — implements
  `AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext>`. Parses BPMN, accumulates
  models and deploys them (see below). A `DeploymentApi` is a constructor parameter, so the
  platform modules inject the implementation (the mock by default).
- `processservice/PeaProcessService<A>` — implements `MigratableProcessService<A>`.
  `startWorkflowPhaseOne` asks with `PREFLIGHT_CHECK`, `startWorkflowPhaseTwo` acts with
  `SYNC` (see below). The
  Process-Engine-API interfaces are constructor parameters, so the platform modules inject an
  implementation.
- `processservice/PeaStartProcessCommand` — the adapter's own `StartProcessCommand` carrying
  the BPMN process id, the payload and the `ExecutionMode` (the built-in commands cannot
  carry a non-default `ExecutionMode` — see [`../GAPS.md`](../GAPS.md), entry 2).
- `observation/` — the seam through which something outside the adapter watches its user
  tasks (see below). This is adapter-own API, the way `Camunda7EngineCustomizer` is
  adapter-own API of the Camunda 7 adapter: nothing of it belongs to the VanillaBP adapter
  SPI, because nothing of it is true of another BPMS.

## Reading BPMN (`readBpmn`)

The Process-Engine-API has no BPMN model type, so `readBpmn` parses the BPMN XML itself,
only far enough to learn the executable process ids. It uses the JDK's **StAX** streaming
parser (`javax.xml.stream`, XXE-hardened: external entities and DTDs disabled), collecting
the `id` of every `<bpmn:process isExecutable="true">` element (a file may contain several).
It returns one `PeaBpmnModel(filename, rawBytes, bpmnProcessId)` per executable process;
parse/read failures are wrapped in `BpmnParseException`.

## Deploying (`prepareBpmn` → `deployResources`)

`prepareBpmn` accumulates the models of a workflow module into the `PeaProcessingContext`
(creating it on the first call — the core passes `null` initially). `wireBpmn` validates the
task wiring against the core's `WorkflowTaskInvoker` and collects the task definitions to
subscribe for. `deployResources` builds one `DeployBundleCommand` per
workflow module containing a `NamedResource` per **file** (models of the same file are
deployed once) and calls the injected `DeploymentApi.deploy(...)` synchronously. Because
module-as-tenant isolation is not expressible, the bundle is deployed to the default tenant
(see [`../GAPS.md`](../GAPS.md), entry 4).

## Two-phase start ↔ `ExecutionMode` mapping

VanillaBP starts a workflow in two phases; the Process-Engine-API's `ExecutionMode`
(bpm-crafters/process-engine-api issue 281) expresses exactly the needed semantics, so the
mapping is direct:

|           VanillaBP phase           |                   When                   | Process-Engine-API `ExecutionMode` |                                          Effect                                          |
|-------------------------------------|------------------------------------------|------------------------------------|------------------------------------------------------------------------------------------|
| phase one (`startWorkflowPhaseOne`) | inside the caller's DB transaction       | `PREFLIGHT_CHECK`                  | validate only — no instance is created (no ghost workflow if the transaction rolls back) |
| phase two (`startWorkflowPhaseTwo`) | after commit, via the transaction outbox | `SYNC`                             | actually create the process instance                                                     |

The command in both phases is a `PeaStartProcessCommand` with the same BPMN process id and
the aggregate id passed as the `aggregateId` payload variable, differing only in the
`ExecutionMode`. The platform passes the workflow module id and BPMN process id into both
`startWorkflowPhaseOne(module, process, aggregatePersistence, aggregate)` and
`startWorkflowPhaseTwo(module, process, aggregateId)`.

This is proven end-to-end by `PeaTwoPhaseStartOutboxTest` in the `spring-boot` module, which
drives `ProcessService#startWorkflow` inside a JPA transaction with the phase-two outbox:
exactly one `PREFLIGHT_CHECK` is recorded while the transaction is open (no instance), one
`SYNC` after commit creates the instance (matching process id + aggregate id), and a rollback
records the `PREFLIGHT_CHECK` but never dispatches a `SYNC`.

**Idempotency limitation:** phase two is at-least-once (outbox), so a crash between a
successful create and the outbox entry removal can duplicate the instance. There is no
registry which would deduplicate it strictly: a workflow is located by asking rather than
remembered (decision 25 of the platform's `DECISIONS.md`). The core narrows the window by
probing before a re-dispatched start, and this adapter answers that probe optimistically
([`GAPS.md`](../GAPS.md), entry 11), so the residual stays wider here than on an adapter which
can be asked.

## Observing user tasks (`io.vanillabp.pea.observation`)

The Process-Engine-API delivers a task to exactly ONE subscription, so an extension which
wants to watch the user tasks of an application cannot subscribe next to the adapter: it
would either see nothing or take the delivery away from the workflow application, decided by
the order the two subscriptions were registered in. The seam is therefore inside the
adapter's own subscription.

The package holds three types:

- `PeaUserTaskObserver` — what an application or an extension implements. Two methods,
  `userTaskDelivered` and `userTaskTerminated`. The user-facing contract is in
  [`../README.md`](../README.md), section "Observing the user tasks of an application".
- `PeaUserTaskObservation` — the value handed over: adapter id, workflow module, BPMN process,
  task definition, workflow aggregate id, the engine's `TaskInformation` and the payload the
  subscription asked for. The identifiers are the PLAIN ones (decision 2 in
  [`../DECISIONS.md`](../DECISIONS.md)), and the two which a delivery may leave open —
  `bpmnProcessId` and `workflowAggregateId` — are documented as nullable rather than faked.
- `PeaUserTaskObservers` — the observers of one adapter id and the one place they are called
  from, so that what an observer costs the task it watches is decided once for both
  platforms: a throwing observer is caught and logged, the next one is called anyway, and an
  application which registered nothing never builds an observation (the callers hand over a
  `Supplier`).

Where it is called from:

- `PeaDeploymentService` takes the observers through `setUserTaskObservers(...)`, the way it
  takes the `fetch-variables` resolver, and hands them to every `PeaUserTaskHandler` it builds
  while opening the user-task subscriptions of a workflow module.
- `PeaUserTaskHandler.accept` calls `userTaskDelivered` right after it routed the delivery to
  its BPMN process and BEFORE the check which drops a delivery no `@WorkflowTask` method
  claims. The workflow aggregate's id is therefore resolved twice per delivery when somebody
  observes: tolerantly for the observation (an unknown aggregate leaves it `null`) and
  strictly for the application's own path, where a claimed delivery without an aggregate id is
  a defect. Nobody observing means neither resolution happens.
- `PeaUserTaskHandler.terminated` is registered as the subscription's `TaskTerminationHandler`
  — the overload carrying the engine's `TaskInformation`, which is what the reason travels in.
  The service-task subscriptions register the same overload, without observers: there is
  nobody to tell, and the DEBUG line saying a task is gone is worth the engine's reason.

## Process-Engine-API interfaces used

Verified against `dev.bpm-crafters.process-engine-api:process-engine-api:1.7`
(package `dev.bpmcrafters.processengineapi`). The full API surface (each an own
interface) is:

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

Cross-cutting: command interfaces implement `ExecutionModeAware.executionMode()` returning
`dev.bpmcrafters.processengineapi.ExecutionMode` (`DEFAULT`, `ASYNC`, `SYNC`,
`PREFLIGHT_CHECK`; see issue #281). Several APIs also extend `MetaInfoAware`
(`meta(...)`) and `RestrictionAware` (`getSupportedRestrictions()`).

`PeaProcessService` uses the subset VanillaBP's operations need (start process, correlate
message, signals where a `SignalApi` was provided, task subscription, service- and user-task
completion); deployment goes through `PeaDeploymentService`. APIs VanillaBP has no use for yet
(user-task modification, decision evaluation) are simply not called — the gaps the API leaves for
the features VanillaBP DOES implement are collected in [`../GAPS.md`](../GAPS.md).

## Platform version guard

`META-INF/vanillabp/adapter-process-engine-api.properties` carries this adapter's version and the
version of the VanillaBP platform integration it was built against
(`platform.version=${adapter-platform.version}`, filled by resource filtering configured
in `pom.xml`). The `PeaDeploymentService` constructor passes it to
`AdapterPlatformVersion.requireCompatiblePlatform(...)`, which aborts the startup with a
guiding message if the platform integration on the classpath is older (the comparison itself is
held by `AdapterPlatformVersionTest` of the platform repository) — Maven does not
report that as a conflict, because a version managed by the application always wins over
the version required transitively by this adapter, even as a downgrade. See
`migration-adapter/README.md`, section "Adapter/platform version guard".
