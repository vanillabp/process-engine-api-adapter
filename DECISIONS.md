# Decision log

The code of this repository points at the decisions below. A number is handed out once. It is
never reused and never renumbered, so a citation always finds its entry. A decision which is
overturned keeps its entry. The entry is marked as superseded and names the entry which replaced it.

A citation in code reads `see decision 2 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision the platform shares has its own entry in
`adapter-platform-integration`, written from that side. A pointer into another repository breaks
easily, and this log exists to avoid exactly that.

The links below point into this repository's [`README.md`](./README.md) and into
[`GAPS.md`](./GAPS.md). They hold the details an entry leaves out on purpose.

### 1. A command carries the shared aggregate attributes and the aggregate-ID variable, nothing else

The Process-Engine-API has no business key. So the variable named after the ID attribute of the
aggregate is the only way back from a process instance to its workflow. It is written whatever the
sync model says. Next to it travels the state the aggregate shares with the engine, because the
engine can evaluate an expression only against the payload it was given. Nothing else travels: a
correlated message carries no content of its own, and an attribute marked with `@NoSyncWithBPMS`
stays out of every command.

This holds for every command sent for a workflow: starting it, completing a task with or without an
error, completing or canceling an async or user task, and correlating a message. The bullet
*Aggregate sync* under [Behavior and limitations](./README.md#behavior-and-limitations) says what
this means for each operation.

### 2. The deployed bytes carry scoped identifiers, the model in memory keeps plain ones

This BPMS has no namespace which matches a workflow module. So name-clash avoidance is what keeps
two modules apart. The adapter applies it by rewriting the BPMN resource on its way to the
`DeploymentApi`: process ids, message and signal names, error and escalation codes, task
definitions and form references. The `PeaBpmnModel` kept in memory holds the plain identifiers,
because the registries of the core use them as keys. Every delivery which comes back from the
engine is translated to the plain identifiers before the core sees it.

### 3. A class opens its fields one by one, not as a whole

The process service and the deployment service of this adapter hold about a dozen fields each. Most
of them are collaborators nobody outside the class needs. Which of them a caller may read or set is
part of the surface of the class. So an accessor is declared per field, and `@Getter` or `@Setter`
on the class is refused, even where an IDE offers it. Such an annotation would publish today's list
of fields, and then every field a later change adds. `@SuppressWarnings("LombokSetterMayBeUsed")` on
such a class keeps the IDE from offering it again.

### 4. What has to be asked before the commit is a preflight, the work itself runs after it

The Process-Engine-API runs a command in the mode the command carries. This adapter uses the two
modes as the two phases VanillaBP needs. `PREFLIGHT_CHECK` runs inside the transaction of the caller
and only asks, so a guiding error shows up where the application made the call. `SYNC` runs the real
command after the commit. The phase-two outbox dispatches it, so it can be repeated.

The built-in command classes cannot carry a mode other than the default, so the adapter sends
subclasses of them. Two of the commands are `final` and cannot be subclassed at all. That is why
correlating a message and starting a workflow by message have no preflight here. They are
documented in [`GAPS.md`](./GAPS.md) instead of being faked. Where the platform offers a pre-commit
hook, the preflights run in it. Where it offers none, they run at once, so the adapter still works
in a test without one.

### 5. A failure of phase two is reported, not dropped

Every phase-two operation of the process service used to catch `ExecutionException`, write "task
is gone" and consume the outbox entry. That is right for the case it was written for: a repeated
completion of a task which already finished. It is wrong for every other case. This API declares no
typed errors, and an engine which cannot be reached answers exactly like one which rejected the
command. So a completion was lost without a word.

Now every phase-two operation lets the failure through. Its message names the operation, the
process, the workflow module and the adapter. It also says that a blocked entry on a task which
already finished is the harmless case. This covers completing and canceling a task, the same for a
user task, correlating a message, broadcasting a signal and starting a workflow by message. The
last one still had a second way to look successful after the others were fixed. It waits on a
future like all of them, and an interrupted wait returned normally. That marked the entry as done
although no workflow had been started, so the database of the application held an aggregate no
engine knew about. So an interrupt is now a failure of phase two like any other and reaches the
outbox as one.

The DELIVERY path stays as it was, on purpose. There the engine delivers again, which is the
recovery, and no outbox entry is involved. But it now says that the outcome was lost, after an
interrupt as well. A redelivery nobody announced looks like a business method which ran twice for
no reason.
See [A failure of phase two is reported, not dropped](./README.md#a-failure-of-phase-two-is-reported-not-dropped).

### 6. Only what the API cannot do at all is a permanent failure

`isPhaseTwoFailureRepeatable` answers `false` for `UnsupportedOperationException` and for nothing
else. So an entry is blocked at once only where the engine has no such capability: a signal without
a configured `SignalApi`, or a push of a changed aggregate. Everything else is repeated.

Nothing more can be told apart. The API declares no typed errors, so "the engine refused this" and
"the engine cannot be reached" arrive as the same exception. A wrong verdict of "permanent" blocks
work which a retry would have completed.
See [Which phase-two failures are repeated](./README.md#which-phase-two-failures-are-repeated).

### 7. A subscription asks for exactly the variables the handlers declare

*Superseded in part by decision 19: the key `fetch-variables` is gone, and a start which still sets it ends. No message names it any more.*

*Extended by decision 20: the set also carries every variable a `@TaskParam` of an extension's method reads for the served tasks.*

A subscription used to be opened with an empty set, so the engine decided what a task delivery
carried. Now it names the aggregate-id variable of the process it serves. It adds all `@TaskParam`
names the core reports for that task definition. User-task subscriptions do the same.

The names come from the core and not from a scan of the model. A model declares names nobody reads,
and it misses names no model carries. The mock engine trims its payload to what the subscription
asked for, so the tests run the derivation instead of only asserting it.
See [What a subscription asks the engine for](./README.md#what-a-subscription-asks-the-engine-for).

### 8. What this adapter does per operation is a handler, not a pair of methods

The adapter SPI of VanillaBP used to ask for two methods per outbound operation, and this adapter
had eighteen of them. Now it answers a map: one `PhaseOperationHandler` per `PhaseOperation`. Each
handler is the pair of "ask" and "act" for this engine. What the handlers do has not changed. They
send the same preflight commands, the same completions and the same messages. Only the shape moved.

The map states which operations this adapter serves. Two of its entries are there although their
answer is "not here". A signal needs a `SignalApi`, and the adapter may have been built without one.
A changed aggregate cannot be pushed at all, because the API updates the payload of a TASK and not
of a running instance (GAPS entry 18). For a missing entry, VanillaBP would say "this adapter cannot
serve the operation". That is true but does not help. Only this adapter knows which API is missing
and what to model instead. So the entries stay, and the handlers throw with a message which names
the fix. The message of the core is the fallback for an adapter which has nothing to add. It is not
the better answer.

### 9. One list of user-task observers, and the observation names the adapter

The observers of this adapter's user tasks are collected once per application. They are the beans
of `PeaUserTaskObserver` a platform module finds. Every configured adapter id of type
`process-engine-api` gets that one list.

There is nothing to split up anyway. `validateDistinctAdapterInstances` ends the boot as soon as a
second adapter id of this type is configured. The reason is that the engine arrives as a set of
beans the application provides, and they do not know which engine they are
([`GAPS.md`](./GAPS.md), entry 14). One application has one engine, and a registration per id would
be a switch with only one setting today.

The observation still names the adapter its task came from. An observer reports under that name.
This repository is not the only source of adapter ids an extension sees. And on the day the API can
tell two engines apart, the field is already there. A registration per adapter id can still be
added then. It narrows what an observer gets and breaks nobody.

The two platform registrations rely on this, and so does the shape of the observation. What the
seam promises beyond that is in the type javadoc of `PeaUserTaskObserver` and in
[Observing the user tasks of an application](./README.md#observing-the-user-tasks-of-an-application).

### 10. The models read at deployment are indexed once, and the meta keys are spelled once

Nothing can be read back from this engine. So what the deployment pipeline read at boot is all
there is, and `PeaDeployedProcesses` already held it per adapter id. It always answered by process
id. Now it also answers by the BPMN element id of a user task and by its external form reference.
That is an index over the models it holds, not a second store. Whoever asks cannot get an answer
which disagrees with what was deployed. And a new deployment cannot leave behind an entry for a
user task the new model does not have.

The answer is the model and not a trimmed record. Whoever asks reads more of it than such a record
would carry, and the adapter holds the model anyway. A form reference is not unique inside a
workflow module, so that lookup answers a collection and the caller decides. The lookup by element
id answers a collection too. An element id is unique within one BPMN file, but nothing keeps two
processes of one module from using the same one. The pass which reads the process id now also reads
the name the modeller wrote on the process, next to the user-task names it already read. So nobody
has to walk the same bytes a second time.

The keys of `TaskInformation.meta` moved the same way. The Process-Engine-API names the keys a
subscription may be restricted by, but none of the keys a delivery carries. So the words are a
convention. This adapter wrote two of its keys as string literals in its handlers, and whoever
watched its user tasks wrote them again. Now they live in `PeaTaskMeta`, together with readers
which answer "the engine filled none" instead of throwing. What the keys mean is still up to the
Process-Engine-API ([`GAPS.md`](./GAPS.md), entry 6, and entry 3 of the Business Cockpit adapter's).

See [What the adapter remembers about the deployed models](./README.md#what-the-adapter-remembers-about-the-deployed-models)
and [The meta a delivered task carries](./README.md#the-meta-a-delivered-task-carries).

### 11. The subscriptions of a declared process id are composed from what the application serves

A workflow module may declare a BPMN process id under which it deploys nothing. That is how a
renamed process keeps being served. Under `use-prefix`, a task definition reaches the engine as
`<module>__<process>__<task>`. So the tasks of the workflows under the old id carry a name no
subscription of the deployed processes asks for, and nobody notices. A task nobody subscribed for
is not a failed task. It is a workflow which stands still. Those workflows need one more
subscription each, and the question is where its names come from.

The names are COMPOSED from what the application serves. The core names it
(`taskWiringOfProcessesNobodyDeployed`, decision 34 of the platform's own DECISIONS.md). Today that
is the task definition of every `@WorkflowTask` method registered for the declared id. The adapter
scopes each of them by that id, exactly as the deployment scoped the ones it deployed. Camunda 8
composes the same way and for the same reason, in its decision 19. The other way is to read the
models the engine still holds, which is what Camunda 7 does. This API has no repository to read
them from ([`GAPS.md`](./GAPS.md), entry 12). So composing is not the cheaper of two ways here. It
is the only one, and it turns out to be enough for the default mode.

Composing has a price in two places, and both are paid on purpose:

- a task definition may belong to a service task or to a user task, and nothing outside the model
  says which. Both subscriptions are opened, and the one of the wrong kind stays idle. An idle
  subscription of this API costs nothing at all, not even an activation request;
- a `@WorkflowTask` method wired to a BPMN element id (`@WorkflowTask(id = ...)`) names no task
  definition, so no name can be composed for it. Those workflows are the one case which still
  stands still. The start says so and names both ways out: wire the method by task definition, or
  keep deploying the old model under its old id until its workflows have ended.

Where a name is already served, nothing of its own is opened. That is every mode except
`use-prefix`, and also `use-prefix` with `prefix-task-definitions-per-process: false`. The declared
id still joins that subscription for two reasons. The payload the subscription asks for has to
cover what the methods of the old id read. And a delivery which cannot be routed has to name the
old id as one of the reasons. It does NOT join as a candidate for routing. Otherwise a delivery
over a shared name would be ambiguous for every workflow, including the ones served correctly
today. Such a delivery gets what it got before: it is attributed to a deployed process, and the
start says that too. To tell the two apart, the adapter needs the `bpmnProcessId` meta entry of
[`GAPS.md`](./GAPS.md) entry 6, and no engine behind this API fills it today.

The boot is not refused over any of this, and no property is added to refuse it. Whether a
declaration is a defect depends on whether workflows still run under the old id. That is the one
thing this API cannot answer: it has no repository, no query and no history. Decision 38 of the
platform's DECISIONS.md puts that case first: a check which cannot see every model that could hold
its answer stays silent instead of refusing. A refusal would also hit the case this adapter exists
for. The declared ids belong to the module. So in a migration where the other BPMS serves the old
workflows, a refusing adapter would end the boot over workflows another adapter serves correctly.

`PeaDeclaredProcessSubscriptionsTest` holds which subscriptions are opened in each mode, what the
start says, and that a delivery of a task of the old id reaches the methods of the old id.

See [What an id without a model still gets](./README.md#what-an-id-without-a-model-still-gets).

### 12. An observer which fails disturbs the delivery

The user-task observers used to cost the delivery nothing. `PeaUserTaskObservers` caught what an
observer threw, wrote an ERROR line and handed the task on. The javadoc of `PeaUserTaskObserver`
promised that it works this way. That promise is withdrawn. It was never written down as an entry
here. It lived in that javadoc and in
[Observing the user tasks of an application](./README.md#observing-the-user-tasks-of-an-application),
and both of them now say what this entry says.

What changed is the kind of work an observer does. This seam was first built for a cockpit
extension. That extension used to only note that a task had arrived. It described the task later,
from a stored entry which was repeated until it went through. Today that work runs inside the call
of the observer. A failure there leaves nothing behind to repeat. So a swallowed failure is a
report which never arrives, plus an ERROR line nobody reads. On an embedded Camunda 7 or on
Camunda 8 the question does not come up. There the adapter watches such a task from a listener
which holds the transition, so a failure becomes an incident somebody has to look at. This API
offers no listener and no incident, so the disturbance has to come from the delivery itself.

So an observer which throws fails the delivery. What happens next is up to the engine, and the API
says nothing about it ([`GAPS.md`](./GAPS.md), entry 25). So it was measured instead of assumed.
The measurement used the API's own reference implementation for an embedded Camunda 7
(`process-engine-adapter-camunda-platform-c7-embedded-core` 2025.11.1 on Camunda 7.24, measured
2026-09-17). There a delivery which throws is logged, and the task is dropped from the list of
delivered tasks. The next pull hands it to the subscription again as a new delivery. The user task
stays in the engine, and nothing raises an incident. The repetition stops as soon as the observer
works. So the failure is loud while it lasts, and the application loses nothing. A termination
which throws is not repeated there, and it disturbs all the same. An observer which loses a
termination has a defect either way. A rule with an exception for the quiet half would just be the
old promise again.

Four smaller answers go with it:

- The other observers are told before the failure leaves. Otherwise who hears about a task would
  depend on the order in which the platform happened to resolve the beans.
- Several failures travel together. The first one is thrown, and the others are attached to it as
  suppressed exceptions, so none of them is lost.
- A failure while describing the task for the observers disturbs as well. An observer which gets
  nothing to see is in the same position as one which could do nothing.
- There is no way back to the old behaviour, neither per application nor per observer. A promise
  which can be switched off is not a promise.

The application does not pay for any of this. `PeaUserTaskHandler` runs the `@WorkflowTask`
notification before it lets the failure out. The core recognizes a repeated delivery by its task
id, so the method is called once per task, however often the engine delivers it.

`PeaUserTaskObserverTest` (core), `UserTaskObserverIntegrationTest` (Spring Boot) and
`PeaUserTaskObserverTest` (Quarkus) hold all of it.

### 13. No open-task probe is supplied, because this API cannot say "gone"

The core can work out a cancellation for a BPMS which reports none. Each time a workflow wakes up,
the core asks the adapter about the other tasks it believes are open there, one question per task.
It reports the ones which are gone to the application as canceled. The question is `OpenTaskProbe`,
and its answer has three values: `GONE`, `STILL_THERE` and `CANNOT_SAY`. Only the first leads to a
cancellation. The third is in the contract for an adapter which cannot tell a refusal from an
outage. Such an adapter can say so instead of guessing.

This adapter answers none of them, because it can never answer the first. The Process-Engine-API
has no operation which asks about a task. A failed command comes back as one untyped
`ExecutionException`. So a `PREFLIGHT_CHECK` completion of the task cannot tell a refusal from an
engine which cannot be reached ([`GAPS.md`](./GAPS.md), entries 10 and 27).

So nothing is supplied, not even a probe which always says `CANNOT_SAY`. Such a probe would give the
application nothing. It would cost one round trip per open task at every delivery, and the next
reader would have to work out again why it never reports anything. A probe which read a failure as
`GONE` is the worse half of the same choice: an engine with a short hiccup would cancel the open
work of every workflow it woke up.

The rule holds until the API can answer. One operation which asks whether the engine still has a
task would change it, and so would the typed errors entry 10 asks for. Either one turns the
preflight completion into a probe, and this entry into a superseded one. Until then, a change which
supplies a probe here has to say which of the two it got. `PeaOpenTaskProbeTest` fails when a probe
appears anyway. An application reads
[What an application hears about a canceled task](./README.md#what-an-application-hears-about-a-canceled-task)
instead.

### 14. A task of a workflow this application does not own is refused as far as this API allows

*Narrowed by decision 18: no subscription is opened for a process of this application nobody claims, so its tasks no longer reach this refusal. What is left is the case this entry is about, a task of another application under the same task type.*

The core may answer a delivery with `DeliveryOfAnUnknownWorkflowException`. Such a delivery is not
treated like any other failure here. A service task is failed with a retry count of zero, so an
engine which reads the count stops offering it. A user task cannot be answered at all. So it ends
in one log line, which names the workflow and says the task was left untouched.

The core's side of this is decision 99 of `adapter-platform-integration`. It words the refusal and
counts it as `vanillabp.task.deliveries.unknown.workflow`. What each adapter does with it is that
adapter's own decision, and this is the decision of this adapter.

It can happen here at all because a task subscription of the Process-Engine-API matches a task type
across the whole engine, and there is no tenant (gap 15 in [`GAPS.md`](./GAPS.md)). Two
applications which deploy a BPMN process of the same name therefore get each other's tasks.
Nothing in `SubscribeForTaskCmd` can narrow that. The restrictions of `CommonRestrictions` all name
one running instance or one definition. None of them says "only the workflows this application
started".

`PeaTaskHandler.determineBpmnProcessId` routes a delivery by the meta entry the engine fills, or by
the single process of the subscription. It does not ask whether this application owns the routed
process. It could not answer that from the delivery anyway, because the name is the same on both
sides. So the first one to notice is the core, when it looks up the workflow aggregate and finds
nothing.

What the API offers was read on 2026-09-27, on version 1.7, the newest on Maven Central.

`ServiceTaskCompletionApi.failTask` takes a
`FailTaskCmd(taskId, reason, errorDetails, retryCount, retryBackoff)`. The retry count is optional,
and the API says nothing about what a value of zero means. But it is the only lever a subscriber
has over whether a delivery comes back. The reference implementation for an embedded Camunda 7
(`process-engine-adapter-camunda-platform-c7-embedded-core` 2025.11.1) passes it straight into
`externalTaskService.handleFailure`. There zero is what Camunda 7 turns into an incident. Where the
command names no count, the reference implementation falls back to its own retry supplier. So a
zero is read where an engine reads it, and it costs nothing where an engine ignores it.

`UserTaskCompletionApi` has `completeTask` and `completeTaskByError`, and no failure command. Both
of them move the workflow on, and moving the workflow of a stranger on is the worst thing this
could do. Throwing out of the handler is not an answer either. The reference implementation logs
it, drops the subscription for the task and offers the same task again at the next pull cycle, for
as long as both applications run.

There is nothing else. There is no incident, no dead-letter queue, no way to give a task back and
no way to ask the engine who owns a workflow. That is the honest answer for the user-task side, and
gap 28 records it as a request to bpm-crafters.

This is what was decided. The service task is failed with `PeaFailTaskCmd.withoutFurtherAttempts`,
which is the ordinary failure plus a retry count of zero. The reason it carries is the whole
message of the core. So an engine which shows the reason to an operator shows the sentence which
explains the situation. The log line leaves out the stack trace. The message is the finding, and a
trace would only name the line of the core which read the database.

The user task is not answered at the engine, and the handler returns normally. The log line says
what the task is about, that it was not notified, and that the task stays untouched. It is a
warning and not an error, because nothing in this application is broken.

The user-task handler still returns normally. This looks like the swallowing it replaces, but it is
not the same thing. Returning normally is what keeps the delivery from going round in circles. The
pull cycle of the reference engine treats a handler which returns as a delivery that happened. It
skips the task while the task is unchanged. A handler which throws deactivates the subscription,
and the next cycle hands over the same task as a new one. So throwing would only buy an endless
stream of the same log line and change nothing else.

What changed is the line. It used to be `the CREATED notification for user task '...' failed! The
user task itself stays available.`, at error level and with a stack trace. That reads as a defect
of the code which ran, and it is not one.

The retries were not raised instead. The other option was to make the refusal cost the owning
application nothing: fail the service task with the default retries of the engine, and let the
delivery come back until somebody separates the two applications. That is the quiet option, and
being quiet is the problem. An application which takes the work of another application and says
nothing does so for months. The first anybody hears of it is a workflow which never moved.

Nothing is lost by the zero. The task stays in the engine, untouched and not completed. So the
application which owns the workflow can still have it once the two are separated. The zero costs
one incident on engines which have incidents, and that is exactly the thing somebody has to see.

Nothing is counted here. The core counts the refusal once, whichever adapter met it. A second
counter in the adapter would count the same event under a second name.

`PeaTaskHandlerTest` and `PeaUserTaskHandlerTest` hold both halves.

### 15. A user task nothing serves is named in a claimed process, and nothing is refused

*Superseded by decision 17: a user task of a claimed process now needs a `@WorkflowTask` method or the line `implemented-externally=true`, the INFO report is gone, and the core holds the rule for every adapter.*

*Its split between a claimed process and one nobody claims goes further with decision 18: a process nobody claims gets no subscription either, so its user tasks are not delivered and dropped any more.*

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task,
somebody works through a task list and finishes it, and the workflow moves on. That is why the core
hands a user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it
asks for a method. Until now this adapter wrote one DEBUG line about a user task without an
external form reference. It said nothing at all about a user task whose reference no method names.
Nobody reads DEBUG. So an application which drew a notification into its model and forgot the
method found out in production, if at all.

From now on the deployment names such a task. It does so once per BPMN process, at INFO, while the
model is wired, and only for a process which a `@WorkflowService` class of this application claims.
The DEBUG line is gone.

Two cases are named separately, because the way out is different.

A user task whose external form reference no method names is subscribed for all the same. This
adapter opens one subscription per reference the model carries, whatever the application serves.
So the engine offers the task, `PeaUserTaskHandler` finds no method and drops the delivery. The
observers of the application see it, but the application itself does not. The way out is a method
named after the reference or after the element.

A user task which names no external form reference never arrives at all. The reference is the name
a subscription asks for. So there is nothing to subscribe under, and a method alone would change
nothing. The way out is the reference in the model plus the method. The old DEBUG line was about
this case, and it is the case which loses the most.

This is not the refusal the Camunda 8 adapter has. Decision 53 of that adapter refuses a user task
which a job worker serves in a claimed process. The reason it gives is what this shape costs: the
cluster hands out a job, nothing fetches it, and the workflow stands at the element with no
incident and nothing in any log. The Process-Engine-API has no such shape. A user task here belongs
to the engine. It is delivered as a notification, and nothing about it waits for this application.

So the rule is the same where the two can be the same, and it stops where the API stops. The split
is taken over: a process this application claims is a process it stands in for, and a process
nobody claims is somebody else's model. The level is not taken over. A refusal would end the boot
of an application whose model is right. That would be stricter than the core, whose own field says
a handler is optional. A WARN would make the same claim in a quieter voice, on every boot, for a
model nobody has to change.

Other shapes were looked at and turned down.

Refusing it, the way Camunda 8 refuses its own case, would refuse every model whose user tasks are
worked through a task list. Here such a model is normal. A flag to switch the refusal off would be
a flag for the normal case.

A WARN instead of an INFO says that something needs attention. Here the model may be exactly what
the modeller meant. A warning on every boot for a correct model teaches people to skip warnings.

Refusing the user task which names no external form reference was the tempting option. VanillaBP
can never reach such an element, whatever the application does. But it is still a model which
runs: the engine creates the task, and a task list finishes it. An application may use this adapter
for its service tasks and the engine's own task list for its user tasks. It is doing nothing wrong.
Its boot must not end over a user task somebody meant to work on by hand.

The check could also sit in the core. The core holds `optional` and knows which method serves which
spec, so it could name the unserved optional specs of a claimed process once for all adapters. But
it could not name the second case. A user task without an external form reference never becomes a
spec, so the core never hears about it. The way out also differs per BPMS, and that is the half of
the message worth reading. A report in the core would also change the boot output of the Camunda 8
adapter, which this entry does not touch.

One thing is left open on purpose. The Camunda 8 adapter stays silent about the same case for a
Camunda-managed user task whose external form reference no method names. So three adapters say two
different things about one situation. Closing that gap needs either a change in that adapter or the
report in the core described above. Whoever takes it on should start from this entry.

`PeaUnservedUserTasksTest` holds both messages, both keys a method may be wired by, the silence
about a process nobody claims, that the line is an INFO, and that a user task without a reference
becomes neither a spec nor a subscription.

### 16. This adapter reports no expression of a model, and the silence is the honest answer

While VanillaBP wires a process, it asks every adapter for the expressions of that process. It
wants the element, the place inside the element and the text the engine evaluates. The core turns
that into one guiding message per process. This adapter answers with nothing, so an application
running on it never sees that message.

Two things are missing, and either one is enough. The first is the model. The Process-Engine-API has
no type for a BPMN model, so the adapter never holds one. It holds neither the model being deployed
nor a model the engine runs. That is gap 1 and gap 12 in [`GAPS.md`](./GAPS.md). It is also why the
hint about concurrent tokens is silent here (gap 21).

The second is the expression language, and it would still stop us if the first were solved. The
adapter does read the deployed BPMN XML with the streaming reader of the JDK, far enough to find the
executable process ids. So the text of an attribute is within reach. But the text alone answers
nothing. Camunda 7 marks an expression with `${...}` or `#{...}`, and Camunda 8 marks one with a
leading `=`. The Process-Engine-API names neither, because it names no engine. So the adapter cannot
tell an expression from a literal. It also does not know at which places the engine behind the API
evaluates anything at all. Where the engine reads plain text, naming an expression would be wrong.

