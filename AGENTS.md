# Working on process-engine-api-adapter

The VanillaBP adapter for the Process-Engine-API, on Spring Boot and on Quarkus.

Read [`README.md`](./README.md) first, and [`GAPS.md`](./GAPS.md) with it. The gaps are the things
this API cannot do, and most of the adapter's shape follows from them.

What an adapter implements is described once, for every adapter, in
[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
in the platform repository. It names the two interfaces and the calls the core expects back. It
also says what an answer promises and what a wrong answer costs. Read it before you change anything
on the SPI boundary. Where this adapter cannot keep a promise of that document, that is a gap, and
it belongs in `GAPS.md`.

## Why this repository has no `UPGRADE.md`

The other adapter repositories have one. It describes the step from VanillaBP 1 to the 2.0 release:
what a version-1 application sees behave differently, and what it has to change. This adapter never
had a version-1 release. So nobody needs that file here, and it would only fill up with changes
between two snapshots. Do not add one just because the other repositories have it.

A change can still be worth writing down. How a feature works in the end belongs in the wiki,
because that is where users read. A reason which several places in this repository rely on belongs
in [`DECISIONS.md`](./DECISIONS.md). What this API cannot do belongs in [`GAPS.md`](./GAPS.md).

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions which several places in this repository rely
on. It is the ONLY thing the code may cite. The citation has the plain form
`see decision 7 in the repository's DECISIONS.md`, so a grep finds it, and it names only entries of
THIS repository.

Read it before you change behaviour. An entry is not background reading. It is the reason why the
code around it looks the way it does. So a change which contradicts an entry is wrong until the
entry says otherwise.

**A decision is changed or replaced only after asking.** If your change would make an entry untrue,
stop and ask the maintainer before you write the change. If the answer is yes, the same commit
updates the log. The old entry STAYS. It is marked as superseded and names the entry which replaced
it. The new decision takes the next free number. Numbers are never reused and never renumbered,
because a citation in an older release still points at them. Never edit an entry until its old
text is gone.

The same rule holds for adding an entry. A decision gets a number when several places rely on it
and a copy of the explanation in each place would go stale. Anything smaller is a comment in the
place where it belongs. Anything larger is documentation.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, `GAPS.md`, this file,
the Javadoc and comments which explain something, in the main code and in the tests, and the
texts of commits and pull requests. It holds for the
[wiki](https://github.com/vanillabp/process-engine-api-adapter/wiki) as well, because the wiki
clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this repository. The first example comes from this file:

> There was never a version-1 release of this adapter, so there is nobody to write that for, and a
> file here would fill up with changes between two snapshots. Do not add one out of symmetry.

becomes

> This adapter never had a version-1 release. So nobody needs that file here, and it would only
> fill up with changes between two snapshots. Do not add one just because the other repositories
> have it.

The second one also comes from this file:

> A decision earns a number when several places rely on it and copying the explanation to each of
> them would rot; anything smaller is a comment where it belongs, and anything larger is
> documentation.

becomes

> A decision gets a number when several places rely on it and a copy of the explanation in each
> place would go stale. Anything smaller is a comment in the place where it belongs. Anything
> larger is documentation.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13, in the Business Cockpit repository and not in this one. Two
branches claimed the same number, and they had to become 19 and 20. Two more claimed the next one,
and they had to become 21 and 22. Both times it showed up at the merge, which is the worst moment.
A merge happens on GitHub, and nobody can change a `see decision 21` in a Java file there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^### [0-9]+\. '  # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+### [0-9]+\. '                   # for each open pull request
```

The entries of this repository are `###` headings. `gh pr diff` takes no path argument, so the
grep does the filtering.

If your number is taken, your entry gets the next free one. Then correct every citation of it in
the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number which somebody else handed out long ago, and that citation
stays as it is. A search and replace over the whole branch turns a right reference into a wrong
one.

This does not break the rule that a number is never renumbered. That rule is about a merged number,
because a citation in a released artifact points at it. Until the pull request is merged, nothing
outside the branch has seen the number. So correcting it costs nothing beyond the branch.

Every other running number is checked the same way. The story prompts are such a series. They are
kept outside this repository, so they are checked where they are kept.

After you renumber, run `bin/check-decision-citations.sh`. It says whether every citation of a
decision still points at an entry. It also finds a citation which is wrapped over two lines. The
*Checks* workflow runs it on every pull request.

`bin/check-orphaned-javadoc.sh` finds a javadoc block which stands directly in front of a second
one. Javadoc drops such a block without a word, so its text appears nowhere. Run the script when
you wrote or moved a comment. If it reports a block, move the block back to the element it
describes. Do not delete it.

## What code may point at

Code may not point at anything which a later change can make wrong without anybody noticing. So no
story or prompt number, no issue or pull request number, no chat transcript and no person. These
record a conversation at one point in time. A decision entry lives next to the code and is updated
in the same commit as the code. That is why code may cite it.

Where a name can carry the reason, the better fix is the name. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an
entry in the log.

Commit messages and pull request descriptions may cite whatever they like. They record one point
in time themselves.
