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

## CI

`.github/workflows/test.yml`: L1 + L3 on every push/PR (JDK 21 — Robolectric
with SDK 36+ requires >= 21), and L2 via `android-emulator-runner` (stock API
36 image, plus a foldable one), on every PR, every push to `main` and every
release. L4 stays manual/local.

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

  The L4 scripts take `SERIAL` and `OVERLAY_DIR` from the environment
  (defaults: `emulator-5556`, `instances/test`), so a second session runs
  `SERIAL=emulator-5558 OVERLAY_DIR=~/Development/andashi/provisioning/emulator/instances/test-2 e2e/l4-smoke.sh`
  instead of queueing on the default one.
- Respect `device-lock.sh`: the lock is **per instance** (serial as argument, or
  `SERIAL`/`ADB_SERIAL`). Never start, stop or adb into an instance another
  session holds. `device-lock.sh status` lists every held instance.
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
- Known emulator limits: nothing Google-server-side can be validated there
  (sandboxed Play, Play Integrity, push); wallpapers apply only after reboot;
  test-keys mean results do not equal "tested on release GrapheneOS".