The other option was to read the delimiters of whatever engine somebody happens to run. It was
turned down. The adapter would have to guess, and a wrong guess makes a startup message say
something untrue. A startup message which is sometimes untrue is worse than none. The whole point
of this check is that a developer can act on it without reading documentation first.

Nothing else of the feature is switched off. The core reads the property which accepts the
expressions of a model, so it behaves here the way it behaves everywhere. It just never has
anything to accept.

The way out is not a change in this adapter. An engine would have to say which expression language
it evaluates, and at which places. That request goes to bpm-crafters together with the model type,
not as a separate one. Entry 29 of [`GAPS.md`](./GAPS.md) says the same, from the side of what this
API cannot do.

### 17. A user task needs a method or a line, one without a form reference the line only

Supersedes decision 15. On 2026-10-07 the platform decided two things. Every task of a claimed BPMN
process needs a `@WorkflowTask` method or the property `implemented-externally=true`. And the core
holds that rule for every adapter (decision 119 of `adapter-platform-integration`). The reason is
the one the Camunda adapters give: version 1 asked for the method, and only the application can
tell a task meant for a task list from a forgotten method. This adapter had no version 1, so for it
the rule is simply the one all three adapters share.

**A user task with an external form reference** is handed to the core as before, and the core asks
for the method or the line. The INFO report of decision 15 and `PeaUnservedUserTasks` are gone. The
message of the core now says what the report said, and it ends the boot.

