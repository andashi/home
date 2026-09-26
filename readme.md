# Andashi Home

A search-first launcher with a minimal home screen, configured with a file.

The home screen is one page: a clock, a few widgets, a dock of favorites.
Nothing else lives there. Every app, contact, file, setting or calculation is
reached the same way, by typing a few letters into search. No icon grids to
arrange, no settings tree to click through: the launcher reads `launcher.json`,
converges to it, and tells you what it applied.

Andashi Home is the launcher of the [andashi](https://andashi.org) distribution,
a GrapheneOS setup that runs a phone as a set of isolated profiles ("zones").
Each zone gets its own `launcher.json`. That is the whole configuration story.

## The idea

- **Search first.** The search bar is the primary interface. Apps, shortcuts,
  contacts, files, calendar entries, unit conversions and web searches all come
  from the same field, ranked by use. Opening an app is typing, not hunting.
- **Minimal home.** The home screen holds what you glance at (a clock, a few
  widgets) and what you tap most (the dock). Apps live in search, not on the
  desktop, so the home screen never fills up and never needs tidying.
- **Configured, not clicked.** Every deterministic setting is a key in one
  JSON document with comments. Edit it, push it, the launcher reloads live.
  Re-pushing an unchanged file changes nothing; that is checked, not hoped.
- **Verifiable.** The launcher exposes its effective state and the diagnostics
  of the last reload. Provisioning does not trust an exit code, it reads back.
- **Per profile.** Every Android user (owner, work, private space, secondary
  users) has its own file and its own state. A config written into one profile
  never touches another.

## What a config looks like

```jsonc
{
  "schemaVersion": 2,
  "icons": { "themed": true, "pack": "app.lawnchair.lawnicons" },
  "appearance": {
    "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": false, "searchWallpaperBlur": true },
    "wallpaper": { "image": "home.jpg", "target": "both" },
  },
  "home": {
    "searchBar": { "position": "top" },
    "favorites": ["org.thoughtcrime.securesms", "app.vanadium.browser"],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "layouts": {
        "phone": { "items": [ { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 } ] },
      },
    },
  },
  "search": { "layout": "grid", "contacts": false },
}
```

Comments and trailing commas are fine. Unknown keys are reported, not rejected,
so the file may run ahead of the app and vice versa.

## How it reaches the phone

Three shell commands, all per Android user, all without root:

```sh
# 1. write the file into the launcher of user N (images the config refers to go the same way)
adb shell content write --user N --uri content://org.andashi.home.config-ingest/wallpapers/home.jpg < home.jpg
adb shell content write --user N --uri content://org.andashi.home.config-ingest/launcher.json < launcher.json

# 2. ask for an explicit reload (the file watcher does it anyway; this makes scripts deterministic)
adb shell am broadcast --user N -a org.andashi.home.action.RELOAD_CONFIG \
    -n org.andashi.home/de.mm20.launcher2.config.service.ReloadConfigReceiver

# 3. read back what is in effect, and the report of the last reload
adb shell content query --user N --uri content://org.andashi.home.state/config
adb shell content query --user N --uri content://org.andashi.home.state/diagnostics
```

The diagnostics carry the SHA-256 of the file the launcher last loaded, so a
script waits for exactly its own push and then compares the effective config
field by field. The [andashi provisioning](https://andashi.org) does this for
every zone in well under a minute, replacing what used to be 27 minutes of UI
automation.

<img alt="The configuration loop: push through the ingest provider, reload and converge, read back and verify, and write-back from edit mode" src="docs/configuration/img/config-flow.svg" width="760">

For the owner profile, plain `adb push` into the app's config directory works
too; the file watcher picks it up. That is the dotfiles workflow: edit, save,
done.

Debug builds use `org.andashi.home.debug`; the e2e scripts target that id.

## Security posture

Built for GrapheneOS and held to its standards:

- No Play Services, no telemetry, no network access for configuration.
- Config lives in app-specific storage; no broad storage permissions, Storage
  Scopes stay untouched.
- The three interfaces (ingest, reload, read-back) are gated to the shell and
  system user via `WRITE_SECURE_SETTINGS`. No app on the device can reach them.
- Whoever has adb access controls the device anyway; the launcher does not add
  a capability that does not already exist.

## What is configurable today

| Section | Keys |
|---|---|
| `icons` | themed icons, enforce themed, icon pack (Lawnicons by default when installed) |
| `appearance.glass` | blur, tint, radius, contrast, wallpaper blur on home and behind search |
| `appearance.wallpaper` | image (uploaded via `wallpapers/<name>`), target home, lock or both |
| `home.searchBar` | position |
| `home.favorites` | the pinned apps, per profile |
| `home.widgets`, `home.grid` | the widget grid: columns, lock, labels, phone and fold layouts, the dock as a grid item |
| `search` | favorites row, all apps, grid or list, labels, contacts, shortcuts, filter bar, keyboard, Enter, order, hidden items |

Every key, with screenshots on a phone and a Pixel Fold, is in
[docs/configuration](docs/configuration/README.md). Coverage of the remaining
deterministic settings is tracked in
[#3](https://github.com/andashi/home/issues/3).

## Status

The configuration system is complete for provisioning parity and verified end
to end on a GrapheneOS emulator, as the unrooted shell, across six profiles.
The home screen is a single-page widget grid
([#23](https://github.com/andashi/home/issues/23)) in a liquid-glass look
([#24](https://github.com/andashi/home/issues/24)), and search matches it
([#91](https://github.com/andashi/home/issues/91)). Releases are signed and
published from annotated tags. The frame-time measurement on a real Pixel
Fold (ADR 0004) is still open.

## Building and testing

```sh
./gradlew :app:app:assembleDefaultDebug        # debug APK
./gradlew :core:config:test :services:config:testDebugUnitTest   # config unit tests
./gradlew :app:ui:verifyRoborazziDebug         # screenshot goldens
e2e/l4-config.sh                               # end-to-end, needs the andashi emulator harness
e2e/l4-provisioning-config.sh                  # the real provisioning step against every zone
```

Test layers, emulator conventions and the definition of done are in
[`AGENTS.md`](AGENTS.md). Architecture decisions are recorded in
[`docs/architecture/adr/`](docs/architecture/adr/); start with the
[index](docs/architecture/README.md).

## Origins and license

Andashi Home started as a fork of [Kvaesitso](https://github.com/MM2-0/Kvaesitso)
by MM2-0, a search-focused launcher, and keeps its search and provider
foundation. It is a hard fork: no upstream merges, occasional cherry-picks,
and a home screen of its own. "Kvaesitso" is upstream's name; this project
does not use it.

This software is free software under the GNU General Public License 3.0.
Upstream copyright is retained:

```
Copyright (C) 2021–2026 MM2-0 and the Kvaesitso contributors
Copyright (C) 2026 andashi

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

The plugin SDK modules (`plugins/sdk` and `core/shared`), inherited from
upstream, are licensed under the Apache License 2.0 for as long as they remain
in the tree.
