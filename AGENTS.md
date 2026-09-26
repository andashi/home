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

Three conditions, no exceptions and no judgement about how small the diff is:
every check green, **zero** unresolved review threads, and CodeRabbit's review
covering the **current head**. If the hourly quota is exhausted (10 included
reviews per hour, rolling), wait for it. A pull request that waits an hour
costs an hour; one merged unreviewed costs whatever it broke.

Recount immediately before merging rather than trusting a count from earlier in
the session: CodeRabbit posts after a push, so a thread can open between the
check and the merge.

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
re-requested full review arrives as a review, an automatic incremental one
arrives as an edit to a comment created much earlier, and sorting on
`createdAt` hands you the older of the two while looking correct.

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

## Test harness (Phase 1)

- **L1 unit tests**: JUnit4 + Robolectric 4.17 (SDK 36/37 supported; needs the
  `--add-opens` JVM args already wired in the module build files — copy that
  `tasks.withType<Test>` block when adding tests to another module). Modules
  with test wiring so far: `:core:base`, `:core:config`, `:core:preferences`,
  `:services:config`, `:data:database`, `:data:searchable`,
  `:data:themes`, `:data:homegrid`, `:app:ui`.
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
      adb -s <serial> shell am start -n org.andashi.home/de.mm20.launcher2.ui.launcher.LauncherActivity
- Known emulator limits: nothing Google-server-side can be validated there
  (sandboxed Play, Play Integrity, push); wallpapers apply only after reboot;
  test-keys mean results do not equal "tested on release GrapheneOS".
