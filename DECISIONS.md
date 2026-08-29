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
