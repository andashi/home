# Working agreements for AI agents

This repo is a fork of `MM2-0/Kvaesitso` (see `docs/architecture/adr/0007-fork-strategy.md`).
Read `docs/architecture/README.md` and the ADRs before changing anything.

## Language

**All code and all documentation in English.** Identifiers, comments, commit
messages, docs, ADRs, test names — no exceptions. Chat with the user may be
German; artifacts never are.

## Commits

No AI-attribution in commits. Never add "Co-Authored-By", "Generated with
[tool]" footers, robot emojis, or any other signature/attribution line to a
commit message — this is the default behavior of some tools (Claude Code in
particular) and must be suppressed. A commit message reads exactly like one a
human engineer wrote: subject line + body, nothing else, no exceptions.

This paragraph was not enough on its own: the tool instructs the opposite, and
20 commits carrying those lines reached public main before anyone noticed. So
it is enforced in two places. Enable the hook once per clone:

```bash
git config core.hooksPath .githooks
```

`.githooks/commit-msg` rejects the commit before it exists. CI
(`.github/workflows/commit-hygiene.yml`) checks every commit in a pull request,
because a hook only helps in a clone that enabled it. Both match at line start,
so prose *about* attribution is fine, and both allow `Co-Authored-By` for
actual human co-authors.

## Referring to an issue from a pull request

**GitHub's closing-keyword parser ignores negation.** A pull request body saying
"test-only: it does not fix #NNN" is read as `fix #NNN`, and merging it closes
that issue. It happened on 2026-09-25: #152 said in its own body that it was
test-only and denied fixing the issue it referenced, with the keyword sitting
immediately before the number. GitHub listed the issue under
`closingIssuesReferences`, and the merge closed it - two minutes after the
defect had fired again in CI and blocked another pull request's proof run.

So a pull request that does not close an issue writes **`Refs #NNN`**, and never
puts `close`, `fix` or `resolve` in front of a number, not even inside a
sentence that denies it. Before merging a partial pull request, check what
GitHub thinks it closes:

```bash
gh pr view <n> --json closingIssuesReferences \
  --jq '[.closingIssuesReferences[].number]'
```

An empty list is what a partial pull request should print.

**The body is not the only channel.** GitHub parses the same keywords in
**commit messages** that land on the default branch, and
`closingIssuesReferences` does not see them: it reflects the pull request body
alone. So check both before merging a partial pull request:

```bash
git log --format=%B origin/main..HEAD | grep -inE \
  '(^|[^a-z])(close[sd]?|fix(e[sd])?|resolve[sd]?)[[:space:]]*:?[[:space:]]*([a-z0-9._-]+/[a-z0-9._-]+)?(#|gh-)[0-9]+'
```

No output is what a partial pull request should print.

The alternation is fiddly on purpose. GitHub accepts nine keywords - `close`,
`closes`, `closed`, `fix`, `fixes`, `fixed`, `resolve`, `resolves`, `resolved` -
and documents two reference forms, `#123` and `owner/repo#123`. `GH-123` is
matched as well, deliberately: it is an autolink form and whether it closes is
not documented either way. The costs are not symmetric - a false positive sends
somebody to look at a line that turns out to be harmless, a false negative lets
a live issue close on merge - so this pattern errs toward matching. The first
version of this command in #161 covered neither `fixes #123` nor `closes #123`,
the two most common of them, so it printed nothing on exactly the mistake it
exists to catch, while matching `disclose #123`, which closes nothing. It is now
checked against a table of all nine keywords, the three reference forms and six
strings that must not match.

Both halves of this section were written the hard way. The first draft quoted
the offending sentence in the text, and the `gh pr view` check caught it. The
corrected draft still quoted it in a **commit message**, which that check does
not read - and merging the document that explains this trap closed the issue a
second time, seven hours after the first. This text writes `#NNN` rather than a
real number for that reason, in prose and in commit messages alike.

**The keyword counts even when the sentence is about a different pull request.**
A body saying "#171 closes #NNN" - a statement of fact about somebody else's
work - is parsed as *this* pull request closing that issue, exactly as the
negation case is. Write "settles that issue", or name the other pull request
without a keyword in front of the number. Found on #175 on 2026-09-26, which
listed an issue it had nothing to do with.

**`closingIssuesReferences` updates with a lag.** Re-read it a little after
editing the body, not immediately: the field served right after an edit can
still be the old one, so a check run at once can report a reference that is
already gone, or miss one that has just appeared. Both directions are wrong and
only one of them is safe.

A wrongly closed issue is not a bookkeeping problem. It takes a live defect off
the list everyone reads while it is still failing builds.

## Merging a pull request

Every condition in this section holds before a merge, no exceptions and no
judgement about how small the diff is. **The number is deliberately not given:**
it was three when this was written and has grown with every hole somebody found,
and a stale count in a rule is one people stop reading - the merge script said
"all four conditions hold" while checking seven. The original three: every check
green, **zero** unresolved review threads, and CodeRabbit's review covering the
**current head**. If the hourly quota is exhausted (10 included reviews per
hour, rolling), wait for it. A pull request that waits an hour
costs an hour; one merged unreviewed costs whatever it broke.

Recount immediately before merging rather than trusting a count from earlier in
the session: CodeRabbit posts after a push, so a thread can open between the
check and the merge.

**Make the check gate the merge, and pass `--match-head-commit`.** A recount
printed beside a merge stops nothing: on #194 it reported `threads: 1` and the
merge ran anyway, because they were two statements rather than a condition.
And a recount cannot see a *push* in the same gap - the verification would be of
one head and the merge of another, with nothing to say so. `gh pr merge
--match-head-commit <sha>` refuses when the head has moved.

**A check run and a commit status are different objects, and a gate that treats
them alike waits for nothing.** `statusCheckRollup` mixes both. A check run
carries `.status` - queued, in progress, completed - *and* a `.conclusion`; a
**commit status** carries `.state` and **no `.status` at all**. So
`select((.status // "COMPLETED") != "COMPLETED")` reads every commit status as
finished, and a waiter built on it announced "all checks completed" while
CodeRabbit's status was `PENDING` with "Review in progress" - written twenty
minutes after the same session had documented this exact shape. Select on
`(.conclusion // .state)` and require success, so a missing field cannot
default to done.

**Normalise the case, because it depends on which API you read.** Measured on
one commit on 2026-09-29: `gh pr view --json statusCheckRollup`, which is
GraphQL, gives `SUCCESS`, `FAILURE`, `PENDING`; the REST endpoints for the same
commit give `success`, `failure`, `pending`. A gate written against one and
copied to the other matches nothing and refuses every merge - safe, and still a
gate nobody can use. **And the two disagree on what "not finished" looks like**:
GraphQL returns an empty string for a check that is still running, REST returns
`null`. That one is not safe in either direction - classed as a failure it
blocks a green pull request, classed as a success it merges an unfinished one.
A watcher written in this session did the former within an hour of this entry
being written, because a reorder moved its failure test ahead of its
still-running test. Upper-case or lower-case everything before comparing, and
treat empty and null alike.

**And an empty rollup is not green.** A pull request whose checks have not
registered yet returns an empty list, which every "are any of them failing?"
filter answers with *no*. Require a positive count before reading the verdict.

