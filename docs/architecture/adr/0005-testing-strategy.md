# 0005: Test pyramid and AI-driven development harness

Status: accepted (2026-09-17)

## Context

Upstream has effectively no tests: two unit test files (`OpeningScheduleTest`,
`OpeningHoursTest`), zero `androidTest` sources, zero Compose tests. The fork is
developed AI-assisted, and AI-driven change without a test net is how silent
regressions ship. Tests are therefore **phase 1**, built before the grid.

## Decision

A pyramid, bottom-up, each layer with a clear job:

### L1 — Unit tests (JUnit4 + Robolectric where needed)

Fast, headless, run on every change. Target the fork's own logic, which is
deliberately isolated for this:

- `GridLayoutEngine` — placement, collision, push-down reflow, span clamping.
  Pure functions over immutable data; property-based tests for invariants
  (no overlaps, nothing out of bounds, determinism).
- Config pipeline — parse, `schemaVersion` migrations (golden files per version),
  convergence diff (ADR 0003). Table-driven.
- Repository/DB migrations — Robolectric + Room migration tests.

### L2 — Compose UI tests (`androidx.compose.ui.test`)

Run on emulator/device (and partially via Robolectric):

- edit mode: enter/exit, drag between cells, push-down behavior visible,
  dock add/remove/reorder
- config reload visibly mutating the composition
- glass surface smoke tests (does not assert pixels — that's L3)

### L3 — Screenshot tests (Roborazzi)

- fixed test wallpaper, fixed clock, golden images of the home grid in the glass
  style; catches Compose/rendering regressions that L1/L2 cannot see (ADR 0004).

### L4 — End-to-end, in `e2e/` of this repo

The acceptance harness for AI-driven development. The **scenarios live in this
repo** (`e2e/`) because they change with launcher features (config schema,
read-back provider) and are part of a PR's definition of done. The **instance
harness** (emulator start/stop, snapshots, qcow2 overlays, device lock) stays
in the provisioning repo (`~/Development/andashi/provisioning/emulator/`) and is invoked
as a script interface — on a **dedicated second emulator instance** so test
runs never interfere with the interactively used one:

- separate instance on port 5556 (`emulator-5556`); the GrapheneOS emulator runs
  from the build tree (`out/target/product/emu64x/`) without SDK AVDs, so the test
  instance gets its **own qcow2 overlays** over the shared read-only base images
  (copy-on-write, cheap) — never the working instance's `userdata-qemu.img.qcow2`.
  `run.sh` gains a `-port` flag derived from `SERIAL` and an image/overlay-dir flag
- snapshots make runs reproducible and disposable. The **only honest base state is
  the `clean` snapshot** (near-first-boot, taken before any zone/profile work);
  later snapshots are already provisioned and must not be used as a base.
  Exception, added 2026-09-19 (#27): `profiles-ready` is `clean` plus
  `00-profiles.sh` and nothing else. It is the everyday base of
  `l4-provisioning-config.sh`, which still re-runs `00-profiles.sh`, so drift
  shows up. `clean` stays the release gate.
- `~/Development/andashi/provisioning/emulator/device-lock.sh` is an advisory lock shared by
  all sessions working on these machines — test tooling must acquire it before
  touching any instance and must never start/stop an instance it does not own.
  Since #27 the lock is per instance (per serial), and every session can run
  its own instance (`SERIAL` + `OVERLAY_DIR`, e.g. `emulator-5558` +
  `instances/test-2`) instead of queueing on `emulator-5556`.
- Known emulator limits (documented so results are not over-claimed):
  - nothing Google-server-side can be validated there (sandboxed Play, Play
    Integrity, push, account brokers),
  - the build is signed with **test-keys**, not GrapheneOS release keys — passing
    here means "works on the self-built emulator", not "verified on release
    GrapheneOS",
  - wallpapers apply only after a reboot — L4 scenarios asserting wallpaper state
    must reboot first or skip the assertion.

```
emulator-5556 snapshot restore -> provision (push launcher.json, broadcast reload)
  -> read back via content provider -> assert equality
  -> UI assertions (uiautomator) only where the provider cannot see it
```

An AI agent's definition of done for any feature: L1+L2 green locally, L4 scenario
updated and green on the emulator.

### Wiring

- `test`/`androidTest` source sets added to the modules the fork touches;
  `minifyEnabled` debug variant for tests is unnecessary — test the debug build.
- CI (GitHub Actions on the fork): L1+L3 on JVM, L2 on an emulator runner; L4 runs
  on demand (self-hosted / manual) because it needs the GrapheneOS emulator.

### Which OS image for which layer — and why

- **L2 runs on the stock Android emulator image (API 36), not GrapheneOS.**
  Compose UI tests exercise app behavior (rendering, gestures, AppWidget hosting),
  which is AOSP core and does not diverge under GrapheneOS. Stock images are one
  `sdkmanager` line in CI; a self-built GrapheneOS image would mean hosting and
  caching gigabytes for zero additional signal at this layer.
- **L4 must run on the self-built GrapheneOS emulator**, because what it tests *is*
  the deployment target: per-profile provisioning, GrapheneOS's INTERNET-permission
  revocation interacting with widgets, Storage Scopes avoidance, and the config
  push/reload/read-back loop under real multi-user semantics.
- **Release gate:** the L2 suite additionally runs once on the GrapheneOS emulator
  before each fork release — cheap, since the instance exists anyway, and catches
  any UI divergence the layer reasoning got wrong.

## Consequences

- New code is written test-first by policy; the AGENTS.md of the repo documents the
  harness so any agent picks it up.
- Upstream code we do not touch stays untested — accepted; the net exists to make
  *fork changes* safe, not to audit upstream.
