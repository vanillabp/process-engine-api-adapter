# mock — in-memory Process-Engine-API fake

Contributor documentation. The `mock` module provides
`io.vanillabp.pea.mock.InMemoryProcessEngine`, a **hand-written** in-memory fake of the
bpm-crafters Process-Engine-API. It lets the adapter run in tests and early applications
without a real BPMS and without a network.

It is deliberately **not a Mockito mock**: later feature stories need it to carry real
state (deployed definitions, started instances, subscribed tasks), which a generated mock
cannot do. This fake therefore grows with every feature story; its whole reason to exist
is to make it obvious where VanillaBP or the Process-Engine-API needs an extension (see
[`../GAPS.md`](../GAPS.md)).

The module depends only on `dev.bpm-crafters.process-engine-api:process-engine-api`. The
adapter's `core` module does **not** depend on it.

## Current behavior

Every API method records its invocation into the public, inspectable list
`InMemoryProcessEngine.invocations` (also via `getInvocations()`). A record entry is
`Invocation(String api, String method, Object command, ExecutionMode executionMode)`, where
`executionMode` is taken from the command when it is `ExecutionModeAware` and `null`
otherwise (e.g. task subscription commands). Beyond recording, two APIs carry real state:

- **`deploy(DeployBundleCommand)`** stores a `Deployment(deploymentKey, resources, tenantId)`
  per invocation (inspectable via `getDeployments()`) and returns a `DeploymentInformation`.
- **`startProcess(StartProcessCommand)`** records the command and its `ExecutionMode`.
  Only for `ExecutionMode.SYNC` (phase two of VanillaBP's two-phase start) it creates a
  `StartedInstance(instanceId, variables)`, inspectable via `getStartedInstances()`. The
  aggregate id is one of the `variables` - which one is the adapter's decision (the
  variable is named after the aggregate's ID property), so the engine fake does not
  single it out. `ExecutionMode.PREFLIGHT_CHECK` (phase one) and any other mode create
  no instance.

All other methods still return a completed future / empty result of the declared type.
`reset()` clears the recordings and all fake state (deployments, started instances).

## Interfaces implemented

`InMemoryProcessEngine` implements the real Process-Engine-API interfaces
(`dev.bpm-crafters.process-engine-api:process-engine-api:1.7`, package
`dev.bpmcrafters.processengineapi`):

|            Interface            |                                            Mocked method(s)                                             |                              Returns                               |
|---------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `deploy.DeploymentApi`          | `deploy(DeployBundleCommand)`                                                                           | `CompletableFuture<DeploymentInformation>`                         |
| `process.StartProcessApi`       | `startProcess(StartProcessCommand)`                                                                     | `CompletableFuture<ProcessInformation>`                            |
| `correlation.CorrelationApi`    | `correlateMessage(CorrelateMessageCmd)`                                                                 | `CompletableFuture<Empty>`                                         |
| `task.TaskSubscriptionApi`      | `subscribeForTask(SubscribeForTaskCmd)`, `unsubscribe(UnsubscribeFromTaskCmd)`                          | `CompletableFuture<TaskSubscription>` / `CompletableFuture<Empty>` |
| `task.ServiceTaskCompletionApi` | `completeTask(CompleteTaskCmd)`, `completeTaskByError(CompleteTaskByErrorCmd)`, `failTask(FailTaskCmd)` | `CompletableFuture<Empty>`                                         |
| `task.UserTaskCompletionApi`    | `completeTask(CompleteTaskCmd)`, `completeTaskByError(CompleteTaskByErrorCmd)`                          | `CompletableFuture<Empty>`                                         |

Because `ServiceTaskCompletionApi` and `UserTaskCompletionApi` declare identical
`completeTask` / `completeTaskByError` signatures, one Java method implements both; the
recorded `api` for those is `"TaskCompletionApi"`.

Cross-cutting interface methods also implemented: `MetaInfoAware.meta(MetaInfoAware)`
(returns an empty `MetaInfo`) and `RestrictionAware.getSupportedRestrictions()` (returns
an empty set). `ExecutionModeAware.executionMode()`, `RestrictionAware.areSupported(...)`
and `RestrictionAware.ensureSupported(...)` are Java `default` methods on the
Process-Engine-API interfaces and need no implementation.

Deliberately not implemented, because VanillaBP does not call them:
`correlation.SignalApi`, `task.UserTaskModificationApi`, `decision.EvaluateDecisionApi` —
add them here as soon as an adapter feature needs them.

## Failure injection

For testing VanillaBP's two-phase start the fake can inject failures per BPMN
process id:

- `failPreflightFor(bpmnProcessId)` — every `ExecutionMode.PREFLIGHT_CHECK` for
  that process fails (until `reset()`): asserts that a failed phase one rolls the
  caller's transaction back.
- `failNextSyncFor(bpmnProcessId)` — the NEXT `ExecutionMode.SYNC` start fails
  once, subsequent starts succeed: asserts that a failed phase two makes the
  outbox retry the dispatch.

`getStartedInstances()` returns a LIST in creation order (not a map keyed by
aggregate id) so duplicate starts for the same aggregate are observable - a map
would silently overwrite and hide exactly the bug the fake is meant to surface.
Note the fake cannot validate a `PREFLIGHT_CHECK` against deployed processes
(opaque resources, see `GAPS.md` entry 5) - inject failures instead.