**On `gh` before v2.99.0, `--delete-branch` makes `gh` check out `main` locally
after the merge**, which fails whenever `main` is checked out in another
worktree - and the command then exits non-zero for a merge that **has already
succeeded**, leaving the branch behind. Fired on #207, because this clone runs
2.98.0; upstream fixed it in v2.99.0 (`fix(pr merge): safely handle
--delete-branch with linked worktrees`, cli/cli#14007). **So the first remedy is
to upgrade `gh`**, and the version-independent one is to merge without the flag,
confirm `state == MERGED` through the API, then delete the ref through the API.

The scoping matters as much as the trap: the first draft of this paragraph
claimed it of `gh` in general, and review caught it. A tool bug written down
without its version outlives the bug and sends the next reader to work around
something that was fixed.

The lesson under it is not version-scoped. Every other failure in this document
points the other way, where a broken check answers permissively; this one is a
**correct action reporting failure**, and it is no better: a caller either
retries a merge that happened, or reports that it did not.

**Check what the merge goes into, not only the pull request.** The CI section
below says red on `main` is fixed before the next merge, and for a long time
nothing enforced it - which is #132 exactly. A gate that reads only the pull
request will merge onto a `main` whose own run is red, or has not finished.
Refuse both, with **different** messages: an unfinished run means wait, a failed
one means fix `main` first. One message for both is how the wrong action gets
taken.

**"Resolved" is not "fixed".** CodeRabbit resolves its own threads on a rebase,
including ones whose fixes the rebase does not contain - so a count of zero can
be a true answer about a false state. A thread the bot closed with no reply
naming a fix commit has not been shown to be addressed; read it before it
counts.

**If you automate that check, the bot has two names in one response.**
`resolvedBy.login` comes back as `coderabbitai[bot]`, while the same actor is
`coderabbitai` in `comments.nodes[].author.login`. A condition comparing the
resolver to `coderabbitai` therefore matches nothing and counts zero - and zero
is exactly what "no bot-resolved threads" looks like, so it reads as a pass. It
sat dead in the merge gate from the day it was written until #212, where the bot
resolved its own thread and the gate waved it through. Strip the `[bot]` suffix
before comparing, on both sides, and prove it by resolving a thread as the bot
and watching the gate refuse.

**A finding, an "addressed" claim and a suggested fix deserve three different
levels of trust.** CodeRabbit's findings have been real every time. Its
`✅ Addressed in commit X` was wrong twice in one day - once naming the range that
*introduced* the criticised text, once a commit that did not contain the fix - so
it is not evidence, and only a human reply counts.

**A human reply is a weaker guarantee than it looks, and it is worth knowing why
rather than trusting it.** It is also a claim: that the named commit contains the
fix, and that the commit is still in the head. The second half is checkable and
**refuses benign cases so often that checking it is worse than not** - measured on
this very entry's pull request, where the two commits cited in its own resolution
were absent from the head after a rebase that had preserved every line of the fix.
After a rebase a cited hash is not even a locator. The first half - that the commit
contains the fix - is not mechanisable at all.

So the gate asks for a human reply naming a commit because **that puts a person
between a resolved thread and a merge**, not because the hash proves anything.
Whoever writes the reply is the verification; if they did not look, nothing did. And its suggested *remedy* can
carry the same defect class as the finding: on #214 it proposed observing a
`StateFlow` whose value for the main case is `emptyMap()`, which an observer
already holds, so it would never emit. That was rejected and **the switch to it
made a break check**, which is how to record refusing a remedy.

**List the dependents before deleting a merged branch, and refuse while any
exist** - and **fail closed**, because "no dependents" and "could not ask" are the
same empty result.

This is a conservative policy rather than a documented consequence, and the first
draft said otherwise. It claimed deletion *closes* a dependent pull request
irreversibly, citing #100 -> #101. **That case does not support it**: #100 was
closed rather than merged, and #101 ended up merged against `main`. GitHub
documents retargeting a dependent to the merged pull request's base branch. So the
mechanism is unverified here and the guard is kept for a different reason - a
dependent silently changing base under a merge is a state nobody asked for, and
retargeting it deliberately costs one command.

**A base behind `main` is only a problem when it overlaps - with one caveat
that review caught in this very section.** The rebase rule protects against a
review of a tree whose *relevant* parts have moved, so the first question is
whether the commits between the base and `main` touch any file the pull request
touches. Insisting the base equal `main` instead livelocks a pull request
whenever a review takes as long as the gap between merges, and a condition that
blocks for no reason is one somebody carves an exception into.

But **an empty pathname intersection does not prove the behaviour is
unchanged**: a commit to something the pull request's files *call* changes them
without touching their paths. So the allowance does not apply when any
intervening commit touches a **shared path**, and what counts as one is defined
by `e2e/ci/base-behind.sh`, not by this sentence - there, rebase and re-review.
Outside those, path-disjointness is a heuristic that has earned its keep, not a
proof, and whoever merges still owns the judgement.

**The predicate is the authority, and this paragraph used to disagree with it.**
It listed `e2e/lib/`, `core/`, `services/`, `data/`, a build file and the
version catalog; the script also counts `app/`, `libs/` and `gradle/`, and its
header carries the reasoning for every path it counts and every one it does not.
**This paragraph deliberately does not summarise that reasoning either.** Its
first draft did, and got the summary slightly wrong within three lines of saying
not to - review caught it. Read the header. On 2026-09-28 a session proposed a
merge order on the
ground that an `app/ui`-only commit was outside the shared list, which the prose
supported and the predicate refuses: their own gate would have contradicted them
at the worst moment. The fix is not a longer sentence. **Prose that restates a
list the code owns drifts from it**, so this paragraph names the file instead.
See *Documentation and code can disagree in two directions* below.

**"Reviewed at the head" is not the same as "reviewed".** CodeRabbit reviews a
push incrementally by default - it re-reads only the commits since its last
run - and its verdict is the same green check either way. The range line in its
comment says which kind you got:

    Reviewing files that changed from the base of the PR and between <from> and <to>

`<from>` equal to the pull request's base means the whole pull request was read
in that run. `<from>` equal to some later commit means only the tail was, and
everything before it was covered - if at all - by an earlier run. The
incremental runs are supposed to chain, each starting where the last ended, and
the chain usually holds.

On #170 it appears not to have held. A `/simplify` commit landed shortly after a
review, and the session working the pull request reported a following run whose
range *started* at that commit rather than before it - which would leave the
refactor at the heart of the pull request read by nothing. A full review,
requested by hand, then found a real defect in exactly that commit: a grid item
shrunk to fit and afterwards dropped for crossing the fold reported both that it
had been resized and that it was gone.

**That account could not be confirmed from the pull request afterwards, and
that is the point.** CodeRabbit keeps one rolling summary comment and **edits it
in place**, so every run overwrites the record of the one before. Hours later the
pull request showed two base-anchored full reviews and one incremental run, and
no trace of the run that would prove or disprove the gap. Whether the chain broke
that day is now unanswerable - which means the chain cannot be audited after the
fact by anyone, on any pull request. So do not try to audit it; remove the need
for it:

**Request a full review before merging.** It costs one of ten hourly reviews and
replaces an argument about chain-of-custody with a single line that either reads
from the base or does not. Check it mechanically:

```bash
pr=<n>
gh api graphql -f query='
{ repository(owner:"andashi", name:"home") { pullRequest(number:'"$pr"') {
    baseRefOid
    headRefOid
    reviews(last:100) { nodes { author{login} submittedAt lastEditedAt body } }
    comments(orderBy:{field:UPDATED_AT, direction:DESC}, first:30) {
      nodes { author{login} createdAt updatedAt body } } } } }' \
  --jq '.data.repository.pullRequest as $pr
        | [ ($pr.reviews.nodes[]  | select(.author.login=="coderabbitai")
             | {at:(.lastEditedAt // .submittedAt), body:.body}),
            ($pr.comments.nodes[] | select(.author.login=="coderabbitai")
             | {at:(.updatedAt    // .createdAt),   body:.body}) ]
        | map(select(.body | test("Reviewing files that changed")))
        | sort_by(.at) | last
        | if . == null then "NO REVIEW RANGE FOUND"
          else .body | capture("between (?<a>[0-9a-f]+) and (?<b>[0-9a-f]+)")
                     | "\(.a) \(.b)" end
        | "reviewed  \(.)\nbase head \($pr.baseRefOid) \($pr.headRefOid)"'
```

The two lines it prints must match. `NO REVIEW RANGE FOUND` means no CodeRabbit
record carries a range line - either it has not reviewed the pull request, or it
has and said so in a form this command cannot read, which is the same thing for
the purpose of merging. It is printed rather than left blank on purpose, because
an empty line here reads as "nothing to worry about" and means the opposite.

**One request, not three.** The base and head come out of the same call as the
review records, and that is not tidiness. Fetching them separately leaves a
window in which a push can land between reading the head and reading the
reviews, and the failure is the dangerous direction: the head reads as the
commit the review covered, the command prints a match, and the merge takes a
newer commit nothing has read. Caught in review on this very section, which had
three calls.

**The comments are ordered by `UPDATED_AT`, and that is load-bearing.** The
rolling summary comment is created early and edited on every later run, so
taking the most recently *created* comments drops it as soon as thirty comments
follow it, and the command then prints `NO REVIEW AT ALL` for a pull request
that has in fact been reviewed. The first version of this section did exactly
that, and review caught it: on #170 the rolling comment was created at 07:07 and
last edited at 07:44, which put it second by edit time and outside the three
newest by creation time. The failure is at least safe - a missing record blocks
a merge rather than waving one through - but a check that cries wolf is one
people learn to skip, and that is how it fails the other way in the end.

The reviews cannot be ordered that way; the API offers no `orderBy` there. A
plain `last:100` is enough for them because CodeRabbit submits a *new* review
per full run rather than editing an old one, so the newest is the newest by
submission time. Read **both** the reviews and the
rolling comment, and sort by the edit time rather than the creation time: a
re-requested full review *with findings* arrives as a review, an automatic
incremental one arrives as an edit to a comment created much earlier, and
sorting on `createdAt` hands you the older of the two while looking correct.
A re-requested review that finds nothing arrives only as that edit - see below.

**A full review that finds nothing creates no review object at all.** Its range
line then exists only in the rolling comment, so a check that reads the reviews
alone concludes the review never ran. The command above is already right about
this - it reads both - but do not reason about the reviews API on its own when
a pull request comes back clean.

**A rebase invalidates a review anchored before it, even when the ranges
chain.** This is judgement, not a command: two reviews can meet exactly, with no
commit unread, and still be worthless, because the same diff against a different
tree is a different change. On #175 the ranges abutted perfectly; a review
anchored at the current base then found **seven** real defects, every one of them
about a shared library that had been rewritten in the meantime. Glass's
conflict-free rebase the same day is the mechanism in miniature: `open_search`
resolved to a different function with different behaviour, no conflict, nothing
red. So after a rebase, request a full review and check the range starts at the
**current** base - a chained one does not count, however tidy it looks.

## Feedback loop (no LSP)

LSP is deliberately disabled for this project: Kotlin language servers on a
52-module Android/Gradle build give slow, sometimes wrong diagnostics (generated
code, Compose compiler plugin). The feedback loop is Gradle — scoped to the
module being touched, never a full build:

```bash
./gradlew :<module>:compileDebugKotlin        # type errors, fast
./gradlew :<module>:testDebugUnitTest         # L1 unit tests
./gradlew :<module>:connectedDebugAndroidTest # L2 instrumented tests (needs device)
```

Example module paths: `:core:preferences`, `:data:homegrid`, `:app:ui`.

## Diagnosing a crash

The launcher has no in-app crash reporter; it was removed with the module
diet (#20) because it duplicated what the platform already keeps. Caught
exceptions go to logcat under the `MM20` tag via
`CrashReporter.logException`. Uncaught ones land in the platform's DropBox,
which survives a reboot:

```bash
adb -s "$SERIAL" shell dumpsys dropbox --print | grep -A40 '<package>'
```

Verified on the GrapheneOS test instance 2026-09-20: a forced crash
(`am crash <pkg>`) went from zero DropBox entries to two carrying `Process:`
and `Package:`, and they were still there after a reboot.

## Test policy

This fork is developed AI-assisted, so tests are the safety net, not an
afterthought (see `docs/architecture/adr/0005-testing-strategy.md`):

- New fork code is written **test-first**. Pure logic (grid layout engine, config
  parsing/migration/convergence) lives in headless, unit-testable modules.
- Before touching existing upstream code, write characterization tests that pin
  its current behavior.
- Coverage is measured on fork-touched modules only; untouched upstream code
  staying untested is accepted. It is measured with Kover: a module the fork
  owns applies `libs.plugins.kover` and carries a `minBound` in its build file,
  CI runs `:<module>:koverVerifyDebug` and fails the PR below it, and the root
  build lists the module under `kover(project(...))` so `./gradlew
  koverHtmlReport` shows one merged report. The bound is set at the value the
  tests reach when the gate is introduced (rounded down) and is only ever
  raised. A new fork module ships with its gate in the same PR.
- Definition of done: unit + Compose tests green; for config/provisioning-facing
  features, the L4 scenario in `e2e/` (driven against the provisioning repo's
  emulator harness) updated and green.
- A test that reads a file outside its own source set **declares that file as an
  input of the test task**, or the guard silently stops guarding:

      tasks.withType<Test>().configureEach {
          inputs.file(rootProject.file("docs/architecture/adr/0002-config-format-json.md"))
              .withPropertyName("adr0002")
              .withPathSensitivity(PathSensitivity.RELATIVE)
      }

  Gradle cannot infer that a Kotlin test reads a Markdown file two directories
  up. Without the declaration a change to that file alone leaves the task
  `UP-TO-DATE`, the test does not run, and a wrong example passes. It happened:
  `ConfigParserTest` parses the example document out of ADR 0002, and the first
  version of that test was verified by breaking the example on purpose - it
  passed, because it had not run. CI would have hidden it, since every checkout
  there is fresh.
- Check a new test by breaking what it guards and watching it fail. A test that
  has never been red has not been tested either. Say in the PR which tests fall
  over without the change and which are deliberate controls that pass in both
  states - a fix that "passes" by disabling the feature is otherwise
  indistinguishable from one that works.
- In a shell test this is not optional, and breaking the code is only half
  of it: check that the break reaches the code you meant, and count the
  total, not the passes. A shell test can fail to exercise anything in more
  than one way, and each looks like a working test. Both happened to the
  tests for one fix in `e2e/lib/grid-device.sh` (#155):
  - `set -e` is ignored inside an `if` condition, even in a subshell, so a
    helper run as `if helper; then` cannot exit on an error: the test passed
    without the fix;
  - moved into its own `bash -c`, the helper died on a variable the library
    reads under `set -u` before any of it ran: red before the fix for the
    wrong reason, and red after it, which a count of the passes read as
    green.

### The root shapes

This heading carried a count of the shapes and of the defects they explain
until a fourth shape arrived two days later. A count in a heading goes stale
exactly as a count in a rule does; do not put one back.

Almost everything found on the night of 2026-09-26 was one of the first two
mistakes below. The shapes after them came out of later days.
Neither is carelessness: both read as tidiness while you write them, and both
live by preference in the code that checks things, because **a checker's correct
answer and its broken answer look identical from outside** - silence, a zero, an
empty string - and the broken one is quieter. Nothing complains, so nobody
re-reads it.

**1. A step failed and the sequence carried on.** `acquire` to `/dev/null`, then
the runner killed a locked instance. `run.sh stop … || true`, and an emulator
was stranded. A documentation patch failing on a stale anchor while the commit
went ahead. A body-replace assertion failing while the upload proceeded.

The sharper form, which finds them: **a failure that becomes the most permissive
answer.** Empty meaning universal. Empty meaning verified. A missing lock
meaning free. An unreadable snapshot list meaning nothing to clean. In a release
verification, a grep for a label the tool does not print read an empty digest -
which reported "differs" against one string and *matched* the next, because an
empty string matches anything.

**2. A value was read before an action and used after it.** Two reads of a
remaining deadline with a tick in between, handing `timeout` a zero, which it
takes as *no limit*. A merge command whose recount printed `threads: 1` beside
the merge it did not gate. A range check fetching base and head in separate
calls from the review records. A window list read, then a tap.

`--match-head-commit` exists because of this shape, and so does reading the base
and head in the same call as the reviews.

**Two consequences worth stating on their own:**

- **One deadline over several steps is not the same as each step being
  bounded.** A shared budget lets the slowest step consume the others', and what
  gets starved is whatever runs last - usually the check. A consolidation that
  reads as a simplification coupled a dump, a tap and the check after it; a slow
  dump then starved the check, on exactly the slow boots the mechanism existed
  for.
- **Do not decide membership by matching text when the data is a list.** Five
  boundary defects in two days: a pattern where a fixed string was meant,
  `holders=$PKG` accepting `org.andashi.home.debug`, an unanchored `pgrep`
  matching the shell that named it, `cold-1-m10` taken for `cold-1-m1`, and
  `grep -w` defeated by a hyphen - the last one chosen *because* it reads as
  careful. The worst of them decided a permission grant. Compare whole fields,
  or use a tool that understands lists.

  **A second instance shows "anchor your patterns" is the wrong fix.** A later
  `pkill -f 'watch-pr.sh 223'`, written as the first command of a compound line
  that went on to rebase a branch, matched **its own shell's command line** and
  killed the whole invocation - so the rebase never ran, and the non-zero exit
  read as "the pkill failed" rather than "everything after the semicolon was
  cancelled". The pattern was specific enough to look anchored.

  **The status is 144 in the Bash tool and 143 in bash, and both are this same
  kill.** A session sees `Exit code 144`; a plain `bash -c` reports 143, which is
  128 + `SIGTERM`, the signal `pkill` sends by default (`pkill -9` gives 137).
  Measured three ways on this machine: through the tool by two sessions
  independently, and against a plain shell as a control. Where the tool's figure
  diverges is untraced and deliberately not guessed at. **144 is the number to
  recognise**, because it is the one in front of you when it happens - the entry
  first said 144 with no layer named, then 143 after review, and both were half
  the answer.
  **Kill by recorded PID, not by pattern.** procps-ng does exclude the `pkill`
  process itself, so the trap is narrower than "always": it fires when the
  **shell's** command line contains the pattern, which a compound `bash -c …`
  does and other invocation forms need not. That is exactly the case above, and it
  is not one you can rule out by looking at the pattern - which is why the rule is
  the recorded PID rather than a better pattern.

**Durable state whose absence carries meaning is a permissive-default factory.**
One new field - a record of how each app entry spelled its activity - produced
**seven** defects in review, every one the same shape: the empty marker written
after a *failed* reload (failure recorded as success); a **corrupt** file counted
as a record (unreadable meaning present); the record written before the device
writes and not restored (a write that did not happen recorded as done); write-back
running before the migration (no record meaning any form will do); a rollback
writing an empty map as the "before" state (**the repair manufacturing the
permissive answer**); a cancelled apply skipping the rollback entirely; and
write-back held back and never asked again. Every path that writes such a field
and every path that reads it must decide what absence means **explicitly**. And
**a rollback that can itself fail is another instance** - a compensating action
must not swallow its own error, or it reaches the state it existed to prevent.

**Persisted data outlives the code that wrote it.** Any table, enum or severity
that is serialised needs an answer to "what if this was written by a version
that disagreed with me". Two instances, and the second is the expensive one:

- On #215 a reload report written by an **older build** kept its old severities
  when a new build read it: the severity table had changed underneath persisted
  data. Fixed by treating a report that disagrees with the table as none, and
  reloading once on the startup check.
- **Removing a value from a persisted enum can wipe every setting, and a
  different option guards it than the one people reach for.**
  `ignoreUnknownKeys` covers a removed *field*. It does nothing for a removed
  *value* in a field that still exists: the store then fails the whole document
  with `CorruptionException: Cannot read json`, so a device that had the old
  value stored loses **every** setting on upgrade, silently.
  For `LauncherSettingsDataSerializer` the corruption handler then replaces
  every setting with defaults.

  **Two guards cover two shapes of this, neither covers the other, and both are
  bound to named fields rather than to the language.** `coerceInputValues = true`
  reads an unknown enum value as a missing property and falls back - **but only
  where the property has a default**, so a required enum property without one
  still fails the document. And it does nothing at all for an unknown value
  **inside a list**: that needs a tolerant serializer bound to that list, and in
  this repository exactly one list has one - `TolerantEnumListSerializer` on
  `List<KeyboardFilterBarItem>` in the settings store, which drops entries this
  build no longer knows (ADR 0008: removing a value is a normal consequence of
  removing a feature, so it must not be able to wipe unrelated settings).

  **The configuration is a second store with a different failure, so check both
  before removing a value.** `search.filterBarItems` in `launcher.json` binds
  `FilterBarItemSerializer`, which is strict: drop a `SearchFilterItem` value and
  `ConfigParser` returns `DecodeFailed` with a null config, and `ConfigReloader`
  rejects the update. Nobody's settings are wiped there - instead the provisioned
  configuration stops applying, which on a managed device is its own kind of bad.
  **Trace the serializer of the specific list, in both stores**, rather than
  inferring one from a neighbouring list that happens to be tolerant.

  Found on #238 while removing `SearchBarStyle.Solid`, because the decode was
  verified rather than assumed - and the break, dropping that one option,
  reddens with the corruption, which is what shows a single line is holding the
  floor up. `RemovedSearchBarStyleTest` pins **that one field**. Every future
  enum shrink needs its own answer - a default, a tolerant serializer or a
  migration - plus a decode test that fails without it.

**Do not fix a semantic trap with a rule; change the shape so the wrong use is
impossible.** Both of us reach for the ordered-looking tool exactly when being
careful: `grep -w` reads as "whole word" and is not, and a counter reads as
ordered and is not across a store reset. Proposed for the reload report, a rule
would have said "compare with `!=`, never `<`" - about a field whose whole shape
invites the comparison it forbids. The provisioning session's answer was to emit a
**store identity** beside the counter, changing on the event that zeroes it: within
one identity `<` is sound, across two a consumer sees the identity change instead
of reading 0 as "it went backwards". Two fields, and the rule becomes a check
somebody can write a test for.

**A check that is meant to stay silent needs its own tests more than a noisy one
does.** Its correct output and its broken output are the same silence, and - unlike
an ordinary checker - nobody will trip it by accident and discover it is dead. The
provisioning repository's tripwire for the widget-diagnostic defect, designed to
fire the first time any zone declares a widget other than the built-in const and
to stay quiet for months otherwise, ships with four cases of its own for exactly
that reason. Four cases for a tripwire is not over-engineering; it is the only way
to know it still exists.

And phrase such a check as **the question to answer** rather than as an
instruction - *has andashi/home#219 shipped in the release we install?* An
instruction goes stale when the situation changes; a question stays answerable, and
it tells whoever trips it in six weeks what the check was for.

**A fix can produce the next defect.** Two did that night: patching a guard's
fourth hole opened its fifth, and consolidating four deadlines into one starved
the last step. After fixing something in a checker, break it again.

**A third shape, found the day after: the answer was computed and then thrown
away.** Not a wrong comparison - a discarded one, and it hides in the same place
for the same reason.

A shell `for` loop exits with its **last** iteration's status. So

    for f in config/*.json; do jq -e . "$f" >/dev/null && echo "ok: $f"; done
    for f in $(find . -name '*.sh'); do bash -n "$f"; done

validate every file and report only the last one. An invalid config or a syntax
error anywhere but at the end passes green on every push. Found in the
provisioning repository's only workflow on 2026-09-27 and demonstrated with three
files whose middle member was broken: exit 0 before the fix, exit 1 after. Its
`make check` had it right locally, so **the gate that ran automatically was the
weaker of the two**, which is the wrong way round. Accumulate into a variable and
exit on it after the loop, the way `.github/workflows/commit-hygiene.yml` does.

Two things that shape shares with the `[bot]` suffix above. Both produce a
**quiet success**: one counted zero and called it clean, the other computed every
answer and kept the last. And both sat in a checker that had never been watched
to fail - that workflow was also unparseable YAML for a while and did not run at
all, so it was silently useless twice, in two different ways, while the
repository looked green throughout. **A gate nobody has ever watched fail is
indistinguishable from no gate.**

The same scan found nothing of the kind here: `commit-hygiene.yml` accumulates
`bad=1` and exits after its loop, `check-helpers.test.sh` accumulates `failed=1`
and exits on it, and `delete_snapshots` ignores its deletes on purpose because
the list afterwards is the check. Four candidates, four false alarms - which is
what a heuristic for this looks like when it comes back clean, and worth writing
down so the next person does not re-run it hopefully.

**A fourth shape, and it is the first one inverted: a failed step stopped the
sequence - permanently and silently.** On #213 a bind pass ran *inside* the
package-event collection, so one host exception ended the collection and **every
later arrival went unhandled**, until the host id changed. Nothing logged a
stop; arrivals simply never fired again.

Shapes 1 and 4 are the same error about a failure's **scope**: shape 1 lets a
failure affect too little - the step failed, the sequence carried on - and shape
4 lets it affect too much - the step failed, the sequence died. **Ask what a
failure ends, and whether that is what you meant.** The fix was to move the work
out of the collector into a function that throws nothing but cancellation.

**A check establishes its own preconditions, or its answer is about something
else.** Four of one session's own tools were wrong this way in a single day
(2026-09-28), and the point of grouping them is that they have one remedy rather
than four:

- `node_bounds` exited 0 with empty output, so the check reported a contact as
  **shown** on a screen that did not contain it.
- A device proof reported *not shown* on **both** builds, because search had
  closed across a system permission dialog and the check was reading the home
  screen.
- `grep -m1` under `pipefail` took SIGPIPE on a longer dump - the early-closing
  reader again, which "Three ways a checker's own plumbing lies" covers below.
- A rebase conflict inside an `&&` chain, where `set -e` does **not** stop, let
  the tests and a push run against a half-rebased tree.

Each asserted something without first establishing it was in a position to
assert it: is the thing on screen, did the command run to completion, is this
the tree I think it is. **The remedies are the same shape every time** - assert
search is open before looking for a result in it, check the rebase state before
building, require the lookup to have produced output before reading it.

Two corollaries worth stating on their own:

- **A check looking at the wrong thing gives the same answer for every build**,
  and "no difference between the builds" is the most reassuring possible way to
  be told that nothing works.
- **Knowing the trap is not protection.** `set -e` inside a conditional, and
  `grep` piped into an early-closing reader, are both already written down in
  this document, and both were met again the same day by someone who had read
  them. Only changing the shape helps.

**A chain that agrees with itself says nothing about which way is true.** A
round trip - differ, apply, read back, map - passes under a *consistent*
inversion, because every stage agrees with every other stage either way. Four
instances on 2026-09-28 alone:

- `colorSource` (#233): reading the stored boolean inverted passed every test.
  Nothing tied `uiCompatModeColors=false` to the system branch of
  `ColorScheme.systemCorePalette` until a settings test pinned it.
- search filters (#235): a bridge swapping `hidden` and `contacts` passed the
  settings-contract round trip.
- profile mapping (#235): the write-back complete-example test cannot catch a
  consistently wrong profile mapping **by design** - it compares the read-back
  with itself.
- `dimWallpaper` (#240): the same shape once more.

The cure is never a better round trip: **pin the mapping against the
CONSUMER** - the composable that draws, the palette that is read, the switch the
user sees - and pin both directions. A round trip proves the stages agree; only
the consumer says they agree with reality.

**The commonest error in this project is a claim about what the code does, made
without reading the line that does it.** It is not one session's habit: it
recurred in every session on 2026-09-28, each time with the same tell, a
plausible chain of reasoning from a true premise to an unchecked conclusion.

- `iconsShape` was traced to the settings provider and the shape helper, and
  reported as cutting every app icon. It cuts none: `ShapedLauncherIcon` returns
  early into `ClearLauncherIcon` for the whole launcher scaffold.
- `searchBarColors` was called the system bars' icon colour. It is the search
  bar's own content colour.
- `transparent` was predicted to be the illegible search-bar value. The
  measurement said `solid`, at 1.16:1 in both directions.
- A provisioning claim ran "the chain installs apps, installs happen every run,
  so a reinstall is the normal path" without checking the verb: it is
  `install -r` throughout, and `provision/` contains no `pm uninstall` at all.
- Grey values quoted from memory (116/101) computed to 123/93 - caught before
  the run, which is the version of this mistake that costs nothing.

**A grep that finds a provider and a helper proves the chain exists, not that
the call site reaches it.** Before asserting how code behaves, read the line
that does it, or run it; and when the assertion is about what a person sees,
trace it to the composable that *draws*, not to the provider.

**Documentation and code can disagree in two directions, and only one of them
looks like a defect.**

*The documentation promises more than the code does.* `gestures.md` promised the
file keeps a gesture "for the day it is installed", and
`gesture-app-unavailable` was in no waiting set, so it never applied.
`docs/configuration` promised `shapes-custom` and `typography-custom` warnings
that nothing produced. Neither was a stale page describing removed behaviour:
both described behaviour that had never existed. Both were found by a test
written **before** the implementation, which is what turns a promise into a red
test instead of a surprise. So **read the documentation beside what you are
building as a specification**, and write the test from it first.

*The documentation promises less than the code enforces.* The rebase paragraph
in "Merging a pull request" named fewer shared paths than `base-behind.sh`
counts. This direction fails toward refusing, which is exactly why nobody
notices it, and it still cost a wrong premise in a live decision.

**What separates both from the rest of this document is who found them: a claim
a machine can check gets checked, and a claim in prose does not.** On #236 the
ADR still said a check covered release APKs after that wiring had been lifted
out of the pull request - and the gate found it, not a reader. The same day two
correct records sat present and unread while the question they answer was being
argued from first principles: `docs/configuration/icons.md` ("there is no
`icons.shape` key … home, the dock and search always draw the squircle") and a
comment in `ShapedLauncherIcon.kt` saying the same. **The entry on persisted
enums above is a third instance, and it is this document's own:** it was first
written weaker than the comment in `TolerantEnumListSerializer` that already
stated both limits correctly, and review caught it. The lesson is not "read the
docs"; it is that prose does not defend itself, so put the load-bearing claims
where a test can reach them.

**A field whose wrong value is INVISIBLE gets no default.** A default is fine
where omitting it fails loudly: `ScaffoldConfiguration.searchBarStyle = Hidden`
omitted means no search bar, and you see that at once. `darkSearchBar = false`
omitted means the right bar with **unreadable text on some wallpapers**, which
nobody notices without a screenshot on the right background.

That default is how #238's own fix reproduced the defect it was removing. The
assistant's scaffold was one of two construction sites and never passed the
field, so mapping `Solid` to `Transparent` gave it white text on light glass -
1.49 and 1.15, the same numbers the pull request existed to remove. Removing the
default makes the compiler name every site that omits it, today's two and any
later one: `No value passed for parameter 'darkSearchBar'`. **A compile-time
break is the strongest form of the break check**, because the failure cannot be
skipped, cached or mis-reported. It is the shape-change move again, and its root
is the familiar one: two places assemble the same configuration, and a change
reaches only one of them.

**But "invariant by construction" is only as good as the set of constructors you
closed, and a `data class` has a second door: `copy()`.** On #215 an ERROR-level
deprecation on the primary constructor was the whole mechanism making a
disagreeing severity uncompilable - and Kotlin's generated `copy()` does not
inherit that deprecation, so any caller could build one anyway. Fixed by making
it a plain serializable class, which **removes** the second door rather than
guarding it. Before relying on a closed constructor, list every way an instance
can come into being - `copy()`, a builder, deserialization, a factory,
reflection - and say which are closed and why the rest are safe.

### Ways a test runs and tests nothing

The mechanisms below were all met in this repository within one week. None is
carelessness; every one of them looks correct while you are writing it. That is
why a green run is not evidence and the deliberate break is, and it is why the
test policy above asks for the break rather than the pass.

1. **The task did not run.** A test reading a file outside its source set
   without declaring it as an input leaves the task `UP-TO-DATE`, so a changed
   file runs nothing (`ConfigParserTest` and ADR 0002).
2. **`set -e` inside an `if`.** Ignored there, even in a subshell, so a helper
   run as `if helper; then` cannot exit on an error (#155).
3. **The suite executed no case.** A shell test that sources the script under
   test inherits its `exit`, ending the file before a single check runs. It
   passed, and the exit code said so (#168).
4. **A name changed meaning under a rebase.** `open_search` moved into the
   shared library keeping its name and losing what it did. No conflict, nothing
   red, and the step waited for an answer to a query nobody had made (#164).
5. **The assertion target went inert.** An isolation check overrode
   `appearance.transparency.background`, accepted but not served since #24, so
   it compared a value neither profile ever had (#176).
6. **A blind control.** The control tapped through an empty query, which lists
   every app, so the item it looked for was on screen whatever the code did -
   and the unfixed build passed (#187).
7. **The assertion ran off the test thread.** A concurrent test asserting on a
   worker thread never sees the failure, so a wrong result stays green (#190).
8. **The fake could not produce the answer the code must reject.** A fake that
   only ever returns a right-shaped answer proves nothing about the rejection,
   and it is always the convenient one to write. Three in one day, each found by
   somebody other than its author: a fake that closed a tapped dialog faster
   than a device does, so a break stayed green until the fake was made as slow
   as the device; a one-line `dumpsys gfxinfo` fake that could not fill a pipe
   buffer, which left `pipefail` plus an early-exiting reader unreachable in the
   tests while it broke `frames_rendered` on the device (#210); and a fake
   provider that always returned a report whose hash differed from the previous
   one, so an implementation regressing to a hash-only predicate passed the new
   wiring test (#211). **A fake must match reality in whatever dimension the
   code is sensitive to** - speed, size, ordering - and must be able to lie in
   the specific way the code exists to catch.
9. **The fixture was a state the system cannot be in.** The fixture-side of 8,
   and it produces a red test that proves the wrong thing. Chasing the
   `apps` write-back defect on #207, a reproduction set the *applied* baseline to
   an entry carrying an explicit `activity` - but the applied state is the
   store's read-back, and the store never emits one there. The case went red, it
   was targeted, the other 28 passed: every signal a good reproduction gives. It
   demonstrated a silent loss of the activity, while what actually happens with a
   reachable baseline is a **duplicate entry carrying a stale label** - which was
   what the review had said, and what the reproduction was taken as disproving.
   Both faults are real; only one occurs. **A red test is evidence only if its
   fixture is reachable**, so derive the baseline from what the producing code
   emits, never from what makes the case read well.

10. **The check repaired what it was about to observe.** To prove a record
    survives a restart, the obvious test force-stops the app and reads it back -
    and cannot work here, because the startup check **re-records a missing
    record**. Read it where it is kept, before the restart - and then read the
    *effect* after it, because the pre-restart read alone proves only that the
    record existed beforehand. Either get the post-restart read in before the
    repair path can run, or isolate that path. The sibling of 9: there the fixture
    is a state the system cannot reach, here one it leaves before you look (#214).
11. **The break generator could not express the break.** A generator that mutates
    values can never remove a key, so a schema rule about a key's *absence*
    (`dependentRequired`) was unreachable by a fully green generic suite. Ask what
    class of change your generator cannot make (#216).
12. **The control asserted the opposite of the truth.** Not blind like 6 - it
    could fail and did not, because it encoded a wrong belief and so **certified**
    the defect. The next reader takes a green control as the question having been
    asked. When a fix reverses a control, say so and name the assertion that
    changed direction (#213).
13. **The break ran, but a different break was tested.** In its default
    timestamp mode, Python validates a cached `.pyc` against the source's
    **mtime and size only** - PEP 552's hash-based modes check the source's
    hash instead, and are not what you get unless someone asked for them. Two
    consecutive
    breaks in a harness each shortened the same line by the same 12 characters
    within one second - same size, same second - so the stale bytecode was
    reused and the harness reported the *previous* break's result. Caught only
    because the red test did not fit the break that was supposedly running
    (#239). It is the sibling of 1 and fails the other way: 1 **skips** the
    work, this one **runs the old work** and reports it as new. Both are the
    build system's staleness detection being wrong, and both are invisible from
    the result. Remedy: `PYTHONDONTWRITEBYTECODE=1`, and clear `__pycache__` in
    every break script. And the discipline that matters more than the remedy -
    **a defect in the measuring instrument invalidates every earlier
    measurement until it is re-run.** Every prior break of #236 and #239 was
    re-run without the cache; all agreed except the one. Without that the fix
    would have been forward-looking only, and the earlier green results would
    have stayed unexamined.
The check costs about a minute and is three questions. The first is the one
everybody means by "break it", and on its own it settles nothing:

**1. Does the break go red in the right place?** Break what the test guards, and
watch the test or tests that cover it go red while the **controls** - the ones
that deliberately pass in both states - stay green. Not "one test reddens":
several tests legitimately covering the same behaviour all should, and narrowing
a break until exactly one goes red is how a real guard gets mistaken for noise.

**2. Is the break in the input the guard is about?** For a test that reads a
document outside its source set, change the **document**, not the code. A break
in a declared input re-runs the task and says nothing about whether a change to
the undeclared one would have run it, so mechanism 1 is invisible to a code break
by construction. `ConfigParserTest` was verified by breaking ADR 0002's example
and **passed, because it had not run**; breaking the parser instead would have
gone red and proved the wrong thing. A green break and a red break, both
misleading, from the same missing declaration.

**3. Is the fixture a state the system can be in?** Mechanism 9 satisfies
question 1 completely and still proves the wrong thing, so no amount of the first
question reaches it. Answer this one from what the producing code emits, never
from what makes the case read well.

Two on the list are outside all three, and knowing which is the point of saying
so: **11**, because you cannot break what your generator cannot express, and
**12**, because a control asserting the wrong thing passes in both states, which
is what a declared control is supposed to do. Those two are caught by rereading
what the test *claims*, not by breaking anything.

**The list's mirror image costs differently: a fixture can make a CORRECT fix
look wrong.** Every mechanism above makes a broken thing look fine. On the icons
write-back fix the core test omitted `canonical`, which defaults to the literal
file text - so `#ffffff` never equalled `#FFFFFF`, and a **working** fix
appeared to fail. The failure mode is throwing away a good fix rather than
shipping a bad one, and it is harder to spot because a red test feels like
evidence. Same remedy as 9: **build the fixture the way production builds it**.
The tests now derive the canonical tree exactly as `ConfigWriteBack` does -
parse, then encode through the model - instead of hand-writing one.

This passage was wrong about itself repeatedly while being written, in every way
it describes, and two rules came out of that rather than out of the code.

**A sentence that claims to cover a list makes a claim about every item on it.**
It began as one claiming a single break caught every mechanism, and it was false
about two of them. A list that grows underneath such a sentence invalidates it
silently - the same failure as the counts removed elsewhere in this document,
except that a stale count looks stale and a summarising claim looks careful. It is
a numbered list of questions now because a claim about a set cannot drift out of
step with the set if it *is* the set.

**A fix checked against its finding is not checked against the document.** Every
correction here was reviewed against the one report that prompted it, and the
passage still ended up announcing three questions and calling the third a "second
half", and quoting an earlier question's superseded wording. Nothing catches that
but reading the whole thing once, afterwards - which is also the only thing that
caught it.

**A break that does not go red is a finding, not a result.** It means one of two
things - the test is decoration, or the break was incomplete - and they look
identical. The comfortable reading is available in both directions, so say which
it is before moving on. A deadline guarded in three places stayed green when one
guard was removed; the test was sound and the break was partial, and that was
only known because somebody asked which.

**And for an intermittent defect, a search that fails to reproduce it is not an
exclusion unless its runs per condition are stated.** A single run of a causal
condition misses the defect at exactly the rate it does not fire, and nothing
about the other conditions changes that: at the 0.69 measured for #251's blank
capture in the full package, once is a 31 % miss. The trap is the next step.
**The rate is a property of the condition, not of the defect** - measured later,
the same cause fires at about 0.2 in a two-class condition, where a single run
misses it four times in five. So a sweep whose conditions are all small is weak
even where its conditions are the right ones, and several of them can come back
clean together.

That happened. #248's body recorded the blank as "never in the class alone or in
any pair or half of the glass tests", and every pair and every half behind that
sentence was a **single run** of a small condition - several of which contained
the class #251's bisect later found necessary. #251's bisect has since put one of
those same halves at 2 of 5. The wrong reading did not stay in a session: it
reached a merged pull request body and formed part of the reasoning for
**dropping a golden**, so a coverage trade was priced on a search whose power was
never stated.

**Put the runs per condition and the observed rate beside any negative result,
and size the runs against the rate of the condition you are actually running,
not the rate you measured somewhere else.** Five runs at 0.69 puts a false clean
under 0.3 %; the same five runs at 0.2 leave it at 33 %.

**Those figures assume the runs are independent, which is a practice rather than
a footnote.** Five runs in one block share whatever the host was doing, so a load
trend inside a block lands entirely on one condition and the effective number of
runs is smaller than the count - the same bias that a fixed build order put into
every unfold series before #175. Interleave the conditions run by run, record the
load beside each, and read the rate as a property of the condition you ran. A
round of #251's bisect was started as five runs of one split followed by five of
the other and was restarted interleaved for exactly this reason, with none of the
first round's results kept.
Say in the pull request which tests fall over without the change and which are
deliberate controls that pass in both states.

**Two rules for the shared shell library in `e2e/lib/`, both learned the hard
way in one day.**

**A function that moves into the library keeps its name only if it keeps its
behaviour.** Different behaviour means a different name, so old call sites fail
loudly instead of changing silently. `open_search` moved into the library under
its own name while losing what it did - the script's version typed a letter and
closed the keyboard, the library's only opened search - and a *conflict-free*
rebase silently pointed an existing call at the new meaning. The step then waited
for a banner answering a query nobody had made. Git cannot see this: two valid
files, one name, different meaning. Renaming to `open_search_field` made the old
name exist nowhere, so any stale call site goes red; a library test asserts
`! declare -F open_search`.

**Three ways a checker's own plumbing lies.** All three were found in checkers
rather than in product code, which is where they prefer to live.

**They share a site, and naming it is more useful than naming the causes: an
assignment from a command substitution is where a `set -euo pipefail` script dies
without a message.** `x=$(cmd | ...)` fails, `set -e` exits, and the script never
reaches anything that would print why - so the symptom is a bare non-zero status
and no output. **Outside the `errexit` exemptions**, that is: an assignment used
as an `if` or `while` test does not exit, which is mechanism 2 of the list above
wearing different clothes.

Four instances in one day, all assignments: a merge gate's `shared=$(… | grep …)`,
a fixture's `bt="$(ls -d "$sdk"/build-tools/*/ …)"` on an SDK with no build tools,
the same gate's base lookup - and, in the provisioning repository, a tripwire
written to catch invisible states whose own `launcher_version` assignment killed it
mid-message when no APK was present. **The check against invisible states was
exiting invisibly**, on the same night, in the thing written to catch it.

**When a script exits non-zero and silent, look at its assignments first**; and in
a checker, guard the lookups so the failure gets to speak. **`|| true` is not that
guard, and checking the result for emptiness afterwards does not make it one**: a
lookup that failed and a lookup that found nothing both produce an empty string, so
the check cannot tell them apart and reads the failure as a confident "not found" -
empty meaning verified, the shape named above. Accept the one status that means
absence and let every other one abort, the way the next entry and the release
command both do.

The first draft of that sentence called `|| true` with an emptiness check "the
honest use of that idiom", **nine lines above the entry that gives the correct
one**. Review caught it. The contradiction was a screen apart rather than a file
apart - close enough to read in one sitting, and still written and re-read several
times without anybody noticing, because the permissive version is the one that
sounds careful.

- **`grep` exiting 1 on no match kills a `set -euo pipefail` script**, and
  `pipefail` is the load-bearing half: with `set -e` alone the pipeline reports
  the status of its **last** command, so `x=$(cmd | grep -E pat | head -5)`
  succeeds on no match and hides real `grep` errors too. With `pipefail` it dies
  instead - silently, exit 1, no message - so the branch that needed "no match"
  never runs. In the merge gate that was the base-behind *allowance*, which had
  therefore never once executed, behind a comment about being careful. Write
  `{ grep -E pat || [ $? -eq 1 ]; }` so status 1 is accepted and a real error
  (status 2) still aborts.
  - **And do not pipe it into `head`.** An early-closing reader kills its
    producer with SIGPIPE once the producer's output exceeds a pipe buffer, and
    141 is not 1, so the guard above rejects it and the script dies silently
    again. Measured: 200k lines dies, 7 lines does not, and it dies whatever
    feeds `head` - **the fault is the reader, not the producer**, which is why
    the first attempt at this fix only moved it from `grep` to `printf`. Use a
    reader that drains (`sed -n '1,5p'`), or capture first and truncate without a
    pipe. Same trap as the `gfxinfo` fake in mechanism 8.
- **`$(...)` strips trailing newlines**, so a file that ends in one does not
  round-trip: read back and re-hashed, it never matches the file on disk. A file
  with no terminal newline is unaffected, which is what makes this intermittent
  and therefore worse. Hash on the device, or do not round-trip.
- **`git reset --soft <new base>` then committing reverts files the new base
  changed.** The soft reset keeps the *branch's* tree and commits it against the
  new base, so every differing file joins the diff - including ones the branch
  never meant to touch, reverted to their state at the old base. No merge, so
  **nothing conflicts and nothing goes red**; on #219 it reverted `AGENTS.md` and
  undid two merged pull requests. After squashing onto a new base, diff the
  **file list** against what the pull request is supposed to touch.

**A helper's log goes to stderr when its callers might capture stdout.** Found
twice within ten minutes: `grant_home_role` logging through `log` corrupted
`measure-footprint`'s TSV, which promises data on stdout, and `retry_for`'s
elapsed-time line would have corrupted the JSON that `query_json_as_user`'s
callers parse. Both now write to stderr, and the test for the second asserts
that stdout is exactly the predicate's output.

## Test harness (Phase 1)

- **L1 unit tests**: JUnit4 + Robolectric 4.17 (SDK 36/37 supported; needs the
  `--add-opens` JVM args already wired in the module build files — copy that
  `tasks.withType<Test>` block when adding tests to another module). Modules
  with test wiring so far: `:core:base`, `:core:config`, `:core:preferences`,
  `:services:config`, `:data:database`, `:data:searchable`,
  `:data:themes`, `:data:homegrid`, `:data:customattrs`, `:app:ui`.
- **L3 screenshot tests**: Roborazzi in `:app:ui`; goldens are committed under
  `app/ui/src/test/roborazzi/`.
  - record: `./gradlew :app:ui:recordRoborazziDebug`
  - verify: `./gradlew :app:ui:verifyRoborazziDebug`
- **L2 Compose UI tests**: `androidTest` in `:app:ui`, run on a stock API 36
  emulator (`:app:ui:connectedDebugAndroidTest`).
- **Room migrations**: `:data:database` exports schemas via KSP
  (`schemas/…/<version>.json`); `MigrationTest` validates the full chain and is
  the template for new migrations. When bumping the DB version: write the
  migration, extend `MigrationTest.allMigrations`, run
  `:data:database:kspDebugKotlin` once to export the new schema JSON, commit it.
- **L4**: `e2e/l4-smoke.sh` (and future scenarios) — see the emulator section
  below.
- **Footprint**: `e2e/measure-footprint.sh` — APK size, dex method references,
  declared permissions and Gradle module count on the host (`--static`,
  seconds), plus cold start, PSS/RSS and CPU on the test instance, unplugged
  and charging (a boot cycle per run; `--runs N` repeats the cycle and adds a
  `<metric>.spread` line, which is what makes a small runtime delta quotable). Results are committed under `e2e/measurements/`; `--compare
  <before>.tsv <after>.tsv` prints the deltas and names the permissions that
  appeared or disappeared. Every module-diet PR (#20) carries a before/after
  from it.
- **Cold start and unfold, build against build**: `e2e/measure-coldstart.sh`
  (`am start -W` TotalTime) and `e2e/measure-unfold.sh` (the first frame
  after an unfold). Each prepares one snapshot per APK and alternates the
  builds round by round, **alternating their order** within a round: with a
  fixed order, a load trend within a round always lands on the same build.
  `measure-unfold.sh` ran a fixed order before #175, so every unfold series
  committed before it, #158's included, carries that position bias.
  - Compare **pairwise** (same round, same start), never by unpaired
    medians. On #167, a fixed-order series at host load 11-15 had the
    unpaired medians put the build with extra start-up work 140 ms *faster*
    (626 against 766 ms), opposite to the hypothesis - the spread dominated.
  - A median whose paired spread dwarfs it is not quoted, not even as
    colour: "351 ms before, 341 after" in the v0.8.0 notes sat on a paired
    spread of -55 to +401 ms.
  - Measure on a quiet host, or say that the host was not quiet: at load
    11-18 these series cannot resolve tens of milliseconds, and a null result
    from them is not evidence that a cost was looked for and not found. The
    same holds one level up, for pass/fail: on 2026-09-26 three L4 scenarios
    failed at host load 47 for three unrelated reasons, two of them looking
    exactly like launcher regressions, and came back green on a quiet host.
    A result without its host load is not evidence.
  - **A series that resolves tens of milliseconds needs the machine to
    itself**: a pilot of about 15 minutes to estimate the spread and, if
    that allows it, up to about 90 minutes more, with no other session
    building or running an emulator. #175's pilot, with a ceiling fixed
    beforehand at the idle floor + 2 (2.93 + 2), ended in its second round
    at load 9.67 when another session's build started; the harness's own
    working load was 4.2-4.8, within a point of the ceiling. Quiet windows
    do exist (load 2.1-2.8 for a function check the same day). For #167 we
    **chose not to spend one**: its start-up burst was gone (five write-back
    passes per cold start to one, #171), and no decision turned on a
    first-frame figure. That is a judgement about the cost, not a claim that
    the measurement is impossible.

## CI

`.github/workflows/test.yml`: L1 + L3 (JDK 21 — Robolectric with SDK 36+
requires >= 21) and L2 via `android-emulator-runner` (stock API 36 image, plus
a foldable one), on every PR, every push to `main` and every release. A push to
another branch runs nothing until it has a PR: a bare `push:` ran every PR
commit twice and doubled the flakes. L4 stays manual/local.

**Red on `main` is fixed before the next merge.** A PR is green on its own
head; the run on `main` is the first to see it combined with whatever merged
next to it, and nothing blocks on that run - GitHub tells the merger at most.
A control test from #123 went red on `main` and stayed red through three more
merges until someone read a log (#132).

## Fork conventions

- Hard fork (ADR 0007, revised 2026-09-19): no merges from upstream, cherry-picks
  by hand where worth it. Upstream files may be edited, modules removed, packages
  renamed; structure is chosen for the fork's maintainability, not for merge
  friendliness. `upstream` remote stays for reading (`upstream-main`).
- Support matrix: current GrapheneOS on Pixel devices (candybar + Fold, cover and
  inner display). minSdk 36. Old-Android compat code is deleted, not maintained.
- No Play Services dependencies, no telemetry.

## Security

This fork is a **launcher whose primary target is GrapheneOS**, so it is held to
high security standards. A launcher runs with elevated trust on the device
(home-screen role, app launching, widget hosting, potentially provisioning
data), and GrapheneOS users expect software that does not erode the platform's
guarantees. Treat security as a design constraint, not a checklist item:

- **Least privilege**: request no permissions beyond what a feature strictly
  needs; never weaken existing sandboxing, signature checks, or SELinux-related
  behavior to make something work.
- **Data protection**: user data and provisioning/config data must never leak
  — no plain-text secrets, no sensitive data in logs, no world-readable files,
  no unprotected exported components. Treat config/provisioning payloads as
  untrusted input: validate and sanitize everything that is parsed.
- **Attack surface**: keep exported activities/services/providers/receivers
  minimal and explicitly permission-guarded; be conservative with IPC, deep
  links, `WebView` usage, and dynamic code loading.
- **Dependencies**: no new third-party dependencies without clear justification;
  no closed-source blobs, no trackers, no Play Services, no telemetry (see fork
  conventions).
- **When in doubt, ask**: if a change could weaken the security posture —
  even indirectly — flag it explicitly instead of merging it silently.

## Releases

**Run the L4 scenarios from `clean` before tagging, and say so in the
annotation.** CI covers L1, L2 and L3 on every pull request and on the release
itself, but L4 is manual and local by design, so nothing runs it unless a person
does. Two contract drifts sat undetected for weeks because of that. One scenario fed
the launcher `home.dock`, a key removed at schema 2; the same scenario's
isolation check overrode `appearance.transparency.background`, inert since #24.
Both failed loudly the moment somebody ran that scenario from `clean`, and
nobody had since #24. Finding them cost an afternoon and a wrong escalation into the
provisioning repository. One person, one hour per release, catches that class
before it ships rather than weeks after.

A third drift showed up on v0.11.0's own gate: `l4-config` asserts the served
`search` object by **exact equality**, and the three keys added in #235 were
never added to the scenario's expectation, so the release run went red on a
correct launcher. That is the gate earning its hour - twenty minutes before the
tag instead of a week after it - but the shape is worth naming. **A scenario
that pins a section by exact equality is a second definition of that section**,
and it drifts every time the first one grows. Keep the expectation in a file
both sides read, pin it against the model with a unit test, and declare that
file as an input of the test task.

A release is made by pushing an annotated tag. `.github/workflows/release.yml`
publishes the annotation verbatim as the release body, so the annotation is
where the release is described - `git tag -a`, never a lightweight tag (the
workflow rejects those).

**A tag ships only a commit whose full suite is green, L2 included.** PRs merged
close together meet for the first time on `main`: v0.7.2 was tagged from a
commit that merged three PRs within ten seconds, each green on its own head, and
no L2 suite had seen them together (#132). The run on `main` reports that
combination but blocks nothing, and a tag can be cut before it finishes, so
`release.yml` runs `test.yml` with both emulator suites itself and builds
nothing unless all of it is green. That is the
enforcement, not a reason to tag blind: a red release run means the tag names a
commit that must not ship - fix it on `main` and tag the fix, never re-run until
a flake lets it through without looking at the failure.

End the annotation with the trailer the provisioning host parses:

    Security-Fixes: #6, #7, #8         issues this release closes
    Security-Fixes: none               checked, nothing security-relevant
    Security-Fixes: unspecified        the default when the line is absent

**Never type that list from memory.** v0.3.0 shipped with four issues in it
where the release closed eight, because the list written down was the issues
closed on GitHub that morning, not the issues the release fixed. Derive the
candidates instead:

```bash
set -euo pipefail
prev=$(git describe --tags --abbrev=0 HEAD^)
# grep exits 1 when a range references no issue at all, which is a valid
# release, not a failure. Accept that one status and no other.
refs=$(git log "$prev..HEAD" --format='%B' |
         { grep -oE '#[0-9]+' || [ $? -eq 1 ]; } | tr -d '#' | sort -un)
gh issue list --state all --label security --limit 200 --json number,title \
  --jq '.[] | "\(.number)\t\(.title)"' |
while IFS=$'\t' read -r n t; do
  if grep -qx "$n" <<<"$refs"; then printf '#%s  %s\n' "$n" "$t"; fi
done
```

One API call, and it fails closed: a broken lookup aborts with a non-zero exit
rather than dropping a candidate. That matters more than it looks - a list that
silently loses an entry fails in exactly the way the original defect did. The
guard around `grep` is what keeps "fails closed" from swallowing the empty
case: without it a release whose commits reference no issue exits 1 with no
output, which reads exactly like a broken run. A real grep error still aborts.

The result is a **superset to strike down**, never a list to paste. Two rules
remove the false positives, both mechanical:

- **Already released?** An issue named in an earlier release's trailer is
  already shipped. `gh release list` and read the trailers.
- **Mentioned or fixed?** A commit that merely discusses an issue number puts
  it in the list. Check that the release range actually contains the fix.
- **Does the fix reach the device?** The trailer drives an urgency decision, so
  it names what installing this release changes for a user. An issue closed by
  removing something that was not in the shipped APK anyway belongs in the
  prose, not in the trailer.

All three rules earn their keep. For v0.3.0 the command returns the correct eight
plus #14, whose fix shipped one release later - the version catalog entry it
reported was still present at the tag. For v0.3.1 it returns five candidates
and **none** of them belong: #7, #8, #12 and #13 appear only as an example
inside a commit message about the trailer format, and all four shipped in
v0.3.0. #14 survives both of the first two rules - the commit that closes it
is in the range - and falls to the third: it removed a version catalog entry
for a library no module had referenced since v0.3.0, so installing v0.3.1
changes nothing about that exposure. It is named in v0.3.1's prose instead.

If nothing survives, write `Security-Fixes: none`. Do not omit the line -
omitting it publishes `unspecified`, which tells the provisioning host that
nobody checked, and it treats that as urgent.

Do not derive the list from close timestamps. Issues closed by deleting the
feature get closed whenever someone audits them, which for v0.3.0 was ten hours
after the tag: a window query over the release interval returns exactly the
four issues that were missed and none of the four that were written down.
GitHub's own close references do not help either - only two of the eight were
closed through a commit reference, the rest by hand.

## Emulator (GrapheneOS, self-built)

The test target is a self-built GrapheneOS emulator (`~/android/grapheneos`,
target `sdk_phone64_x86_64-cur-userdebug`, test-keys), operated via
`~/Development/andashi/provisioning/emulator/run.sh`.

- **One instance per session.** Each instance is a serial plus an overlay dir
  under `~/Development/andashi/provisioning/emulator/instances/`, and the two always go
  together (the provisioning repo's README, "Emulator instances", is the
  authoritative table):

  | Serial | `OVERLAY_DIR` | Used by |
  |---|---|---|
  | `emulator-5554` | none (build tree) | the working instance, interactive |
  | `emulator-5556` | `instances/test` | this repo's L4 scripts (default) |
  | `emulator-5558` | `instances/test-2` | the provisioning repo's own verification runs |
  | `emulator-5560` | `instances/test-fold` | foldable (Pixel Fold profile): Fold L2/L4 work, screenshots |
  | `emulator-5562` | `instances/test-fold-gpu` | foldable, started with `GPU=host` (the default in `e2e/measure-unfold.sh`, which runs on it) |

  The L4 scripts take `SERIAL` and `OVERLAY_DIR` from the environment
  (defaults: `emulator-5556`, `instances/test`), so a second session runs
  `SERIAL=emulator-5558 OVERLAY_DIR=~/Development/andashi/provisioning/emulator/instances/test-2 e2e/l4-smoke.sh`
  instead of queueing on the default one.
- Respect `device-lock.sh`: the lock is **per instance** (serial as argument, or
  `SERIAL`/`ADB_SERIAL`). Never start, stop or adb into an instance another
  session holds. `device-lock.sh status` lists every held instance.
- A running instance nobody holds is a stray, whoever booted it: the
  measurement scripts stop one at the end of their run and say so. An
  instance you booted by hand and want to keep: hold its lock
  (`device-lock.sh acquire <owner> <serial>`) and pass that owner as
  `LOCK_OWNER` to the scripts you run on it. A hand-booted emulator-5562 ran
  unlocked for five hours on 2026-09-26, and another session's cleanup
  stopped a locked one an hour later.
- Gradle's device tasks are adb commands too, and they fan out:
  `connectedDebugAndroidTest` installs the APKs and runs the instrumentation,
  and `install*` installs, on **every** connected device unless
  `ANDROID_SERIAL` names one. Pin it on every such command, not only the
  first of a session:

      ANDROID_SERIAL=emulator-5562 ./gradlew :app:ui:connectedDebugAndroidTest

  It happened: a session's unpinned L2 runs installed the test APK and
  launched test activities on two instances other sessions held, one mid-L4
  run. Nothing reports it; the only trace is one directory per device under
  `build/outputs/androidTest-results/connected/debug/`, and a red run can be
  another device's failure.
- L4 runs never use the working instance's `userdata-qemu.img.qcow2`. Test
  instances run writable, because `-read-only` disables snapshots entirely,
  load included. Every run starts by loading its snapshot, which resets RAM and
  disks, so nothing carries over between runs. Never `run.sh snapshot` over
  `clean` or `profiles-ready` by accident.
- Snapshots per test instance: `clean` (near-first-boot, the only honest base,
  the **release gate**) and `profiles-ready` (`clean` + `00-profiles.sh`, no
  launcher; the **everyday** base of `e2e/l4-provisioning-config.sh`).
  `vor-workprofile` on the working instance is already provisioned and not a
  base.
- **Who refreshes `profiles-ready`:** whoever changes `config/profiles.json` in
  the provisioning repo, on every test instance, in the same session
  (procedure in that README). A stale one does not fail runs, because
  `00-profiles.sh` still reconciles, but it makes them slow again.
- **A release build signed with the debug key breaks every secondary user.**
  Not the `debug` variant, which carries `applicationIdSuffix = ".debug"` and
  installs beside the release package rather than over it. The one that does
  this is a **release** build made without the release keystore: it keeps the
  `org.andashi.home` application id and falls back to the debug key (#137).
  The per-user external directory survives the uninstall carrying the ownership
  of the install that created it, so the new build cannot write into it:
  `content write` fails with a null `ParcelFileDescriptor` over `IOException:
  Permission denied` in `ConfigIngestProvider.newTempFile`, permanently rather
  than as a race (twelve attempts over 24 seconds, identical every time).
  Repair per user, and the user has to be running first, because `pm clear` on a
  stopped user prints `Success` and changes nothing:

      adb -s <serial> shell am start-user -w <uid>
      adb -s <serial> shell pm clear --user <uid> org.andashi.home

  Starting the instance from a snapshot taken before the swap avoids it
  entirely. Measured 2026-09-25 on this launcher, on this emulator image, for
  the external files directory the ingest provider uses; the mechanism is
  generic to Android but has not been tested against another app or a real
  device. The provisioning repo's README carries the longer version.
- **A launcher process that survived a snapshot load can render wrongly.**
  From its second window size change on, the dock's icons were composed,
  placed and drawn per Compose, yet HWUI's overdraw view showed no pixels; a
  process launched after the load kept them through four fold cycles, and a
  device never crosses a snapshot's clock jump. This is an **emulator
  artefact, not a device bug**: #129 was closed as such, not as fixed, and is
  to be reopened if the symptom ever shows on hardware.
  So one window size change after a load is fine, but a sequence of more than
  one size change starts from a freshly launched process, restarted after the
  restore:

      adb -s <serial> shell am force-stop org.andashi.home
      adb -s <serial> shell am start -a android.intent.action.MAIN -c android.intent.category.HOME org.andashi.home
- **An injected gesture is not a finger, and "on top" is not "ready".** A
  configured swipe that opened its app every other time on the emulator
  looked like the scaffold dropping gestures. Instrumented from
  `dispatchTouchEvent` through the scaffold to the launch, in twenty-one
  runs on three instances, the scaffold never lost a flick that reached it. Every
  failure had one of three causes outside the launcher:
  - **Input during the app-to-home transition never reaches the launcher.**
    A flick sent right after Home was lost 8 times in 8, 1 in 5 at 0.3 s
    and none from 0.6 s, although the launcher was already the top activity
    and, in `dumpsys window`, already focused. `mCurrentFocus` switches while
    the transition still runs; `reason=Transition` in the same dump is still
    there 0.15 s after Home and gone by 0.45 s.
  - **`input swipe` waits for the app to finish each event, and runs in the
    guest.** With the launcher's main thread busy (its first composition
    after boot: 365 to 1155 ms before the down event was handled) the tool's
    120 ms are spent on the down event and it sends the up without a move - a
    tap. With the guest's CPU busy (5556 and 5560 draw glass and animations
    without the host GPU) two to four samples arrive, the last one 90 to
    500 ms before the up. Compose then computes a velocity of zero and the
    drag ends at the last move, short of the threshold. With the host GPU
    and an idle guest, 10 flicks of 120 ms in 10 opened, each sent a second
    after Home.
  - **The launcher started by component** (`am start -n`) lands in a task
    of its own, and the first Home press replaces it with the home instance;
    a gesture during that swap is lost. `show_home` starts it with the HOME
    intent since the fix for this.

  What a gesture step on the emulator waits for, then: the launcher focused
  **and** no `reason=Transition` in `dumpsys window` **and** its frame
  counter (`dumpsys gfxinfo <pkg>`, "Total frames rendered") still for
  300 ms, and a swipe slow enough (300 ms) that the distance alone crosses
  the threshold. That took 20 flicks from half failing to one in twenty; the
  one left was the `input` tool starved under host load, which no condition
  inside the guest can wait away. A step that allows a second attempt is
  compensating for that documented limit, not hiding a defect - and it says
  which attempt worked, so a count that climbs is visible. Whether a real
  device loses a touch in the first half second after Home is a platform
  question this could not answer: the launcher never sees that touch.
- **The shell cannot change a system app's component state** on this image
  (`pm disable`/`enable` refused in states 2 and 3). So a widget-only app arrival
  cannot be staged by disabling a system app's launcher activity; it needs a
  fixture APK, built at test time with no Gradle module from the SDK build tools
  (`aapt2`, `d8`, `zipalign`, `apksigner`, taken from the newest
  `build-tools/*/`) plus the JDK's `javac` - two installations, which is worth
  spelling out because a missing tool sends you to whichever one you assumed. It
  must **fail loudly** when any of them is absent rather than skip the step.
- Known emulator limits: nothing Google-server-side can be validated there
  (sandboxed Play, Play Integrity, push); wallpapers apply only after reboot;
  test-keys mean results do not equal "tested on release GrapheneOS".