**A user task without an external form reference** never becomes a spec of the core. Nothing can be
subscribed for it, so no method can ever be called for it. Decision 15 named it in the report
because the core could not. Now the adapter asks the core whether the line marks it
(`WorkflowTaskWiring.isImplementedExternally`, by the element id). A marked one goes to the core as
a spec, so the core refuses a method which is wired to it next to the line. An unmarked one ends
the boot of a claimed process with a message of this adapter. The message names the reference to
add, or the line. Whether the process is claimed is asked through
`WorkflowTaskWiring.isClaimedByAWorkflowService`.

Decision 15 argued against a refusal: a model worked through a task list is normal here. The line
answers that. Such a model stays normal, and the application says so once per task.

### 18. A process nobody claims gets no subscription and no check

Proposed by story 937. Decided by the maintainer on 2026-10-07.

On 2026-10-07 the platform decided, in a decision of its own in `adapter-platform-integration`,
that a process nobody claims is not supported. The core ends the start over a deployed process
which no `@WorkflowService` class claims. The exception is a process the application marks with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. So a process
nobody claims which reaches this adapter is one somebody else serves, and the adapter leaves it
alone:

- It goes to the engine with its file, under the prefix of `use-prefix` where that mode is set,
  because the bundle is deployed as a whole.
- `wireBpmn` returns for it right away. The core is not asked about its tasks, and a timer, signal
  or conditional start event of it is not refused. That refusal stays for a claimed process, where
  VanillaBP would have to build the aggregate of a start it never hears about.
