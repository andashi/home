# 0002: JSON (JSONC-tolerant) as the dotfiles config format

Status: accepted (2026-09-17)

## Context

The whole launcher configuration — grid layout, dock favorites, theme reference,
widget settings — must live in a dotfiles repo as one human-editable document
(Omarchy-style), be parsed by the app on hot reload (ADR 0003), and be verifiable.

Candidates:

| Format | For | Against |
|---|---|---|
| **JSON** | kotlinx.serialization is already the app's settings format (`LauncherSettingsData`); zero new dependencies; trivial schema generation | no comments/trailing commas in strict mode |
| YAML | human-friendly | new parser dependency (snakeyaml), whitespace/typing pitfalls, second serialization stack |
| TOML | nice for flat config | nested grid layout becomes awkward; new parser dependency |
| Custom DSL (Hyprland-style) | prettiest to hand-edit | a parser and a language to design, document, and test; disproportionate |

## Decision

**JSON**, with a JSONC-tolerant reader:

```kotlin
Json {
    allowComments = true        // JSONC comments, Omarchy/waybar style
    allowTrailingComma = true
    ignoreUnknownKeys = true    // forward compatibility, both directions
    prettyPrint = true
}
```

(kotlinx-serialization ≥ 1.7 supports `allowComments`; the project pins 1.11.0.)

Additional rules:

- The document carries an explicit `schemaVersion: Int`. Migrations run config-side,
  are pure functions `ConfigVn -> ConfigVn+1`, and are unit-tested.
- A **JSON Schema** is generated from the Kotlin model (or maintained alongside) and
  committed to the dotfiles repo; editors get completion and validation via
  `$schema`.
