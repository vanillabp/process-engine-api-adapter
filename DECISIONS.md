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

The process service and the deployment service of this adapter hold about a dozen fields each,
most of them collaborators nobody outside the class needs. Which of them a caller may read or
set belongs to the surface of the class, so an accessor is declared per field, and `@Getter` or
`@Setter` on the class is refused even where an IDE offers it: it would publish the current field
list and then keep publishing whatever field a later change adds. `@SuppressWarnings("LombokSetterMayBeUsed")`
on such a class is what keeps that offer from coming back.

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

*Superseded in part by decision 19: the key `fetch-variables` is gone, and a start which still sets it ends. No message names it any more.*

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
inside a workflow module, so that lookup answers a collection and the caller decides. The element
id lookup answers a collection too: an element id is unique within one BPMN file, but nothing keeps
two processes of one module from using the same one. The same pass which reads the process id now also reads the name the modeller wrote on the
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

### 14. A task of a workflow this application does not own is refused as far as this API allows

*Narrowed by decision 18: no subscription is opened for a process of this application nobody claims, so its tasks no longer reach this refusal. What is left is the case this entry is about, a task of another application under the same task type.*

A delivery the core answers with `DeliveryOfAnUnknownWorkflowException` is not treated like any
other failure here. A service task is failed with a retry count of zero, so an engine which reads
the count stops offering it. A user task cannot be answered at all, so it ends in one log line which
names the workflow and says the task was left untouched.

The core's side of this is decision 99 of `adapter-platform-integration`, which words the refusal
and counts it as `vanillabp.task.deliveries.unknown.workflow`. What each adapter does with it is the
adapter's own decision, and this is this adapter's.

It can happen here at all because a task subscription of the Process-Engine-API matches a task type
globally, and there is no tenant (gap 15 in [`GAPS.md`](./GAPS.md)). Two applications which deploy a
BPMN process of the same name are therefore served each other's tasks, and nothing in
`SubscribeForTaskCmd` can narrow that: the restrictions of `CommonRestrictions` all name one running
instance or one definition, and none of them says "only the workflows this application started".

`PeaTaskHandler.determineBpmnProcessId` routes a delivery by the meta entry the engine fills, or by
the single process of the subscription. It does not ask whether the routed process is one this
application owns, and it could not answer that from the delivery anyway: the name is the same on
both sides. So the first thing which notices is the core, when it looks the workflow aggregate up
and finds nothing.

What the API offers was read on 2026-09-27, on version 1.7, the newest on Maven Central.

`ServiceTaskCompletionApi.failTask` takes a
`FailTaskCmd(taskId, reason, errorDetails, retryCount, retryBackoff)`. The retry count is optional
and the API says nothing about what a value of zero means, but it is the only lever a subscriber has
over whether a delivery comes back. The reference
implementation for an embedded Camunda 7 (`process-engine-adapter-camunda-platform-c7-embedded-core`
2025.11.1) passes it straight into `externalTaskService.handleFailure`, where zero is what Camunda 7
turns into an incident, and falls back to its own retry supplier where the command names no count.
So a zero is read where an engine reads it and costs nothing where an engine ignores it.

`UserTaskCompletionApi` has `completeTask` and `completeTaskByError`, and no failure command. Both
of the two move the workflow on, and moving a stranger's workflow on is worse than anything else
this could do. Throwing out of the handler is not an answer either: the reference implementation
logs it, drops the subscription for the task and offers the same task again at the next pull cycle,
for as long as both applications run.

There is nothing else. No incident concept, no dead-letter, no way to give a task back, no way to
ask the engine who owns a workflow. That is the honest answer for the user-task side, and gap 28
records it as an ask to bpm-crafters.

What was decided is this. The service task is failed with `PeaFailTaskCmd.withoutFurtherAttempts`,
which is the ordinary failure plus a retry count of zero. The reason it carries is the core's whole
message, so an engine which shows the reason to an operator shows the sentence which explains the
situation. The log line drops the stack trace, because the message is the finding and a trace would
only name the line of the core which read the database.

The user task is not answered at the engine, and the handler returns normally. The log line says
what the task is about, that it was not notified, and that the task stays untouched. It is a warning
and not an error: nothing in this application is broken.

