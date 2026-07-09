# VanillaBP Process-Engine-API adapter

A [VanillaBP](https://www.vanillabp.io) Version 2 BPMS adapter built on the
BPMS-agnostic [bpm-crafters Process-Engine-API](https://github.com/bpm-crafters/process-engine-api)
(`dev.bpm-crafters.process-engine-api:process-engine-api`).

> **Experimental, mock-first, skeleton stage.** This repository currently contains only
> the structural skeleton of the adapter plus an in-memory fake of the Process-Engine-API.
> It does **not** yet deploy processes, start workflows, correlate messages or subscribe
> to tasks. Its purpose right now is to discover — feature by feature — where either
> VanillaBP or the Process-Engine-API needs an extension. Those findings are collected in
> [`GAPS.md`](GAPS.md).

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

## Build

Prerequisites installed into the local Maven repository first (build order):
`spi-for-java` → `adapter-platform-integration` → this repository.

```bash
mvn spotless:apply
mvn install verify
```

Tests are pure JVM smoke tests (no Docker, no network).