- The config is a *desired state* document, not a backup archive. It does not adopt
  Kvaesitso's version-pinned backup format — that was the trap identified in the
  exported-surface analysis ("fragile UI automation traded for fragile format
  coupling"). The config schema is the fork's own, deliberately small, public
  contract.

## The document

What the rules above describe, in full. This example is not decoration: it is
parsed by `ConfigParserTest` on every run, so it cannot drift from the code the
way the favorites shape once did (#35, andashi/provisioning#1). Everything
except `schemaVersion` is optional, and an absent key means *unmanaged*, not
*off*.

<!-- adr-0002-example -->
```json
{
  // Bumped when the shape changes; migrations are pure ConfigVn -> ConfigVn+1.
  // Version 1 files still load: the launcher migrates them (dock.favorites
  // became favorites, the dock and the widgets list went away).
  "schemaVersion": 2,

  "icons": {
    "themed": true,
    "enforceThemed": false,
    "pack": "com.example.iconpack"
  },

  "appearance": {
    // The glass surfaces (ADR 0004); these are the defaults, see "Glass" below.
    "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true, "searchWallpaperBlur": true },
    "wallpaper": {
      // Uploaded beforehand to
      // content://<applicationId>.config-ingest/wallpapers/zone.jpg
      "image": "zone.jpg",
      "target": "both" // "home" | "lock" | "both"
    }
  },

  "home": {
    "searchBar": { "position": "bottom" }, // "top" | "bottom"

    // The one pin list, shared by search and the favorites widget on the grid.
    "favorites": [
      // Both spellings are valid. The object form is what a round trip
      // writes back; the bare package name means the personal profile.
      { "packageName": "org.thoughtcrime.securesms", "profile": "personal" },
      { "packageName": "com.example.work.mail", "profile": "work" },
      "com.example.dialer"
    ],

    // The master switch for the grid below.
    "widgets": { "enabled": true },

    // The single-page home grid (ADR 0001). Rows are derived from the screen.
    "grid": {
      "columns": 4,     // per cover-width page; the fold layout is twice as wide
      "locked": false,  // true: no edit mode, nothing is written back
      "labels": true,   // a label under every item but the dock
      "layouts": {
        "phone": {
          "items": [
            // The favorites widget in the bottom row, full width: the dock.
            { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 },
            // An AppWidget by provider component, with geometry in cells.
            { "id": "clock", "widget": "com.android.deskclock/.DigitalAppWidgetProvider",
              "x": 0, "y": 0, "w": 4, "h": 2 }
          ]
        },
        // 8 columns wide; the cover display renders columns 4 to 7 (#93).
        "fold": { "items": [] }
      }
    }
  }
}
```

Three things the example is here to settle, because nothing written down said
them before:

- **A favorite is an object or a package name**, never anything else. The short
  form was what the config generator emitted while the contract declared only
  the long one, and because this parser does not set `coerceInputValues`, a
  single bare string failed the decode and took the zone's *entire*
  configuration with it - wallpaper, dock, icons and all. Both decode now, and
  a favorite that is neither still fails loudly.
- **`home.favorites` manages app pins only** (#3 D4). The apps are pinned in
  file order. Shortcuts, tags and contacts pinned on the device keep their
  relative order and follow the app block; contacts are never named in the
  file; automatically sorted pins (what "Pin to favorites" in search makes)
  are not touched. A reload used to unpin the manually sorted ones, because
  the file could not name them and the list replaced everything.
- **Comments and trailing commas are part of the format**, not a courtesy of
  whichever editor wrote the file. The reader above enables both.
- **A grid item has a stable `id`** (`^[a-z0-9][a-z0-9-]{0,31}$`, unique per
  layout). Write-back and the database match on it, never on the array
  position, so reordering or deleting an item in the file cannot rebind another
  item's widget. The AppWidget host's integer id is device-local and never
  appears in the document. Geometry (`x`, `y`, `w`, `h`, in cells) may be
  omitted once: the launcher places the item at the first free cells and, once
  write-back lands (#23), writes the geometry into this file. Until then a
  re-push of the same file stays a no-op, because an absent field matches
  whatever was placed.

`profile` accepts `personal`, `work` and `private`; Private Space is just
another profile here (ADR 0006).

**Since write-back (ADR 0003 section 5, 2026-09-22) the file is no longer only
the host's declaration; it is the last agreed state between host and device.**
Edit mode on the device writes `home.grid` into `launcher.json`, so the copy in
the dotfiles repo can be behind the one on the phone. The host therefore pulls
before it pushes (owner: `adb pull`; other profiles: the read-back provider,
which serves the effective document in this exact shape) and commits the
pulled file like any other change; a push that would overwrite a device edit
is refused by the provisioning step on a hash mismatch
(andashi/provisioning#2). `home.grid.locked: true` turns this off for a
profile that must not be edited on the device; it is the exception, not the
default, because editing on the device and pulling the result is the point.

**The document above is the contract, not a promise about one build.** A build
may accept a key and not serve it as written. Since #47 it does not stay silent
about that: a present key the build does not serve is reported as an
`inert-key` warning in the reload diagnostics, next to `unknown-key`, carrying
a reason. A warning does not fail the reload, so a config that names such a key
still applies.

"Does not serve" covers doing nothing and doing something else, and the second
is the more dangerous one. The example that motivated the mechanism was
`home.dock.enabled`: no dock was drawn, because the favorites widget had taken
that role - but the value still reached the favorite affordances in search
results. The grid closed that case (#46): the dock *is* the favorites widget on
the grid, and the key left the contract with schema version 2.

Inert today, for a stated reason (`ConfigParserTest` pins the list):

- `appearance.transparency`: replaced by `appearance.glass` (#73). The file no
  longer feeds upstream's transparency schemes, the read-back no longer serves
  the section, and its sub-keys are neither spell-checked nor validated - one
  diagnostic for the section, nothing more.

`appearance.glass` and `home.grid.labels` were inert while only stored and
served back (#73, #74) and are applied since the surfaces draw them (#75).

### Glass (ADR 0004, #24)

```jsonc
"appearance": { "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true, "searchWallpaperBlur": true } },
"home":       { "grid": { "labels": true } }
```

| Key | Meaning | Accepted | Default |
|---|---|---|---|
| `appearance.glass.blur` | backdrop blur radius in dp; `0` is tint only | 0..64 | 24 |
| `appearance.glass.tint` | alpha of the zone's Monet surface color over the backdrop | 0..1 | 0.12 (0.35 before #82) |
| `appearance.glass.radius` | corner radius of every glass surface, dp | 0..64 | 28 |
| `appearance.glass.contrast` | `low` / `medium` / `high`: scales blur and tint, `high` adds a text scrim | enum | `medium` |
| `appearance.glass.wallpaperBlur` | the home background is the blurred backdrop, not the sharp wallpaper (#82) | boolean | `true` |
| `appearance.glass.searchWallpaperBlur` | the background behind search is the blurred backdrop, whatever `wallpaperBlur` says; search is an overlay (#91) | boolean | `true` |
| `home.grid.labels` | labels under grid items; never on the dock | boolean | `true` |

Numbers decode as floats, so a generator that writes `24.0` does not lose the
zone. A value out of range is an `invalid-glass` error at the field's path; an
unknown `contrast` fails the document with a message naming
`appearance.glass.contrast`, like every other enum. The upper bounds exist
because the file is untrusted input and blur costs GPU time on every surface.
**Icons (#86).** `icons.themed` defaults to `true` in the fork: the Clear look
is built from monochrome layers and pack glyphs, and without them every icon
would be the grey fallback. When `icons.pack` is absent (and none was chosen
in the settings), the launcher uses Lawnicons if it is installed - provisioning
installs it - and picks it up when it is installed later. The read-back serves
what is configured, so an absent pack reads back as absent. Lawnicons is not
bundled: it is 39.5 MB of trademark-derived icons and changes weekly.

The read-back always serves `glass` complete, defaults filled in, so a host
compares field by field without knowing them. There is no icon `style` or
`shape` key: the fork renders one icon look on one shape (ADR 0004). Nor is
there one for the edge lens or the rim that make the glass liquid rather
than frosted (#82): one look, its constants in `:core:glass` (`GlassLook`).

### Search (#91)

```jsonc
"search": {
  "favorites": true, "allApps": true, "layout": "grid", "labels": true,
  "contacts": true, "shortcuts": true, "filterBar": true, "openKeyboard": true,
  "launchOnEnter": true, "reversed": false, "hiddenItemsButton": false
}
```

A top-level section, because search is an overlay above every page and not
part of `home`. It holds search's **behavior**; its look is `appearance.glass`
(including `searchWallpaperBlur`) and its columns are `home.grid.columns`, so
nothing about search's appearance is configured twice.

| Key | Meaning | Default |
|---|---|---|
| `search.favorites` | the favorites row at the top of search | `true` |
| `search.allApps` | all apps while the query is empty | `true` |
| `search.layout` | `grid` or `list` for app results | `grid` |
| `search.labels` | labels under app icons in search | `true` |
| `search.contacts` | contacts in the results | `true` |
| `search.shortcuts` | app shortcuts in the results | `true` |
| `search.filterBar` | the filter bar above the keyboard | `true` |
| `search.openKeyboard` | the keyboard opens with search | `true` |
| `search.launchOnEnter` | Enter launches the best match | `true` |
| `search.reversed` | results from the bottom up | `false` |
| `search.hiddenItemsButton` | a button in the bar that shows hidden items | `false` |
| `search.barPosition` | the bar's position while search is open (#107); absent, it follows `home.searchBar.position` and is not read back | follows home |
| `search.actions` | the search actions in order (#106): `websearch`, `url` (label, url with `${1}`, optional package and encoding), `app` (label, package) or a built-in; present replaces the device's list, `[]` is none | the device's own; new installs: built-ins + `websearch` |

The defaults are the launcher's behavior before the section existed, so a
file without `search` changes nothing. Each key writes one of upstream's own
settings, the ones the search UI already reads. `contacts` switches only the
device's own contact provider and leaves any other alone. It grants no
permission: with contacts on and the permission missing, search shows the
existing permission banner. An unknown `layout` fails the document with a
message naming `search.layout`, like every other enum. The read-back serves
`search` complete, defaults filled in.

## Consequences

- One serialization stack everywhere: app settings, config file, schema, tests.
- Hand-editing is comfortable (comments, trailing commas, schema-aware editors).
- `ignoreUnknownKeys` lets the dotfiles repo move ahead of the app (and vice versa)
  without hard failures; unknown keys are reported through read-back diagnostics
  instead.