The user-task handler still returns normally. This looks like the swallowing it replaces, and it is
not the same thing. Returning normally is what keeps the delivery from circling. The reference
engine's pull cycle treats a handler which returns as a delivery that happened and skips the task
while it is unchanged, while a handler which throws deactivates the subscription and the next cycle
hands the same task over as new. So throwing would buy one endless stream of the same log line and
change nothing else.

What changed is the line. It used to be `the CREATED notification for user task '...' failed! The
user task itself stays available.`, at error level and with a stack trace, which reads as a defect
of the code which ran. It is not one.

The retries were not raised instead. The alternative was to make the refusal cost the owning
application nothing: fail the service task with the engine's default retries and let the delivery
come back until somebody separates the two applications. That is the quiet variant, and quiet is the
problem. An application which takes another application's work and says nothing about it does so for
months, and the first anybody hears of it is a workflow which never moved.

Nothing is lost by the zero. The task stays in the engine, untouched and uncompleted, so the
application which owns the workflow can still have it once the two are separated. What the zero
costs is one incident on engines which have incidents, which is exactly the thing somebody has to
see.

Nothing is counted here. The core counts the refusal once, whichever adapter met it. A second
counter in the adapter would count the same event under a second name.

`PeaTaskHandlerTest` and `PeaUserTaskHandlerTest` hold both halves.

### 15. A user task nothing serves is named in a claimed process, and nothing is refused

*Superseded by decision 17: a user task of a claimed process now needs a `@WorkflowTask` method or the line `implemented-externally=true`, the INFO report is gone, and the core holds the rule for every adapter.*

*Its split between a claimed process and one nobody claims goes further with decision 18: a process nobody claims gets no subscription either, so its user tasks are not delivered and dropped any more.*

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task,
somebody works a task list and finishes it, and the workflow moves on. That is why the core hands a
user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it asks for a
method. Until now this adapter said one line on DEBUG about a user task without an external form
reference, and nothing at all about one whose reference no method names. Nobody reads DEBUG, so an
application which drew a notification into its model and forgot the method found out in production,
if at all.

From now on the deployment names such a task. Once per BPMN process, at INFO, while the model is
wired, and only for a process a `@WorkflowService` class of this application claims. The DEBUG line
is gone.

Two cases are named apart, because the way out differs.

A user task whose external form reference no method names is subscribed for all the same: this
adapter opens one subscription per reference the model carries, whatever the application serves. So
the engine offers the task, `PeaUserTaskHandler` finds no method and drops the delivery. The
observers of the application see it and the application itself does not. The way out is a method
named after the reference or after the element.

A user task which names no external form reference never arrives at all. The reference is the name a
subscription asks for, so there is nothing to subscribe under, and a method alone would change
nothing. The way out is the reference in the model plus the method. This is the case the old DEBUG
line was about, and it is the one which loses the most.

This is not the refusal the Camunda 8 adapter has. Decision 53 of that adapter refuses a user task a
job worker serves in a claimed process. The reason it gives is what the shape costs: the cluster
hands out a job, nothing fetches it and the workflow stands at the element with no incident and
nothing in any log. The Process-Engine-API has no such shape. A user task here is the engine's own,
it is delivered as a notification and nothing about it waits for this application.

So the rule is the same where the two can be the same, and it stops where the API stops. What is
taken over is the split: a process this application claims is a process it stands in for, and a
process nobody claims is somebody else's model. What is not taken over is the level. A refusal would
end the boot of an application whose model is right, which would be stricter than the core, whose
own field says a handler is optional. A WARN would be the same claim in a quieter voice, on every
boot, for a model nobody has to change.

Other shapes were looked at and turned down.

Refusing it, the way Camunda 8 refuses its own case, would refuse every model whose user tasks are
worked through a task list, which is a normal model here. A flag to switch the refusal off would be
a flag for the normal case.

A WARN instead of an INFO says that something needs attention. Here the model may be exactly what
the modeller meant, and a warning on every boot for a correct model is how a log teaches people to
skip warnings.

Refusing the user task which names no external form reference was the tempting one, because such an
element is unreachable for VanillaBP whatever the application does. It is still a model which runs:
the engine creates the task and a task list finishes it. An application which uses this adapter for
its service tasks and the engine's own task list for its user tasks is doing nothing wrong, and the
boot of such an application may not end over a user task somebody meant to work by hand.

