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

A fourth trigger, `grid-measured`, fits the grid once its rows are known.
Only the device that draws a layout knows its rows (#90), and only after the
first draw. A file applied before that keeps its layouts as written, instead
of fitting them to a guess: a guess of six dropped the Fold's seventh row. The
first measurement, and any later change of the rows, reloads the file and
applies its layouts even where the file and the store agree. A plain reload
would find them agreeing - that is exactly the state of a layout kept as
written - and fit nothing.

### 5. Write-back: every section follows the device

Added 2026-09-22 for the home grid (ADR 0001, #23); extended to every
section and revised 2026-09-25 (#3 slice 4). A change made on the device -
a setting, the favorites, the search actions, an edit-mode arrangement -
goes back into `launcher.json`, so the file on the device describes what the
device does. There is no switch for it: what the device may not change is
locked, like `home.grid.locked`.

- **Only keys the file already has are written.** A key the file leaves out
  is unmanaged (ADR 0002), and a write-back must not start managing it: the
  file never grows a section, a key or a `locked: false` it did not have.
  A key the model does not know - an inert one, or one of a newer build - is
  left as written.
- **The device is compared with what the file produced, not with what it
  says.** The launcher applies some values differently from their text: a
  widget height clamped to what the widget or the grid allows (#140), a favorite whose
  app is not installed here. Write-back records what a person changed on the
  device, never what the launcher failed to do, so such a value keeps its
  written text. What the file produced is the *baseline*: the effective
  state recorded after each reload and each self-write, tied to the file's
  hash. **One rule for every capture point: a value is taken at the moment
  it was written, never by a later read.** Each section the reload wrote
  comes from the write itself - the settings from the DataStore update's own
  result, the grid, favorites and search actions from what went into their
  repositories - clamps and skipped entries included; every other section,
  a failed one included, from the state read before the apply. A self-write
  records the device state it was computed from, and saves it before its
  report. So a change a person makes during or right after a reload is
  never mistaken for the file's. A push through the ingest provider is
  committed under the same file lock, so it cannot land between a
  write-back's read and its rename. Without a
  baseline for exactly this file nothing is written - the device state
  cannot be told apart from what the file produced, and guessing is how
  `h: 7` becomes `h: 6` - and the startup check reloads a file whose
  baseline is missing. The one cost: setting on the device exactly the value
  a clamped setting already shows writes nothing, and the file keeps its
  own, which renders the same.
- **A list or map is rewritten whole, and merged.** Favorites, search
  actions and the grid layouts are written as one value when any entry
  changed. Entries are matched by `id`, else by value: what nobody changed
  keeps its written text and spelling (a personal favorite stays a string);
  an entry the device never applied stays where it was, because the device
  cannot have removed it; an entry it had and no longer has was removed on
  it. A field an entry leaves out that still has the value it produced
  stays out, which is how the grid-item options keep their absent-means-
  default exception (ADR 0002). A key inside an entry that the model does not
  know - a newer field, one written by hand - is the file's and survives.
  An item written without geometry is placed
  by the launcher, and that placement is what the file produced, so it is
  not written back either; moving the item on the device is.
- **Every other byte survives.** Each changed value is spliced over its own
  span, found by a scanner that understands strings, `//` and `/* */`
  comments and nesting without building a tree (`JsoncObjectSpan`). A
  comment inside a rewritten list or map is the one thing that is lost.
- The write is atomic (temp file, rename), taken under the same lock as a
  reload, and re-parsed before the rename. A missing, unparsable, invalid,
  outdated (`schemaVersion` below the current) or oversized result is never
  written, and a file that changed since it was applied is reloaded first.
  A locked grid is left out of whatever else is written.
- **A skip is said, not swallowed.** Each skip is exposed to the device and
  appended to the last reload report as a warning `write-back-skipped:<code>`,
  for provisioning; the report stays otherwise what it was. An edit-mode
  change on a file without `home.grid.layouts` is kept on the device and
  skipped as `grid-unmanaged`, with how to make the file manage the grid.
- **Removed on purpose: inserting `home.grid`.** Until 2026-09-25 an edit on
  a file without `home.grid` inserted one, and a file without `home` got
  both. That is the file growing a section on its own, which the rule
  above forbids; the change stays on the device and the person is told, at
  the device and in the report. It is not a regression to restore.
- The wallpaper is not written back: a wallpaper picked on the device has no
  upload name to write.
- A provisioning push that lands after a write-back wins by being the last
  writer; the provisioning side pulls before it pushes and refuses a push
  when the device file's hash changed since the last pull
  (andashi/provisioning#2).
- **What write-back puts in the file can leave the device, but only by an
  owner's explicit action, and none of it by a new path.** Some of what
  write-back writes is authored on the device: a search action's `label` and
  `url` as someone typed them (a URL can carry a private endpoint), and the
  favorites, which name the apps a person uses. The same values already
  left the device before write-back:
  - by the read-back provider (section 4, shell and system only), which is what
    provisioning's `--pull` copies into a catalog in git;
  - by platform backup when the owner enables device backup, through the
    settings and databases they live in. The app permits this with
    `android:allowBackup="true"` and `android:fullBackupContent="true"`.

  Write-back adds a copy inside `launcher.json`, so "the file carries only
  what the host put there" stops being true. A key added to write-back is
  therefore a value that can end up in a zone's catalog: weigh it as that.

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
  one document: a change made by hand is written into `launcher.json` on the
  device, for every section since 2026-09-25, so there is no drift to
  surface. What the dotfiles say and what the device shows can still differ
  until the next pull or push, and the last writer wins; `home.grid.locked`
  switches edit mode and the grid's write-back off for a profile that must
  follow its dotfiles exactly.
- No network, no service, no telemetry: reload is fully local.
