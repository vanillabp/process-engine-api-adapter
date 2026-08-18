![Header](./readme/vanillabp-headline.png)

# VanillaBP Process-Engine-API adapter

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

A [VanillaBP](https://www.vanillabp.io) Version 2 BPMS adapter built on the
BPMS-agnostic [bpm-crafters Process-Engine-API](https://github.com/bpm-crafters/process-engine-api)
(`dev.bpm-crafters.process-engine-api:process-engine-api`).

Developers who want to **use** this adapter should refer to the
[Wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki); the VanillaBP concepts it builds on are
documented in the [VanillaBP Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki). This
`README.md` is aimed at contributors.

> **Experimental, mock-first.** This adapter is developed against an in-memory fake of the
> Process-Engine-API (module `mock/`) to discover — feature by feature — where either
> VanillaBP or the Process-Engine-API needs an extension. Those findings are collected in
> [`GAPS.md`](GAPS.md). Implemented so far: **BPMN parsing and deployment**, the
> **two-phase `startWorkflow`** (phase one `PREFLIGHT_CHECK`, phase two `SYNC`, proven
> end-to-end through `ProcessService#startWorkflow` with the JPA outbox against the
> mock), **task processing** (`@WorkflowTask` via task subscriptions),
> **completing/canceling asynchronous and user tasks**, **message correlation**
> (start-by-message included), the **BPMS-election awareness probes**, the
> **viewer/history API** (answered from what this application version deployed — the API
> has neither a repository nor a history API) and the **aggregate sync** (shared
> attributes travel as the payload of every command).

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fprocess-engine-api-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/process-engine-api-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fprocess-engine-api-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/process-engine-api-adapter/quarkus-report)

Coverage is measured separately per platform - a platform's tests never cover the other
platform's code. Click a badge to open the respective report.

## Why an adapter against the Process-Engine-API?

The Process-Engine-API is a second BPMS-agnostic API besides VanillaBP, but at a lower
level: it is command/API-oriented rather than aspect-oriented. Building a VanillaBP
adapter on top of it lets a single adapter target every BPMS the Process-Engine-API
supports. Where a feature cannot be expressed through the Process-Engine-API, the gap is
documented and a BPMS-specific adapter can later fill it.

This adapter uses **only the pure Process-Engine-API artifact** — none of the existing
Process-Engine-API platform implementations or adapters, whose platform-binding and
configuration philosophy differs from VanillaBP's. The Process-Engine-API is Kotlin but
100% Java-compatible; this repository is pure Java and adds no Kotlin build plugins (the
Kotlin standard library arrives transitively).

## ExecutionMode and VanillaBP's two-phase start

Since version 1.6 every Process-Engine-API command carries an
[`ExecutionMode`](https://github.com/bpm-crafters/process-engine-api/issues/281)
(`dev.bpmcrafters.processengineapi.ExecutionMode`): `DEFAULT`, `ASYNC`, `SYNC`,
`PREFLIGHT_CHECK`. This maps directly onto VanillaBP's two-phase workflow start:

- **phase one ≈ `PREFLIGHT_CHECK`** — validate only, no execution;
- **phase two ≈ `SYNC`** — execute in the caller's transaction context.

Accordingly the adapter treats the Process-Engine-API as a **remote BPMS**
(`needsTwoPhaseCommitForStartingWorkflows()` returns `true`) so workflow starts run
through VanillaBP's generic transaction-outbox path.

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
(`io.vanillabp.pea.mock.InMemoryProcessEngine`). Providing your own beans of the
Process-Engine-API interfaces (Spring Boot: any bean; Quarkus: any non-default bean)
replaces the mock with a real Process-Engine-API implementation.

## Behavior and limitations

- **Deployment:** on startup each workflow module's BPMN resources are parsed (JDK StAX, one
  entry per `<bpmn:process isExecutable="true">`) and deployed via the Process-Engine-API's
  `DeploymentApi`. Because the API has no workflow-module / tenant namespace that matches
  VanillaBP's, resources are deployed to the default tenant — **module isolation relies on
  unique BPMN process ids across modules** ([`GAPS.md`](GAPS.md), entry 4).
- **Starting workflows** is two-phase (the Process-Engine-API is treated as a remote BPMS):
  phase one maps to `ExecutionMode.PREFLIGHT_CHECK` (validate only, inside the transaction),
  phase two (after commit, via the outbox) to `ExecutionMode.SYNC` (create the instance). The
  aggregate id travels as a process variable named after the aggregate's ID property
  (`AggregatePersistenceAware.getAggregateIdName()`; there is no business-key slot —
  [`GAPS.md`](GAPS.md), entry 3). Phase two is at-least-once, so duplicates are possible until
  the core-side `WorkflowInstanceRegistry` story lands.
- **Platform coverage:** deployment is wired and integration-tested on **both platforms**:
  Spring Boot (`PeaDeploymentServiceTest`, `DeploymentIntegrationTest`) and Quarkus
  (`PeaDeploymentPipelineTest` - since story 26b the Quarkus platform integration runs the
  deployment pipeline at boot; the test proves `deployResources` reaches the in-memory mock
  engine, `InMemoryProcessEngine.getDeployments()`).
- **Task processing** (story 21c): at `startWorkflowProcessing` the adapter subscribes ONE
  `TaskSubscriptionApi` subscription per distinct task definition of the module's BPMN files
  (the `zeebe:taskDefinition` type of service/send/business-rule/script tasks - the API does
  not define the mapping, [`GAPS.md`](GAPS.md), entry 7). Task wiring is validated during
  `wireBpmn`; unwired `@WorkflowTask` methods are reported at the end of `deployResources`.
  A delivered task runs the `@WorkflowTask` method in a NEW local transaction which commits
  BEFORE the completion command is sent (at-least-once ordering; handlers must be idempotent).
  Outcomes: normal return → `completeTask`; `TaskException` → `completeTaskByError` with the
  error code (aggregate changes committed - the V1 contract); `@TaskId` handlers leave the
  task open for asynchronous completion (upcoming story); any other exception → local rollback
  plus `failTask` (retry semantics are the underlying engine's). All completion commands carry
  **`ExecutionMode.SYNC`** - they run AFTER the local commit, the phase-two shape; the built-in
  command classes cannot carry a non-default mode, so the adapter subclasses them
  ([`GAPS.md`](GAPS.md), entry 8). Routing a delivered task to its BPMN process relies on the
  adapter meta-key convention `bpmnProcessId` ([`GAPS.md`](GAPS.md), entry 6); without the meta
  entry the task definition has to be unique across the module's processes. A completion
  failing AFTER the local commit is tolerated with a WARN - the engine redelivers and the
  idempotent handler converges.
- **Completing/canceling async tasks** (`ProcessService#completeTask`/`#cancelTask`, story 22):
  the awareness probe and the phase-one existence check are `ExecutionMode.PREFLIGHT_CHECK`
  completions (validate only - exactly what the mode is for); the actual completion/cancellation
  runs after the caller's commit through the outbox as a SYNC `completeTask`/`completeTaskByError`
  (a gone task is tolerated - at-least-once residual). PEA failures are untyped, so a failing
  probe cannot be told apart from an unreachable engine and maps to "unknown"
  ([`GAPS.md`](GAPS.md), entry 10 - relevant for multi-BPMS migration setups). `@TaskEvent
  CANCELED` cannot be delivered (the subscription's termination callback carries only the task
  ID, no aggregate reference). The mock tracks open tasks (`deliverTask` opens,
  SYNC completions close) so preflights validate honestly.
- **User tasks** (story 24): user tasks with a `zeebe:formDefinition` EXTERNAL reference (the
  reference is the task definition) are subscribed via the Task Subscription API with
  `TaskType.USER`; a delivered user task is a CREATED notification to an OPTIONAL
  `@WorkflowTask` method (never completing the task; the task's ID arrives as `@TaskId`).
  CANCELED cannot be delivered (termination callback carries no aggregate reference - GAPS).
  `completeUserTask`/`cancelUserTask` run through the `UserTaskCompletionApi` with the same
  PREFLIGHT_CHECK (phase one) / SYNC (phase two) mapping as service tasks; failing
  notifications are logged loudly but never break the user task itself.
- **Message correlation** (story 23): `correlateMessage` sends a `CorrelateMessageCmd` with
  `correlationKey = correlationId ?? aggregate ID` after the caller's commit (outbox); no
  payload travels. `startWorkflowByMessage` sends a `StartProcessByMessageCmd` carrying only
  the aggregate-ID variable. LIMITATION ([`GAPS.md`](GAPS.md), entry 11): both command classes
  are FINAL - the execution mode cannot be transported (no PREFLIGHT_CHECK phase one, commands
  travel with DEFAULT mode) and workflow awareness cannot be probed at all (the adapter answers
  optimistically with a one-time guiding WARN - unsafe for multi-BPMS migration setups).
- **Aggregate sync** (stories 28 and 44): the API is a remote BPMS, so everything is shared
  unless `@NoSyncWithBPMS` excludes it, and the shared attributes travel as the payload of
  every command sent on behalf of a workflow (start, task completion incl.
  `completeTaskByError`, async-task and user-task completion, correlation). The aggregate is
  read AFTER the handler's local transaction committed, in an own transaction; a failing read
  still completes the task, with the aggregate-ID value only and a warning naming the
  workflow. `aggregateChanged` is not expressible at all: the API modifies the payload of
  TASKS only, so both phases throw with a guiding message ([`GAPS.md`](GAPS.md), entry 18) -
  phase ONE already, so the application sees it at its call instead of in an outbox dispatch.
- **Starts the engine performs itself, and the end of a workflow** ([`GAPS.md`](GAPS.md),
  entries 16 and 17): nothing in the API reports either. A process carrying a timer, signal
  or conditional start event is rejected during `wireBpmn` (through the deployment-failure
  policy, so a non-first-priority adapter degrades it to a warning), because deploying it
  would produce workflows without an aggregate. A `@WorkflowEnded` method only WARNs, since
  the workflow runs perfectly well and just the notification is missing.
- **Versions of a process** ([`GAPS.md`](GAPS.md), entry 19): the API knows no version of a
  deployed process definition, so the adapter registers no version catalog and reports only
  the version TAG from the task meta (key `processDefinitionVersionTag`, named by the API's
  `CommonRestrictions`) where the engine supplies it. An exact tag therefore works, while a
  range over tags and any specification made of numbers matches nothing and is reported once.
- **Viewing workflows** (`ProcessService#getProcessDefinitions`/`#getBpmnXml`/`#getWorkflowHistory`,
  story 26): served from what THIS application version deployed - the Process-Engine-API has
  neither a repository nor a query/history API ([`GAPS.md`](GAPS.md), entries 12 and 13). The
  adapter-native process definition id is `<workflow module>|<bpmn process id>`, the reported
  version is the deployment key, and the BPMN XML is the deployed resource byte for byte. The
  history reports the definition with `elementsHistory == null` (the SPI's "not supported by the
  underlying BPMS"), so there are no secondary history contexts and call activities cannot be
  drilled into; call-activity definitions are not reported either (no BPMN model type).

## Which phase-two failures are repeated (story 73)

The phase-two outbox repeats a failed operation until the entry is blocked. The
Process-Engine-API declares no typed exceptions - whatever an engine implementation throws
arrives wrapped in an `ExecutionException`, and "the engine is unreachable" looks exactly like
"the engine refused". So one family is classifiable, and it is the one this adapter throws
itself: `UnsupportedOperationException`, raised where the API cannot do what VanillaBP asks (a
signal without a `SignalApi`, pushing a changed aggregate into a running instance). Those
entries are blocked at once. Everything else is repeated, which is the safe default.

### A failure of phase two is reported, not dropped (story 86)

Until story 86 the phase-two completion of a task caught every failure, wrote a WARN line
saying the task was gone and consumed the outbox entry. That is right for the case it was
written for - a repeated completion of a task the engine already finished, the accepted
at-least-once residual of every remote BPMS - and wrong for every other, because this API
reports no typed errors: an engine which is unreachable answers exactly like one which
finished the task meanwhile. The completion was therefore lost whenever the second case was
real, and the workflow kept waiting at a task the application considered done.

Every phase-two operation of the `ProcessService` now lets the failure through:
completing and canceling a task, the same for a user task, and correlating a message. The
outbox repeats them and blocks the entry when the attempts are used up, so:

- an unreachable engine costs retries and ends in a blocked entry naming the cause,
- a genuinely finished task costs the same retries and ends in a blocked entry as well.

The second case is the price of never losing the first. Its entry is harmless: look at the
cause, then remove the entry. The message of the failure says so.

The DELIVERY path is unchanged: when the engine hands this adapter a task and the completion
afterwards fails, the engine redelivers the task, which is the recovery this adapter relies
on. Nothing is lost there without the outbox.

## Build

Prerequisites installed into the local Maven repository first (build order):
`spi-for-java` → `adapter-platform-integration` → this repository.

```bash
mvn spotless:apply
mvn install verify
```

Tests are pure JVM smoke tests (no Docker, no network).

## Test coverage

`mvn install verify` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Both are published to GitHub Pages by the *Publish to GitHub Packages* workflow on every push to
the default branch. Click the [platform's badge](#documentation-and-supported-platforms) to open
the respective report.

Baseline recorded with the hardening story (2026-07-29): **79.6% line coverage**. The feature
stories' definition of done requires >90% - gaps are filled by the stories touching the respective
code.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