The check could also sit in the core, which holds `optional` and knows which method serves which
spec, and it could then name the unserved optional specs of a claimed process once for all adapters.
It could not name the second case: a user task without an external form reference never becomes a
spec, so the core never hears about it. The way out differs per BPMS as well, which is the half of
the message worth reading. A report in the core would change the Camunda 8 adapter's boot output,
which this entry does not touch.

One thing is left open on purpose. The Camunda 8 adapter stays silent about the same case for a
Camunda-managed user task whose external form reference no method names. Three adapters then say two
different things about one situation. Closing that is either a change in that adapter or the
core-side report above, and whoever takes it should start from this entry.

`PeaUnservedUserTasksTest` holds both messages, both keys a method may be wired by, the silence
about an unclaimed process, that the line is an INFO, and that a user task without a reference
becomes neither a spec nor a subscription.

### 16. This adapter reports no expression of a model, and the silence is the honest answer

VanillaBP asks every adapter, while it wires a process, for the expressions of that process. It
wants the element, the place inside the element and the text the engine evaluates, and the core
turns that into one guiding message per process. This adapter answers with nothing, so the message
never appears for an application running on it.

Two things are missing, and either one is enough. The first is the model. The Process-Engine-API has
no BPMN model type, so the adapter never holds one, neither the model being deployed nor a model the
engine runs. That is gap 1 and gap 12 in [`GAPS.md`](./GAPS.md), and it is the same reason the
concurrent-token hint is silent here (gap 21).

The second is the expression language, and it is the one which would still stop us if the first were
solved. The adapter does read the deployed BPMN XML with the JDK's streaming reader, far enough to
find the executable process ids, so the text of an attribute is within reach. The text alone answers
nothing. Camunda 7 marks an expression with `${...}` or `#{...}`, Camunda 8 marks one with a leading
`=`, and the Process-Engine-API names neither, because it names no engine. So the adapter cannot
tell an expression from a literal, and it does not know which places the engine behind the API
evaluates at all. A place the engine reads as plain text is a place where naming an expression would
be wrong.

Reading the delimiters of the engine somebody happens to run was the alternative, and it was turned
down. The adapter would have to guess, a wrong guess makes a startup message say something untrue,
and a startup message which is sometimes untrue is worse than one which is absent. The whole point
of this check is that a developer can act on it without reading documentation first.

Nothing else of the feature is switched off. The property which accepts the expressions of a model
is read by the core, so it behaves here the way it behaves everywhere. It just never has anything to
accept.

The way out is not a change in this adapter. An engine would have to say which expression language
it evaluates and in which places, and that ask goes to bpm-crafters together with the model type
rather than next to it. Entry 29 of [`GAPS.md`](./GAPS.md) says the same from the side of what this
API cannot do.

### 17. A user task needs a method or a line, one without a form reference the line only

Supersedes decision 15. The platform decided on 2026-10-07 that every task of a claimed BPMN process
needs a `@WorkflowTask` method or the property `implemented-externally=true`, and that the core holds
that rule for every adapter (decision 119 of `adapter-platform-integration`). The reason is the one the
Camunda adapters give: version 1 asked for the method, and only the application can tell a task meant
for a task list from a forgotten method. This adapter had no version 1, so for it the rule is simply
the one all three adapters share.

**A user task with an external form reference** is handed to the core as before, and the core asks for
the method or the line. The INFO report of decision 15 and `PeaUnservedUserTasks` are gone, because
the core's message now says what the report said, and ends the boot.

**A user task without an external form reference** never becomes a spec of the core, because nothing
can be subscribed for it and so no method can ever be called for it. Decision 15 named it in the
report because the core could not. Now the adapter asks the core whether the line marks it
(`WorkflowTaskWiring.isImplementedExternally`, by the element id). A marked one goes to the core as a
spec, so a method drawn into it next to the line is refused there; an unmarked one ends the boot of a
claimed process with a message of this adapter which names the reference to add or the line. Whether
the process is claimed is asked through `WorkflowTaskWiring.isClaimedByAWorkflowService`.

What decision 15 said against a refusal, that a model worked through a task list is normal here, is
answered by the line: such a model stays normal, and the application says so once per task.

