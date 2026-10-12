# mock — in-memory Process-Engine-API fake

This page is for contributors. The `mock` module has
`io.vanillabp.pea.mock.InMemoryProcessEngine`, an in-memory fake of the bpm-crafters
Process-Engine-API, **written by hand**. With it, the adapter runs in tests and early
applications without a real BPMS and without a network.

It is **not a Mockito mock**, on purpose. Later feature stories need it to hold real state:
deployed definitions, started instances, subscribed tasks. A generated mock cannot do that. So
this fake grows with every feature story. It exists only to make it obvious where VanillaBP or
the Process-Engine-API needs an extension (see [`../GAPS.md`](../GAPS.md)).

The module depends only on `dev.bpm-crafters.process-engine-api:process-engine-api`. The
`core` module of the adapter does **not** depend on it.

## Current behavior

Every API method records its call in the public list `InMemoryProcessEngine.invocations`,
which a test can read (also through `getInvocations()`). An entry is
`Invocation(String api, String method, Object command, ExecutionMode executionMode)`. The
`executionMode` comes from the command when the command is `ExecutionModeAware`, and is `null`
otherwise (for example for task subscription commands). Besides recording, the fake keeps
state. The two starting points are these:

- **`deploy(DeployBundleCommand)`** stores one `Deployment(deploymentKey, resources, tenantId)`
  per call, which `getDeployments()` returns. It answers with a `DeploymentInformation`.
- **`startProcess(StartProcessCommand)`** records the command and its `ExecutionMode`. Only for
  `ExecutionMode.SYNC`, phase two of VanillaBP's two-phase start, it creates a
  `StartedInstance(instanceId, variables, bpmnProcessId)`, which `getStartedInstances()` returns. The aggregate
  id is one of the `variables`. The adapter decides which one: the variable is named after the ID
  property of the aggregate. So the fake engine does not pick it out. `ExecutionMode.PREFLIGHT_CHECK`
  (phase one) and every other mode create no instance.

It also keeps the open subscriptions (`getSubscriptions()`), the open tasks (`getOpenTaskIds()`),
the completed, errored and failed tasks, the correlated messages and the broadcast signals. Each of
them has a getter of its own. `reset()` clears the recordings and the state of the fake, except the broadcast signals.

## Interfaces implemented

`InMemoryProcessEngine` implements the real Process-Engine-API interfaces
(`dev.bpm-crafters.process-engine-api:process-engine-api:1.7`, package
`dev.bpmcrafters.processengineapi`):

|            Interface            |                                            Mocked method(s)                                             |                              Returns                               |
|---------------------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `deploy.DeploymentApi`          | `deploy(DeployBundleCommand)`                                                                           | `CompletableFuture<DeploymentInformation>`                         |
| `process.StartProcessApi`       | `startProcess(StartProcessCommand)`                                                                     | `CompletableFuture<ProcessInformation>`                            |
| `correlation.CorrelationApi`    | `correlateMessage(CorrelateMessageCmd)`                                                                 | `CompletableFuture<Empty>`                                         |
| `correlation.SignalApi`         | `sendSignal(SendSignalCmd)`                                                                             | `CompletableFuture<Empty>`                                         |
| `task.TaskSubscriptionApi`      | `subscribeForTask(SubscribeForTaskCmd)`, `unsubscribe(UnsubscribeFromTaskCmd)`                          | `CompletableFuture<TaskSubscription>` / `CompletableFuture<Empty>` |
| `task.ServiceTaskCompletionApi` | `completeTask(CompleteTaskCmd)`, `completeTaskByError(CompleteTaskByErrorCmd)`, `failTask(FailTaskCmd)` | `CompletableFuture<Empty>`                                         |
| `task.UserTaskCompletionApi`    | `completeTask(CompleteTaskCmd)`, `completeTaskByError(CompleteTaskByErrorCmd)`                          | `CompletableFuture<Empty>`                                         |

`ServiceTaskCompletionApi` and `UserTaskCompletionApi` declare the same signatures for
`completeTask` and `completeTaskByError`. So one Java method implements both, and the recorded
`api` for them is `"TaskCompletionApi"`.

Two methods which several interfaces share are implemented as well:
`MetaInfoAware.meta(MetaInfoAware)` returns an empty `MetaInfo`, and
`RestrictionAware.getSupportedRestrictions()` returns an empty set.
`ExecutionModeAware.executionMode()`, `RestrictionAware.areSupported(...)` and
`RestrictionAware.ensureSupported(...)` are Java `default` methods of the Process-Engine-API
interfaces and need no implementation.

Not implemented on purpose, because VanillaBP does not call them:
`task.UserTaskModificationApi` and `decision.EvaluateDecisionApi`. Add them here as soon as a
feature of the adapter needs them.

## Failure injection

To test VanillaBP's two-phase start, the fake can make calls fail for a given BPMN process id:

- `failPreflightFor(bpmnProcessId)`: every `ExecutionMode.PREFLIGHT_CHECK` for that process
  fails, until `reset()`. A test uses it to check that a failed phase one rolls back the
  caller's transaction.
- `failNextSyncFor(bpmnProcessId)`: the NEXT `ExecutionMode.SYNC` start fails once, and the
  starts after it succeed. A test uses it to check that a failed phase two makes the outbox
  dispatch again.

`getStartedInstances()` returns a LIST in the order the instances were created, not a map by
aggregate id. So two starts for the same aggregate can be seen. A map would silently overwrite
the first one and hide exactly the bug the fake is there to show.
The fake cannot check a `PREFLIGHT_CHECK` against deployed processes, because it does not look
into the resources (see `GAPS.md` entry 5). Make the call fail instead.

The tests which use the fake hold what it records and what it does not record. Above all these
are `PeaTwoPhaseStartOutboxTest` (one `PREFLIGHT_CHECK` inside the transaction, one `SYNC` after
the commit, none after a rollback) and `PeaFetchVariablesTest#theEngineNarrowsThePayload` for the
narrowing of the payload.
