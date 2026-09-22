# 0003: File-based hot reload with convergence + read-back provider

Status: accepted (2026-09-17), revised 2026-09-22

Revision 2026-09-22: the config is the source of truth in **both**
directions. Edit mode writes `home.grid` back into `launcher.json` (section 5
and the Consequences); the earlier rule "config wins on the next explicit
reload, drift is a diagnostic" is withdrawn.

## Context

Provisioning today drives the launcher UI with uiautomator (`45-launcher-prefs.sh`,
~650 lines of coordinate arithmetic) because the stock app exports no usable
interface: no provider, a five-route deep-link whitelist, a crashing theme importer
(see `~/Development/andashi/provisioning/docs/architecture/launcher.md`, then
`docs/kvaesitso-interfaces.md`). Its failure mode was
never writing — it was **not being able to check**. This fork owns the app, so the
interface problem is solved at the root.

Goal: Omarchy-style workflow — edit a dotfile, push it, launcher reloads live, and
the result is machine-verifiable.

## Decision

Three components:

### 1. Config file location

`<external-files-dir>/config/launcher.json`, i.e.
`/sdcard/Android/data/<applicationId>/files/config/launcher.json`.

- App-specific storage: no runtime storage permission, fully compatible with
  GrapheneOS Storage Scopes. For the owner (user 0) it is also writable by
  `adb push`, the interactive dotfile path.
- Per Android user, hence per GrapheneOS profile: each zone/profile in the
  provisioning repo gets its own `launcher.json`.
- Watched with `FileObserver` + debounce (~300 ms) so editors that write
  non-atomically do not trigger partial reloads.

### 1a. Transport for secondary users: ingest provider, not `adb push`

**Measured 2026-09-19** on the self-built GrapheneOS emulator (userdebug,
Android 17, test instance from the `clean` snapshot, secondary user 10 created
and started):

| Target | `adb root` | shell (uid 2000) |
|---|---|---|
| `/storage/emulated/0/Android/data/<pkg>/files/config/` | ok | ok |
| `/storage/emulated/10/Android/data/<pkg>/files/config/` | Permission denied | Permission denied |
| `/storage/emulated/10/Download/` | Permission denied | Permission denied |
| `/mnt/user/10/emulated/…` | Permission denied | Permission denied |

The shell lives in user 0's mount namespace; the per-user emulated storage of
every other user is inaccessible to it, and release GrapheneOS has no
`adb root` anyway. An earlier draft of this ADR claimed "writable by `adb push`
for any user"; that was wrong and cost a provisioning step written against it.

The transport for provisioning is therefore a **write-only ingest
`ContentProvider`** (`content://<applicationId>.config-ingest/launcher.json`):

```
adb shell content write --user N \
    --uri content://<applicationId>.config-ingest/launcher.json < launcher.json
```

- `content write` is a stock Android shell tool; `--user N` makes the system
  resolve the provider in that user's launcher instance. The provisioning repo
  already uses this pattern for wallpapers (`helper/themectl`).
- Gate: `android:writePermission="android.permission.WRITE_SECURE_SETTINGS"`
  (held by shell and system only) plus a calling-uid check for shell/root in
  `openFile`. No app on the device can reach it; whoever has adb access
  controls the device anyway.
- The provider is transport only: bytes go to a temp file next to the config
  and are renamed onto it atomically when the stream closes. Reloading stays
  with the two triggers below; the watcher sees the rename. One loader, no
  third code path.
- `content write` exits 0 even on a provider error (it only prints the
  exception), so scripts must treat any output as failure. Completion is
  observed the same way as for a broadcast: poll `/diagnostics` for the
  written file's sha256.

### 2. Reload trigger

Primary: explicit broadcast, deterministic for scripts —

```
adb shell am broadcast -a <applicationId>.action.RELOAD_CONFIG [--user N]
```

