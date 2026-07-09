# core — Process-Engine-API adapter (platform-neutral)

Contributor documentation. The `core` module holds the **platform-neutral** parts of the
adapter: the VanillaBP adapter-SPI implementations and (later) the client logic against
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
  of one workflow module during the deployment pipeline.
- `deployment/PeaDeploymentService` — implements
  `AdapterDeploymentService<PeaBpmnModel, PeaProcessingContext>`. Type/id getters are
  implemented; every pipeline method throws `UnsupportedOperationException` (skeleton).
- `processservice/PeaProcessService<A>` — implements `MigratableProcessService<A>`.
  `needsTwoPhaseCommitForStartingWorkflows()` returns `true`; every behavior method throws
  `UnsupportedOperationException` (skeleton). The Process-Engine-API interfaces are
  constructor parameters, so the platform modules inject an implementation.

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

The skeleton wires the subset needed for VanillaBP's process-service operations into
`PeaProcessService` (start process, correlate message, task subscription, service- and
user-task completion). Deployment goes through `PeaDeploymentService`. The full set is
implemented feature by feature.
