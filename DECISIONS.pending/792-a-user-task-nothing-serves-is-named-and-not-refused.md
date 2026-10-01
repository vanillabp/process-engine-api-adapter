# A user task nothing serves is named in a claimed process, and nothing is refused

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task,
somebody works a task list and finishes it, and the workflow moves on. That is why the core hands a
user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it asks for a
method. Until this story this adapter said one line on DEBUG about a user task without an external
form reference, and nothing at all about one whose reference no method names. Nobody reads DEBUG, so
an application which drew a notification into its model and forgot the method found out in
production, if at all.

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

## Why this is not the refusal Camunda 8 has

Decision 53 of the Camunda 8 adapter refuses a user task a job worker serves in a claimed process.
The reason it gives is what the shape costs: the cluster hands out a job, nothing fetches it and the
workflow stands at the element with no incident and nothing in any log. The Process-Engine-API has no
such shape. A user task here is the engine's own, it is delivered as a notification and nothing about
it waits for this application.

So the rule is the same where the two can be the same, and it stops where the API stops. What is
taken over is the split: a process this application claims is a process it stands in for, and a
process nobody claims is somebody else's model. What is not taken over is the level. A refusal would
end the boot of an application whose model is right, which would be stricter than the core, whose own
field says a handler is optional. A WARN would be the same claim in a quieter voice, on every boot,
for a model nobody has to change.

## What was rejected

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
which this story does not touch.

## What this leaves open

The Camunda 8 adapter stays silent about the same case for a Camunda-managed user task whose external
form reference no method names. Three adapters then say two different things about one situation.
Closing that is either one more story for that adapter or the core-side report above, and whoever
takes it should start from this entry.

`PeaUnservedUserTasksTest` holds both messages, both keys a method may be wired by, the silence about
an unclaimed process, that the line is an INFO, and that a user task without a reference becomes
neither a spec nor a subscription.