The receiver is **exported** and gated by the manifest: the sender must hold
`WRITE_SECURE_SETTINGS` (`android:permission`, shell and system only). No
uid check in code: `getSentFromUid()` only works when the sender opts in via
`BroadcastOptions.setShareIdentityEnabled`, which `am broadcast` does not, so
it reports -1 for exactly the caller we want (measured, second L4 attempt).
A non-exported receiver was the first draft; measured
2026-09-19 as unrooted shell it is simply never invoked (`am broadcast`
reports completion, nothing runs). Only `adb root` could reach it, and release
GrapheneOS has none.

The file watcher is the convenience path for interactive editing (edit → save →
launcher updates). Both funnel into the same loader.

The launcher's own write-back (section 5) renames onto `launcher.json` the
way a push does, and the observer cannot tell who renamed. The watcher tells
them apart by hash: the write-back records a report with trigger
`self-write` and the SHA-256 of the bytes it wrote, and a file event whose
hash matches that report is not reloaded. Only a `self-write` report counts;
a push of unchanged bytes after any other reload still reloads, because
provisioning waits for the watcher's report of its write.

### 5. Write-back: edit mode writes `home.grid`

Added 2026-09-22 with the home grid (ADR 0001, #23). Moving or resizing a
widget in edit mode changes the launcher's database first and then the file,
once per edit session, so the file on the device always describes what is
on the screen:

- Only the text of the `home.grid` object is replaced. The span is located by
  a scanner that understands strings, `//` and `/* */` comments and nesting
  but does not build a tree (`JsoncObjectSpan`), so every other byte of the
  file survives, comments included. A comment inside the old `home.grid`
  object is the one thing that is lost. A missing `grid` is inserted into
  `home`; a missing `home` is inserted at the root.
- The write is atomic (temp file, rename), taken under the same lock as a
  reload, and re-parsed before the rename; a missing, unparsable or
  `locked` file is never written. A file that would exceed the parser's size
  limit is not written either. Skips are surfaced to the user, not written
  into the reload report, which keeps the last good report intact.
- A provisioning push that lands after a write-back wins by being the last
  writer; the provisioning side pulls before it pushes and refuses a push
  when the device file's hash changed since the last pull
  (andashi/provisioning#2).

### 3. Convergence, not application

Reload never "applies" a file. It:

1. parses + migrates the config (ADR 0002),
2. computes a **diff against current state**,
3. writes only the differences into the existing DataStore/repository layer,
4. records diagnostics (unknown keys, invalid entries, skipped items).

UI state therefore converges; re-pushing an unchanged file is a guaranteed no-op.
Compose recomposition over the DataStore makes the reload "hot" — no restart.

### 4. Read-back provider

An exported, **read-only** `ContentProvider` serving the current effective state
as JSON (`content://<applicationId>.state/config`), plus the diagnostics of the
last reload. Gated by `WRITE_SECURE_SETTINGS` like the ingest provider and the
receiver, so the whole config surface has one gate (shell and system). The
first draft left it open ("settings are not secrets"); it does expose dock
package names, widget selection and diagnostic messages, and there is no
on-device consumer that would need it, so least privilege wins. This is the
verification half of the loop:

```
content write launcher.json --user N  ->  broadcast RELOAD --user N
  ->  content query --user N  ->  assert equality
```

This is exactly option 2 of the exported-surface proposal, now trivial because we
own the code.

## Consequences

- `45-launcher-prefs.sh` and its entire uiautomator machinery are deleted from the
  provisioning repo once this lands; the launcher step becomes convergent like every
  other step.
- The convergence function (`Config -> Diff -> [Mutation]`) is pure and unit-tested
  against repository fakes; the provider is covered by instrumented tests.
- UI edits and config edits share one model, and since the 2026-09-22 revision
  one document: a widget moved by hand is written into `launcher.json` on the
  device, so there is no drift to surface. What the dotfiles say and what the
  device shows can still differ until the next pull or push, and the last
  writer wins; `home.grid.locked` switches edit mode and write-back off for a
  profile that must follow its dotfiles exactly.
- No network, no service, no telemetry: reload is fully local.
