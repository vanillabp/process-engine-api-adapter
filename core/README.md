# core — Process-Engine-API adapter (platform-neutral)

Contributor documentation (user-facing documentation lives in
[this adapter's wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki)). The `core` module holds the
**platform-neutral** parts of the adapter: the VanillaBP adapter-SPI implementations and the client logic against
the bpm-crafters Process-Engine-API. It contains no Spring or Quarkus code and — by
design — **does not depend on the `mock/` module**. The Process-Engine-API implementation
is injected from the outside (the mock in tests/early apps, a real implementation later).

Dependencies: `io.vanillabp.adapter:migration-adapter-spi` (adapter SPI, brings the
business SPI transitively) and `dev.bpm-crafters.process-engine-api:process-engine-api`
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
  `needsTwoPhaseCommitForStartingWorkflows()` returns `true`; `startWorkflowPhaseOne` starts
  with `PREFLIGHT_CHECK`, `startWorkflowPhaseTwo` with `SYNC` (see below). The
  Process-Engine-API interfaces are constructor parameters, so the platform modules inject an
  implementation.
- `processservice/PeaStartProcessCommand` — the adapter's own `StartProcessCommand` carrying
  the BPMN process id, the payload and the `ExecutionMode` (the built-in commands cannot
  carry a non-default `ExecutionMode` — see [`../GAPS.md`](../GAPS.md), entry 2).

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
successful create and the outbox entry removal can duplicate the instance. Strict dedup by
`workflowModuleId + bpmnProcessId + workflowAggregateId` needs the core-side
`WorkflowInstanceRegistry`, which does not exist yet.

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
message, task subscription, service- and user-task completion); deployment goes through
`PeaDeploymentService`. APIs VanillaBP has no use for yet (signals, user-task
modification, decision evaluation) are simply not called — the gaps the API leaves for
the features VanillaBP DOES implement are collected in [`../GAPS.md`](../GAPS.md).

## Platform version guard

`META-INF/vanillabp/adapter-process-engine-api.properties` carries this adapter's version and the
version of the VanillaBP platform integration it was built against
(`platform.version=${adapter-platform.version}`, filled by resource filtering configured
in `pom.xml`). The `PeaDeploymentService` constructor passes it to
`AdapterPlatformVersion.requireCompatiblePlatform(...)`, which aborts the startup with a
guiding message if the platform integration on the classpath is older — Maven does not
report that as a conflict, because a version managed by the application always wins over
the version required transitively by this adapter, even as a downgrade. See
`migration-adapter/README.md`, section "Adapter/platform version guard".
