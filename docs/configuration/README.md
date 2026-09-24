# Configuring Andashi Home

Andashi Home is configured by one JSON file, `launcher.json`. The file is the
source of truth: the launcher converges its state towards it on every reload,
and edit mode on the device writes the grid back into it
([ADR 0003](../architecture/adr/0003-config-hot-reload.md)). Everything you can
set is on these pages, with pictures of what it does on a phone and on a Pixel
Fold (cover and inner display).

<img src="img/full-dock-bottom-phone.jpg" width="270" alt="A phone home screen full of glass widgets, the dock at the bottom">
<img src="img/full-dock-bottom-fold-inner.jpg" width="390" alt="The Fold's inner display with the same widgets and more">

| Page | What it covers |
|---|---|
| [Appearance](appearance.md) | `appearance.glass` (the liquid-glass look), `appearance.wallpaper` |
| [Icons](icons.md) | `icons.themed`, `icons.enforceThemed`, `icons.pack`, the Clear look, Lawnicons |
| [Home grid](home-grid.md) | `home.grid`: columns, lock, labels, the phone and fold layouts, every item field, **where the dock can sit**, screens full of widgets |
| [Favorites](favorites.md) | `home.favorites`: the pinned apps the dock shows |
| [Search bar](search-bar.md) | `home.searchBar.position`, what search looks like |
| [Search](search.md) | `search`: favorites row, all apps, grid or list, labels, contacts, shortcuts, filter bar, keyboard, Enter, order, hidden items |
| [Screens](screens.md) | Every scene on the phone, the Fold's cover and its inner display |

## The file

The top level has five keys: `schemaVersion` (required, currently `2`),
`icons`, `appearance`, `home` and `search`. Everything else is optional, and **an absent
key means "unmanaged", not "off"**: the launcher leaves whatever is set on the
device. Comments and trailing commas are allowed (JSONC).

The smallest valid file:

<!-- config -->
```json
{ "schemaVersion": 2 }
```

A file that sets a little of everything:

<!-- config -->
```json
{
  // Bumped when the shape changes; older files are migrated.
  "schemaVersion": 2,
  "icons": { "themed": true },
  "appearance": {
    "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true, "searchWallpaperBlur": true },
    "wallpaper": { "image": "zone.jpg", "target": "both" }
  },
  "home": {
    "searchBar": { "position": "top" },
    "favorites": ["com.android.dialer", "com.android.messaging", "app.vanadium.browser", "app.grapheneos.camera"],
    "widgets": { "enabled": true },
    "grid": {
      "columns": 4,
      "locked": false,
      "labels": true,
      "layouts": {
        "phone": {
          "items": [
            { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider", "x": 0, "y": 0, "w": 2, "h": 2 },
            { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 }
          ]
        }
      }
    }
  }
}
```

## Getting the file onto a device

Replace `<pkg>` with the launcher's package: `org.andashi.home` for a release,
`org.andashi.home.debug` for a debug build.

- **Push through the ingest provider** (works for every profile, including
  secondary users, from the unrooted shell):

  ```sh
  adb shell content write --uri content://<pkg>.config-ingest/launcher.json < launcher.json
  ```

  A wallpaper the file names is uploaded the same way, under its file name:

  ```sh
  adb shell content write --uri content://<pkg>.config-ingest/wallpapers/zone.jpg < zone.jpg
  ```

- **Or `adb push`** to `/storage/emulated/0/Android/data/<pkg>/files/config/launcher.json`
  (owner profile only).

The launcher watches the file and reloads on its own. To force a reload:

```sh
adb shell am broadcast -n <pkg>/de.mm20.launcher2.config.service.ReloadConfigReceiver -a <pkg>.action.RELOAD_CONFIG
```

## Reading back what applied

The launcher serves the effective configuration and the report of the last
reload. It always serves them in this file's shape, so a script can compare
field by field:

```sh
adb shell content query --uri content://<pkg>.state/config        # the effective document
adb shell content query --uri content://<pkg>.state/diagnostics   # the last reload report
```

The report lists `appliedMutations` (the sections that changed) and
`diagnostics`:

- **Errors** (`severity: error`): the file is not applied at all. Examples are
  malformed JSON, a value out of range (`invalid-glass`, `invalid-grid-geometry`,
  …) or an unknown enum value.
- **Warnings** still apply the file:
  - `unknown-key`: a misspelled or unknown key, which is ignored.
  - `inert-key`: a key this build accepts but does not act on, with the reason.
  - Grid corrections: an item was enlarged to the widget's minimum, nudged, or
    did not fit.

## Removed keys

`appearance.transparency` (upstream's transparency schemes) was replaced by
`appearance.glass`. A file that still carries it gets one `inert-key` warning
and it has no effect. Schema version 1 files (with `home.dock`) are migrated on
load.

## How these pages are kept true

Every example marked for it is parsed by `ConfigurationDocsTest` and must
produce no diagnostic. The same test fails when a key of the contract is not
named on any page. The screenshots are taken by `e2e/config-screenshots.sh` on
the GrapheneOS emulator (a phone instance and a foldable one), from exactly the
configs shown, with provisioning's `themes/mauritius` wallpaper and Lawnicons
installed.
