# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 2 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side; a pointer into another repository is the
fragile kind this log exists to avoid.

Links below point into this repository's [`README.md`](./README.md) and into
[`GAPS.md`](./GAPS.md), which carry the detail an entry deliberately leaves out.

### 1. A command carries the shared aggregate attributes and the aggregate-ID variable, nothing else

The Process-Engine-API has no business key, so the variable named after the aggregate's ID
attribute is the only way back from a process instance to the workflow, and it is written no
matter what the sync model says. Beside it travels the state the aggregate shares with the
engine, because the engine can evaluate an expression only against the payload it was given.
Nothing else does: a correlated message carries no content of its own, and an attribute
excluded by `@NoSyncWithBPMS` stays out of every command.

This holds for every command sent on behalf of a workflow - starting it, completing a task
with or without an error, completing or canceling an async or user task, correlating a
message. The bullet *Aggregate sync* under
[Behavior and limitations](./README.md#behavior-and-limitations) says what that means per operation.

### 2. The deployed bytes carry scoped identifiers, the model in memory keeps plain ones

This BPMS has no namespace which matches a workflow module, so name-clash avoidance is what
keeps two modules apart, and it is applied by rewriting the BPMN resource on its way to the
`DeploymentApi`: process ids, message and signal names, error and escalation codes, task
definitions and form references. The `PeaBpmnModel` kept in memory holds the plain
identifiers, because they are what the core's registries are keyed by, and every delivery
coming back from the engine is translated to plain before the core sees it.

### 3. A class opens its fields one by one, not as a whole

The process service and the deployment service of this adapter hold dozens of fields, most of
them collaborators nobody outside the class needs. Which of them a caller may read belongs to
the surface of the class, so an accessor is declared per field, and `@Getter` on the class is
refused even where an IDE offers it: it would publish the current field list and then keep
publishing whatever field a later change adds. `@SuppressWarnings("LombokGetterMayBeUsed")` on
such a class is what keeps that offer from coming back.

### 4. What has to be asked before the commit is a preflight, the work itself runs after it

The Process-Engine-API executes a command in the mode the command carries, so this adapter uses
the two modes as the two phases VanillaBP needs. `PREFLIGHT_CHECK` runs inside the caller's
transaction and only asks, which is what keeps a guiding error where the application made the
call, and `SYNC` runs the real command after the commit, dispatched by the phase-two outbox and
therefore repeatable.

The built-in command classes cannot carry a non-default mode, so the adapter sends subclasses of
them. The two commands which are `final` cannot be subclassed at all, which is why correlating a
message and starting a workflow by message have no preflight here and are documented in
[`GAPS.md`](./GAPS.md) rather than faked. Where the platform offers a pre-commit hook the
preflights run in it, and where none is present they run immediately, which keeps the adapter
usable in a test without one.

### 5. A failure of phase two is reported, not dropped

Every phase-two operation of the process service used to catch `ExecutionException`, write "task
is gone" and consume the outbox entry. That is right for the case it was written for, a repeated
completion of a task which already finished, and wrong for every other, because this API declares
no typed errors and an unreachable engine answers exactly like a rejected command. A completion
was lost in silence.

So every phase-two operation propagates now, with a message naming operation, process, workflow
module and adapter, and saying that a blocked entry on an already finished task is the harmless
reading: completing and canceling a task, the same for a user task, correlating a message,
broadcasting a signal and starting a workflow by message. The last one had kept a second way to
look successful after the other five were fixed. It waits on a future like all of them, and an
interrupted wait returned normally, which marked the entry done although no workflow had been
started, so the application's database carried an aggregate no engine knew about. An interrupt is
therefore a failure of phase two like any other and reaches the outbox as one.

The DELIVERY path is deliberately unchanged: there the engine delivers again, which is the
recovery, and no outbox entry is involved. It does say now that the outcome was lost, an
interrupt included, because an unannounced redelivery reads like a business method running twice
for no reason.
See [A failure of phase two is reported, not dropped](./README.md#a-failure-of-phase-two-is-reported-not-dropped).

### 6. Only what the API cannot do at all is a permanent failure

`isPhaseTwoFailureRepeatable` answers `false` for `UnsupportedOperationException` and for nothing
else, so an entry is blocked immediately only where the engine has no such capability: a signal
without a configured `SignalApi`, a push of a changed aggregate. Everything else is repeated.

More is not classifiable. The API declares no typed errors, so "the engine refused this" and "the
engine is unreachable" arrive as the same exception, and a wrong permanent verdict blocks work
which a retry would have completed.
See [Which phase-two failures are repeated](./README.md#which-phase-two-failures-are-repeated).

### 7. A subscription asks for exactly the variables the handlers declare

A subscription used to be opened with an empty set, so the engine decided what a task delivery
carried. Now it names the aggregate-id variable of the process it serves plus the union of the
`@TaskParam` names the core reports for that task definition, and user-task subscriptions do the
same.

The core is the source rather than a scan of the model, because a model declares names nobody
reads and misses names no model carries. The mock engine trims its payload to what the
subscription asked for, so the derivation is exercised rather than asserted.
See [What a subscription asks the engine for](./README.md#what-a-subscription-asks-the-engine-for).

### 8. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI used to ask for two methods per outbound operation, and this adapter
had eighteen of them. It answers a map now: one `PhaseOperationHandler` per `PhaseOperation`,
each of them the pair of "ask" and "act" for this engine. What the handlers do is unchanged -
the same preflight commands, the same completions, the same messages - only the shape moved.

The map is what states which operations this adapter serves, and two of its entries are there
although the answer they give is "not here": a signal needs a `SignalApi` the adapter may have
been built without, and a changed aggregate cannot be pushed at all because the API updates the
payload of a TASK rather than of a running instance (GAPS entry 18). VanillaBP would say "this
adapter cannot serve the operation" for a missing entry, which is true but useless: which API
is missing, and what to model instead, is knowledge only this adapter has. So the entries stay
and the handlers throw with a message which names the fix - the core's message is the fallback
for an adapter which has nothing to add, not the better answer.

### 9. One list of user-task observers, and the observation names the adapter

Who watches this adapter's user tasks is collected once per application - the beans of
`PeaUserTaskObserver` a platform module finds - and every configured adapter id of type
`process-engine-api` is given that one list.

There is nothing to distribute anyway. `validateDistinctAdapterInstances` ends the boot as
soon as a second adapter id of this type is configured, because the engine arrives as a set of
application-provided beans with no notion of which engine they are ([`GAPS.md`](./GAPS.md),
entry 14). One application, one engine, and a per-id registration today would be a knob with
one setting.

The observation still names the adapter its task came from. It is what an observer reports
under, this repository is not the only source of adapter ids an extension sees, and the day
the API can tell two engines apart the field is already there. A registration per adapter id
stays addable then: it narrows what an observer is handed and breaks nobody.

The two platform registrations rely on this, and so does the shape of the observation. What
the seam promises beyond it is in the type javadoc of `PeaUserTaskObserver` and in
[Observing the user tasks of an application](./README.md#observing-the-user-tasks-of-an-application).

### 10. The models read at deployment are indexed once, and the meta keys are spelled once

Nothing can be read back from this engine, so what the deployment pipeline read at boot is all
there is, and `PeaDeployedProcesses` already held it per adapter id. It now answers by the BPMN
element id of a user task and by its external form reference as well, next to the process id it
always answered by. That is an index over the models it holds, not a second store: whoever asks
cannot get an answer which disagrees with what was deployed, and a redeployment cannot leave an
entry of a user task the new model does not carry.

The answer carries the model rather than a trimmed record, because whoever asks reads more of it
than such a record would carry and the adapter holds it anyway. A form reference is not unique
inside a workflow module, so that lookup answers a collection and the caller decides; the
element id answers one, because nothing keeps two processes of one module from using the same
one. The same pass which reads the process id now also reads the name the modeller wrote on the
process, next to the user-task names it already read, so nobody has to walk the same bytes a
second time for them.

The keys of `TaskInformation.meta` moved the same way. The Process-Engine-API names the keys a
subscription may be restricted by and none of the keys a delivery carries, so the vocabulary is
a convention, and this adapter wrote two of its keys as string literals of its handlers while
whoever watches its user tasks wrote them again. `PeaTaskMeta` is where they live now, with the
readers which answer "the engine filled none" rather than throwing. What the keys mean stays the
Process-Engine-API's business ([`GAPS.md`](./GAPS.md), entry 6, and entry 3 of the Business
Cockpit adapter's).

See [What the adapter remembers about the deployed models](./README.md#what-the-adapter-remembers-about-the-deployed-models)
and [The meta a delivered task carries](./README.md#the-meta-a-delivered-task-carries).

### 11. The subscriptions of a declared process id are composed from what the application serves

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Under `use-prefix` a task definition reaches the engine as
`<module>__<process>__<task>`, so the tasks of the workflows under the old id carry a name no
subscription of the deployed processes asks for, and nobody notices: a task nobody subscribed for
is not a failed task, it is a workflow standing still. Those workflows need one more subscription
each, and the question is where the names come from.

They are COMPOSED from what the application serves. The core names it
(`taskWiringOfProcessesNobodyDeployed`, decision 34 of the platform's own DECISIONS.md), today the
task definition of every `@WorkflowTask` method registered for the declared id, and the adapter
scopes each of them by that id, exactly as the deployment scoped the ones it deployed. Camunda 8
composes the same way and for the same reason, which is its decision 19. Reading the models the
engine still holds is the alternative, the one Camunda 7 takes, and this API has no repository to
read them from ([`GAPS.md`](./GAPS.md), entry 12). So composing is not the cheaper of two ways
here, it is the only one, and it turns out to be enough for the mode which is the default.

What composing costs shows in two places, and both are a price paid on purpose:

- a task definition may belong to a service task or to a user task, and nothing outside the model
  says which. Both subscriptions are opened, and the one whose kind the task never was stays idle.
  An idle subscription of this API costs nothing at all, not even an activation request;
- a `@WorkflowTask` method wired to a BPMN element id (`@WorkflowTask(id = ...)`) names no task
  definition, so no name can be composed for it. Those workflows are the one case which stands
  still after all, and the start says so, naming both ways out: wire the method by task
  definition, or keep deploying the old model under its old id until its workflows have ended.

Where a name is already served nothing of its own is opened, which is every mode but `use-prefix`
and `use-prefix` with `prefix-task-definitions-per-process: false`. The declared id still joins
that subscription, because the payload the subscription asks for has to cover what the old id's
methods read, and because a delivery which cannot be routed has to name the old id as one of the
reasons. It does NOT join as a routing candidate: a delivery over a shared name would then be
ambiguous for every workflow, the ones served correctly today included. What such a delivery gets
is what it got before, attribution to a deployed process, and the start says that too. Telling the
two apart needs the `bpmnProcessId` meta entry of [`GAPS.md`](./GAPS.md) entry 6, which no engine
behind this API fills today.

The boot is not refused over any of this, and no property is added to refuse it. Whether a
declaration is a defect depends on whether workflows still run under the old id, and that is the
one thing this API cannot answer: no repository, no query, no history. Decision 38 of the
platform's DECISIONS.md puts that case first, a check which cannot see every model that could
carry its answer stays silent rather than refusing. A refusal would also hit the case this adapter
exists for: the declared ids are module level, so in a migration where the other BPMS serves the
old workflows, a refusing adapter would end the boot over workflows another adapter is serving
correctly.

`PeaDeclaredProcessSubscriptionsTest` holds which subscriptions are opened per mode, what the
start says and that a delivery of the old id's task reaches the methods of the old id.

See [What an id without a model still gets](./README.md#what-an-id-without-a-model-still-gets).

### 12. An observer which fails disturbs the delivery

The user-task observers used to cost the delivery nothing. `PeaUserTaskObservers` caught what
an observer threw, wrote an ERROR line and handed the task on, and the javadoc of
`PeaUserTaskObserver` promised that this is how it works. The promise is withdrawn. It was
never written down as an entry here, it lived in that javadoc and in
[Observing the user tasks of an application](./README.md#observing-the-user-tasks-of-an-application),
and both of them now say what this entry says.

What changed is the kind of work an observer does. The first one this seam was built for is a
cockpit extension, and it used to only note that a task had arrived, with the work of
describing it done later, from a stored entry which was repeated until it went through. Today
that work runs inside the observer call. A failure there leaves nothing behind to repeat, so a
swallowed failure is a report which never arrives and an ERROR line nobody reads. On an
embedded Camunda 7 or on Camunda 8 the question does not come up: the adapter watches such a
task from a listener which holds the transition, so a failure there becomes an incident
somebody has to look at. This API offers no listener and no incident, so the disturbance has
to come from the delivery itself.

An observer which throws therefore fails the delivery. What happens next is the engine's
business, and the API says nothing about it ([`GAPS.md`](./GAPS.md), entry 25), so it was
measured rather than assumed. Against the API's own reference implementation for an embedded
Camunda 7 (`process-engine-adapter-camunda-platform-c7-embedded-core` 2025.11.1 on Camunda
7.24, measured 2026-09-17) a delivery which throws is logged, the task is dropped from the
list of delivered ones, and the next pull hands it to the subscription again as a new
delivery. The user task stays in the engine and nothing raises an incident. The repetition
stops as soon as the observer works, so the failure is loud while it lasts and the
application loses nothing over it. A termination which throws is not repeated there, and
it disturbs all the same: an observer which loses a termination has a defect either way, and a
rule with an exception for the quiet half would be the old promise again.

Four smaller answers go with it:

- The other observers are told before the failure leaves. Otherwise who hears about a task
  would depend on the order the platform happened to resolve the beans in.
- Several failures travel together. The first one is thrown and the others are attached to it
  as suppressed exceptions, so none of them is lost.
- A failure while describing the task for the observers disturbs as well. An observer which
  gets nothing to see is in the same position as one which could do nothing.
- There is no way back to the old behaviour, not per application and not per observer. A
  promise which can be switched off is not one.

The application does not pay for any of this. `PeaUserTaskHandler` runs the
`@WorkflowTask` notification before it lets the failure out, and the core recognizes a
repeated delivery by its task id, so the method is called once per task however often the
engine delivers it.

`PeaUserTaskObserverTest` (core), `UserTaskObserverIntegrationTest` (Spring Boot) and
`PeaUserTaskObserverTest` (Quarkus) hold all of it.

### 13. No open-task probe is supplied, because this API cannot say "gone"

The core can work a cancellation out for a BPMS which reports none. At every wake-up of a
workflow it asks the adapter about the other tasks it believes are open there, one question per
task, and reports the ones which are gone to the application as canceled. The question is
`OpenTaskProbe` and its answer has three values: `GONE`, `STILL_THERE`, `CANNOT_SAY`. Only the
first leads to a cancellation. The third is in the contract so that an adapter which cannot tell
a refusal from an outage can say so instead of guessing.

This adapter answers none of them, because it cannot reach the first. The Process-Engine-API has
no operation which asks about a task, and a failed command comes back as one untyped
`ExecutionException`, so a `PREFLIGHT_CHECK` completion of the task cannot tell a refusal from an
unreachable engine ([`GAPS.md`](./GAPS.md), entries 10 and 27).

So nothing is supplied, rather than a probe which always says `CANNOT_SAY`. Such a probe would
buy the application nothing and cost it a round trip per open task at every delivery, and the
next reader would have to work out again why it never reports anything. A probe which read a
failure as `GONE` is the worse half of the same choice: an engine which hiccups would cancel the
open work of every workflow it woke up.

The rule holds until the API can answer. A single operation asking whether the engine still has a
task, or the typed errors entry 10 asks for, turns the preflight completion into a probe and this
entry into a superseded one. Until then a change which supplies a probe here has to say which of
the two it got. `PeaOpenTaskProbeTest` fails when one appears anyway, and
[What an application hears about a canceled task](./README.md#what-an-application-hears-about-a-canceled-task)
is what an application reads instead.
