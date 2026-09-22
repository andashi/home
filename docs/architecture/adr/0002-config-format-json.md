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
    "transparency": {
      "name": "glass",
      "background": 0.7,
      "surface": 0.55,
      "elevatedSurface": 0.65
    },
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
        // 8 columns wide; the cover display renders columns 0 to 3.
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
the grid, and the key left the contract with schema version 2. No key of the
current contract is inert; the mechanism stays for the next one, and the test
that guards it runs against a table of its own.

## Consequences

- One serialization stack everywhere: app settings, config file, schema, tests.
- Hand-editing is comfortable (comments, trailing commas, schema-aware editors).
- `ignoreUnknownKeys` lets the dotfiles repo move ahead of the app (and vice versa)
  without hard failures; unknown keys are reported through read-back diagnostics
  instead.