- `startWorkflowProcessing` opens no subscription for its tasks and user tasks. A delivery for it
  could only end in the refusal of decision 14, so it is better not to ask the engine for one.

Before, every task of such a process got a subscription, and a service task ended in `failTask`
with the default retries. Now a workflow of such a process stops at its first task until whatever
serves it takes the task.

Every question whether a process is claimed goes to
`WorkflowTaskWiring.isClaimedByAWorkflowService`. `fetchVariablesOf` no longer catches the
exception of `resolveWorkflowAggregateIdName`. It only sees tasks of claimed processes, and the
declared id of a renamed process is claimed as well.

What this does not change: the viewer API still lists the definitions of every model this adapter
deployed, a marked process included, because it reports what is deployed.

`PeaUnclaimedProcessTest` holds three things: the timer start which ends nothing, the deployment
of the whole file, and that only the claimed process gets a subscription. `PeaFetchVariablesTest`
holds that a process nobody claims gets no subscription.

### 19. The key `fetch-variables` is gone, and a start which still sets it ends

Proposed by story 938. Decided by the maintainer on 2026-10-07 and 2026-10-09.

`vanillabp.adapters.<id>.fetch-variables: all` let a subscription ask the engine for the complete
payload of the process instance, instead of the set of decision 7. It could be set at four levels,
down to a single task, with the same name and values as on Camunda 8. It was there for a
`@TaskParam` name the core cannot see, because the name is put together while the delivery runs.

