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

Example module paths: `:core:preferences`, `:data:widgets`, `:app:ui`.

## Test policy

This fork is developed AI-assisted, so tests are the safety net, not an
afterthought (see `docs/architecture/adr/0005-testing-strategy.md`):

- New fork code is written **test-first**. Pure logic (grid layout engine, config
  parsing/migration/convergence) lives in headless, unit-testable modules.
- Before touching existing upstream code, write characterization tests that pin
  its current behavior.
- Coverage is measured on fork-touched modules only; untouched upstream code
  staying untested is accepted.
- Definition of done: unit + Compose tests green; for config/provisioning-facing
  features, the L4 scenario in `e2e/` (driven against the provisioning repo's
  emulator harness) updated and green.

## Test harness (Phase 1)

- **L1 unit tests**: JUnit4 + Robolectric 4.17 (SDK 36/37 supported; needs the
  `--add-opens` JVM args already wired in the module build files — copy that
  `tasks.withType<Test>` block when adding tests to another module). Modules
  with test wiring so far: `:core:base`, `:core:config`, `:core:preferences`,
  `:services:config`, `:data:database`, `:data:locations`, `:data:searchable`,
  `:data:themes`, `:data:widgets`, `:app:ui`.
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
with SDK 36+ requires >= 21), L2 on PRs via `android-emulator-runner` (stock
API 36 image). L4 stays manual/local.

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
