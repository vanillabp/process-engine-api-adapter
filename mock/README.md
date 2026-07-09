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

## Current (skeleton) behavior

Every API method:

1. records its invocation into the public, inspectable list
   `InMemoryProcessEngine.invocations` (also via `getInvocations()`). A record entry is
   `Invocation(String api, String method, Object command, ExecutionMode executionMode)`,
   where `executionMode` is taken from the command when it is `ExecutionModeAware` and
   `null` otherwise (e.g. task subscription commands);
2. returns a completed future / empty result of the declared type.

`reset()` clears all recordings (and, later, all fake state). No stateful behavior yet.

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

Not yet implemented (added when the corresponding feature story needs them):
`correlation.SignalApi`, `task.UserTaskModificationApi`, `decision.EvaluateDecisionApi`.
