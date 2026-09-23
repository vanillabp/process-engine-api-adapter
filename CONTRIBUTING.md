# Contributing

This repository is the VanillaBP adapter for the
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api) of bpm-crafters. It
implements the adapter SPI of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration) against an API
rather than against one engine, so what it can do depends on the engine behind that API. Business
code never sees it: an application writes against
[`spi-for-java`](https://github.com/vanillabp/spi-for-java), and what this adapter does for its users
is described in the [wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki).

Where the rules are: [`README.md`](./README.md) explains how the adapter works and where its limits
are, and it is the first thing to read. [`AGENTS.md`](./AGENTS.md) says how work is done here, in the
form an agent reads. [`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on, and
it is the only thing the code is allowed to cite.

## Building and testing

A JDK 21 or newer, and Maven, without a wrapper. The workflows build with the JDK named in
`.github/workflows`, currently 25, so build with that one if you want to see what the pipeline sees.
The class files stay at Java 21 either way, because that is what the property `version.java` in the
root `pom.xml` compiles against. Two repositories are built and installed into the local Maven
repository first, in this order: `spi-for-java`, then `adapter-platform-integration`. Then, here:

```bash
mvn spotless:apply
mvn install
```

`install` and not `install verify`: `install` already runs every phase `verify` has, so naming both
walks two lifecycles per module and reports every compiler warning twice.

Two tools read the javadoc, and each one sees a part the other misses. The compiler checks every
class for a broken reference or broken HTML, the package private ones included. The javadoc plugin
checks what the published documentation shows, so it starts at protected and stops there. One thing
below protected is shown as well: the fields a serializable class carries into its serialized form,
which is why a private field of an exception is asked for a comment too.

A comment which is missing breaks the build. Everything this repository publishes has one now, and
the plugin fails on a warning so that it stays that way. Write the sentence rather than switching
the check off, and write the one a reader needs: what this repository publishes is read by somebody
wiring it into an application, and `@return the value` is the same gap in a longer form. A module
which publishes nothing sets `maven.javadoc.skip`, so a test module is never asked for comments.

One thing the javadoc plugin cannot see is an accessor Lombok generates, because it reads the source
and Lombok writes bytecode. So a published comment names a property in words rather than linking a
getter which is not in the file.

The tests are pure JVM smoke tests. They need neither Docker nor a network, which makes a full run
the cheapest in the workspace and is also the reason other repositories boot this adapter as their
test double. `test-coverage-report/coverage-gate` is the last module of the reactor and fails below
85 percent of covered instructions per platform, while the rule is 90.

## What a POM hands an application

A tool which only translates our source belongs in scope `provided`, and the scope stands at the
declaration in the module which uses the tool. Lombok is such a tool, an annotation processor is
another. An application asked for a workflow engine, and every jar it did not ask for is one more
thing to ship and to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not do it. Maven copies a
managed version, scope and exclusions into a dependency and leaves the optional flag behind, so the
POM we publish says nothing at all about that dependency. Lombok reached the runtime classpath of
every application that way, here and in the platform.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc, the commit message and the pull request
included. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them.

## What is asked before the code is written

Where a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write
it and wait for the answer. An entry is never edited away: it stays, marked as superseded and naming
its successor, and the new decision takes the next free number.

The second question is the SPI. What this adapter implements is published by the platform
integration and served by three other adapters as well, so a change to the SPI is discussed there and
not worked around here. Refusing what the Process-Engine-API cannot do, with a message naming the
adapter and the reason, is a different thing and belongs here.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what moved
and why it had to. It may cite an issue or a conversation, because it is a record of a moment itself,
which the code is not.

Check the numbers your branch hands out before you open it. Another branch may have taken the
decision number you used while you were writing, and once a pull request is merged a
`see decision 3` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

The *Publish to GitHub Packages* workflow builds and tests every pull request and publishes nothing
from a branch. A red check is a finding about your change. Read the log and fix what it says rather
than pushing again to see whether it goes away.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