In VanillaBP 2.0 a handler reads its data from the workflow aggregate. A `@TaskParam` name which is
not on the method is not a case 2.0 has to support. A key is part of the surface. After the release
it cannot be removed without breaking every application which sets it. So it goes now, on this
adapter and on Camunda 8 together:

- No subscription reads the key. The set of decision 7 is the only answer, at every level.
- An application which still sets the key, with any value and at any of the four levels, does not
  start. The message names every key which sets it, says to remove it, and says what a subscription
  asks for now. The platforms still bind the key as text, for this message only. On Quarkus this
  also keeps SmallRye from ending the start with a message of its own.
- The two messages of a delivery which misses a variable no longer name the key. The one for a
  `@TaskParam` outside the set points to the workflow aggregate.

What was checked first: no part of 2.0 needs `all` on this adapter. The adapter has no
multi-instance variables of its own to fetch. And a process no workflow service claims gets no
subscription at all (decision 18).

The Business Cockpit was the one user. On this adapter, a `@TaskParam` of a
`@UserTaskDetailsProvider` got a variable which no `@WorkflowTask` method reads only through `all`.
The reason is that the cockpit reads the payload of the adapter's own subscription and cannot open
a second one. On Camunda 8 such a parameter never gets a process variable. Now it gets one on
neither BPMS, unless a `@WorkflowTask` method of the module declares the same name. A details
provider reads its data from the workflow aggregate, and the cockpit strand updates its
documentation.

