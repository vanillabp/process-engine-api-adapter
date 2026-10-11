# Contributing

This repository is the VanillaBP adapter for the
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api) of bpm-crafters. It
implements the adapter SPI of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration). It talks to an
API and not to one engine, so what it can do depends on the engine behind that API. Business code
never sees it, because an application is written against
[`spi-for-java`](https://github.com/vanillabp/spi-for-java). The
[wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki) says what this adapter does for
its users.

Where the rules are: read [`README.md`](./README.md) first. It explains how the adapter works and
where its limits are. [`AGENTS.md`](./AGENTS.md) says how work is done here, in the form an agent
reads. [`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on. It is the only
thing the code may cite.

## Getting started

Start from the [development workspace](https://github.com/vanillabp/development-workspace). Clone it
with `git clone --recurse-submodules`. This repository builds on `spi-for-java` and
`adapter-platform-integration`, and the workspace holds all three side by side. The [README of the
workspace](https://github.com/vanillabp/development-workspace#readme) says how to build them in
order without published snapshots, and how to open a pull request, with or without the right to push
here. It also shows how a coding agent can take a bug off your hands. The rest of this file is about
this repository.

## Building and testing

A JDK 21 or newer, and Maven, without a wrapper. The workflows build with the JDK named in
`.github/workflows`, currently 25, so build with that one if you want to see what the pipeline sees.
The class files stay at Java 21 either way, because that is what the property `version.java` in the
root `pom.xml` compiles against. Two repositories come first, in this order: `spi-for-java`, then
`adapter-platform-integration`. Build and install them in the workspace, see [Getting
started](#getting-started), or read their [published snapshots](#snapshots-on-maven-central).
Then, here:

```bash
mvn spotless:apply
mvn install
```

Use `install`, not `install verify`. `install` already runs every phase of `verify`. Naming both
runs two lifecycles per module, and every compiler warning shows up twice.

Two tools read the javadoc, and each one sees a part the other misses. The compiler checks every
class for a broken reference or broken HTML, including the package private ones. The javadoc plugin
checks what the published documentation shows, so it looks at protected and public only. One thing
below protected is shown as well: the fields a serializable class writes into its serialized form.
That is why a private field of an exception needs a comment too.

A missing comment breaks the build. Everything this repository publishes has one now, and the
plugin fails on a warning so that it stays that way. Write the sentence instead of switching the
check off. And write the one a reader needs. Somebody who wires this adapter into an application
reads what it publishes, and `@return the value` is the same gap in a longer form. A module which
publishes nothing sets `maven.javadoc.skip`, so a test module never needs comments.

The javadoc plugin cannot see an accessor which Lombok generates. The plugin reads the source, and
Lombok writes bytecode. So a published comment names a property in words instead of linking a getter
which is not in the file. A published class whose constructor comes from Lombok writes that
constructor out. Otherwise the documentation shows a constructor without parameters, which does not
exist.

Neither tool sees two javadoc blocks in a row. Javadoc keeps the last block before an element and
drops the earlier ones without a word. So a comment somebody wrote and kept up to date appears
nowhere. `bin/check-orphaned-javadoc.sh` finds that shape. A block it reports describes
something, usually the element next door, so hang it back there rather than delete it.

The tests are pure JVM smoke tests. They need neither Docker nor a network. So a full run is the
cheapest in the workspace, and that is also why other repositories boot this adapter as their test
double. `test-coverage-report/coverage-gate` is the last module of the reactor. It fails below 85
percent of covered instructions per platform. The rule is 90.

That last module also holds the checks which read the sources of the whole repository instead of
running anything. They check that every test class suppresses its output, that no guiding message
fell apart in its text block, and that every BPMN error code goes through the scoping helper before
it reaches the engine. They live there so that one run covers every module. A build of a single
module never runs them, so build the whole reactor before you push.

## Snapshots on Maven Central

If you clone only this repository, Maven takes `spi-for-java` and `adapter-platform-integration`
from the snapshot repository of Maven Central, `https://central.sonatype.com/repository/maven-snapshots/`.
Every push to `main` there publishes a snapshot. The parent `io.vanillabp:release-parent` names that
repository, and reading it needs no login and no token.

Every push to `main` of this repository publishes its own snapshot to the same place.

## What a POM hands an application

A tool which only translates our source belongs in scope `provided`. The scope is written where the
module which uses the tool declares it. Lombok is such a tool, and so is an annotation processor. An
application asked for a workflow engine. Every jar it did not ask for is one more thing to ship, and
one more thing to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not help. Maven copies the
managed version, scope and exclusions into a dependency, but not the optional flag. So the POM we
publish says nothing at all about that dependency. Lombok reached the runtime classpath of
every application that way, here and in the platform.

## A POM comment says what this POM does

A comment in a POM says what this POM sets and why it differs from what it would get for free. It
does not repeat what the parent sets. `io.vanillabp:release-parent` is released on its own schedule,
so a copy of its settings in a child here goes stale without anything noticing. The javadoc
comments of three POMs in this organisation did exactly that: they said the parent switched the
javadoc check off, after a release of it had switched the check on.

Sometimes a module really relies on something its parent does or does not do. Then say so as an
assumption, and name the version it was checked against. The next reader can check it again with one
command, `mvn help:effective-pom`, instead of believing a sentence.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc and the comments, in the main code and in the
tests, the commit message and the pull request included. Nothing a program reads is renamed for the
sake of language: type and method names, configuration keys and artifact coordinates stay as they
are, because code in other repositories points at them.

The same rules hold for the [wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki).
[`AGENTS.md`](./AGENTS.md) has before and after examples from this repository.

## What is asked before the code is written

Where a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write
it and wait for the answer. An entry is never edited away: it stays, marked as superseded and naming
its successor, and the new decision takes the next free number.

The second question is the SPI. The platform integration publishes what this adapter implements,
and three other adapters implement it as well. So a change to the SPI is discussed there and not
worked around here. Refusing what the Process-Engine-API cannot do is a different thing, and it
belongs here. The message names the adapter and the reason.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what
changed and why. It may cite an issue or a conversation. A pull request records one moment in time,
and the code does not.

Check the numbers your branch hands out before you open the pull request. Another branch may have
taken your decision number while you were writing. And once a pull request is merged, a
`see decision 3` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

After you corrected a number, `bin/check-decision-citations.sh` says whether every citation of a
decision still points at an entry of `DECISIONS.md`. The *Checks* workflow runs it on every pull
request.

The *Build and publish snapshots* workflow builds and tests every pull request. It publishes
nothing from a branch. A red check is a finding about your change. Read the log and fix what it
says. Do not push again just to see whether it goes away.

For your first pull request in this repository, a maintainer approves the first run of the
workflows. A pull request from a fork builds and tests like any other, because the snapshots it
needs can be read without a login. It cannot publish anything, because GitHub gives it no secrets of
this repository. [What the build of a pull request can
do](https://github.com/vanillabp/development-workspace#what-the-build-of-a-pull-request-can-do) says
more.

`main` has a ruleset which requires two green checks before a merge. One is `publish`, the build
of that workflow. The other is `orphaned-javadoc-check`, the *Checks* workflow running the script
named above. While one of them is red, GitHub does not offer the merge.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
