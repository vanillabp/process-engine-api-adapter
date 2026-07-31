# VanillaBP Process-Engine-API adapter

A [VanillaBP](https://www.vanillabp.io) Version 2 BPMS adapter built on the
BPMS-agnostic [bpm-crafters Process-Engine-API](https://github.com/bpm-crafters/process-engine-api)
(`dev.bpm-crafters.process-engine-api:process-engine-api`).

> **Experimental, mock-first.** This adapter is developed against an in-memory fake of the
> Process-Engine-API (module `mock/`) to discover — feature by feature — where either
> VanillaBP or the Process-Engine-API needs an extension. Those findings are collected in
> [`GAPS.md`](GAPS.md). Implemented so far: **BPMN parsing and deployment** and the
> **two-phase `startWorkflow`** (phase one `PREFLIGHT_CHECK`, phase two `SYNC`), proven
> end-to-end through `ProcessService#startWorkflow` with the JPA outbox against the mock.
> Message correlation and task subscription are later stories.

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

## Build

Prerequisites installed into the local Maven repository first (build order):
`spi-for-java` → `adapter-platform-integration` → this repository.

```bash
mvn spotless:apply
mvn install verify
```

Tests are pure JVM smoke tests (no Docker, no network).

## Test coverage

An aggregated JaCoCo report over all modules is generated by `mvn install verify`
into `test-coverage-report/report`. Baseline recorded with the hardening story
(2026-07-29): **79.6% line coverage**. The feature stories' definition of done
requires >90% - gaps are filled by the stories touching the respective code.