This adapter did not exist in version 1, so there is no `UPGRADE.md` entry.

`PeaFetchVariablesTest#theRemovedKeyEndsTheStart` holds the message.
`RemovedFetchVariablesBootTest` on Spring Boot and `PeaRemovedFetchVariablesTest` on Quarkus hold
that a start with the key set at adapter level and at task level ends with it.
`PeaFetchVariablesTest#aNameOutsideTheSubscriptionFailsGuiding` and
`PeaTaskHandlerTest#aTaskParameterOutsideTheSubscriptionFailsGuiding` hold that no message names the
key.

### 20. A subscription asks for what the extensions read as well

The Process-Engine-API hands a task to exactly one subscription. So an extension gets no
subscription of its own. It reads what the subscription of this adapter delivered, through a
`PeaUserTaskObserver`. Until now that subscription asked only for the variables of the
`@WorkflowTask` methods (decision 7). A `@TaskParam` of a method of an extension, for example a
details provider of the Business Cockpit, got its value only where a workflow method read the same
variable. Otherwise it got `null`.

Now `fetchVariablesOf` also asks the core `WorkflowTaskWiring#extensionTaskParameterNames` for
every task it serves, and the subscription fetches the union. The core answers for every extension
which registered a handler contract, so the adapter needs to know nothing about the extensions.

The keys go in the order in which an extension looks up a task: the BPMN element id first, then the
task definition. That is the order of the Business Cockpit, so the variables fetched are those of
the method which runs there. A task of a process nobody deployed has no element id, so it is asked
by its task definition alone.

The question is asked for service tasks as well as for user tasks. Today an observer sees only user
tasks. But the core answers the same question for both, and a service task no extension reads
costs nothing: the answer is empty.