### 18. A process nobody claims gets no subscription and no check

Proposed by story 937. Decided by the maintainer on 2026-10-07.

The platform decided on 2026-10-07 that a process nobody claims is not supported, in a decision of
`adapter-platform-integration` of its own. The core ends the start over a deployed process no
`@WorkflowService` class claims, unless the application marks it with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. A process
nobody claims which reaches this adapter is therefore one somebody else serves, and the adapter
leaves it alone:

- It goes to the engine with its file, under the prefix of `use-prefix` where that mode is set,
  because the bundle is deployed as a whole.
- `wireBpmn` returns for it right away. The core is not asked about its tasks, and a timer, signal
  or conditional start event of it is not refused. That refusal stays for a claimed process, where
  VanillaBP would have to build the aggregate of a start it never hears about.
- `startWorkflowProcessing` opens no subscription for its tasks and user tasks. A delivery for it
  could only end in the refusal of decision 14, so it is better not to ask the engine for one.

Before, every task of such a process got a subscription, and a service task ended in `failTask` with
the default retries. Now a workflow of it stops at its first task until whatever serves it takes the
task.

Every question whether a process is claimed goes to `WorkflowTaskWiring.isClaimedByAWorkflowService`.
`fetchVariablesOf` no longer catches the exception of `resolveWorkflowAggregateIdName`: it only sees
tasks of claimed processes, and the declared id of a renamed process is claimed as well.

What this does not change: the viewer API still lists the definitions of every model this adapter
deployed, a marked process included, because it reports what is deployed.

`PeaUnclaimedProcessTest` holds the timer start which ends nothing, the deployment of the whole file
and the subscription of the claimed process alone. `PeaFetchVariablesTest` holds that a process
nobody claims gets no subscription.

### 19. The key `fetch-variables` is gone, and a start which still sets it ends

Proposed by story 938. Decided by the maintainer on 2026-10-07 and 2026-10-09.

`vanillabp.adapters.<id>.fetch-variables: all` let a subscription ask the engine for the complete
payload of the process instance instead of the set of decision 7. It could be set at four levels,
down to a single task, with the same name and values as on Camunda 8. It was there for a
`@TaskParam` name the core cannot see because it is put together while the delivery runs.

In VanillaBP 2.0 a handler reads its data from the workflow aggregate. A `@TaskParam` name which is
not on the method is not a case 2.0 has to support. A key is surface which cannot be removed after
the release without breaking every application which sets it, so it goes now, on this adapter and
on Camunda 8 together:

- No subscription reads the key. The set of decision 7 is the only answer, at every level.
- An application which still sets the key, with any value and at any of the four levels, does not
  start. The message names every key which sets it, says to remove it and says what a subscription
  asks for now. The platforms still bind the key as text for this message only. On Quarkus that
  also keeps SmallRye from ending the start with a message of its own.
- The two messages of a delivery which misses a variable no longer name the key. The one for a
  `@TaskParam` outside the set points to the workflow aggregate.

What was checked before: no part of 2.0 needs `all` on this adapter. The adapter has no
multi-instance variables of its own to fetch, and a process no workflow service claims gets no
subscription at all (decision 18).

The Business Cockpit was the one user. On this adapter a `@TaskParam` of a
`@UserTaskDetailsProvider` got a variable which no `@WorkflowTask` method reads only through `all`,
because the cockpit reads the payload of the adapter's own subscription and cannot open a second
one. On Camunda 8 such a parameter never gets a process variable. Now it gets one on neither BPMS
unless a `@WorkflowTask` method of the module declares the same name. A details provider reads its
data from the workflow aggregate, and the cockpit strand updates its documentation.

This adapter did not exist in version 1, so there is no `UPGRADE.md` entry.

`PeaFetchVariablesTest#theRemovedKeyEndsTheStart` holds the message, and
`RemovedFetchVariablesBootTest` on Spring Boot and `PeaRemovedFetchVariablesTest` on Quarkus hold
that a start with the key set at adapter and at task level ends with it.
`PeaFetchVariablesTest#aNameOutsideTheSubscriptionFailsGuiding` and
`PeaTaskHandlerTest#aTaskParameterOutsideTheSubscriptionFailsGuiding` hold that no message names the
key.
