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
  "schemaVersion": 1,

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
    "dock": {
      "enabled": true,
      "favorites": [
        // Both spellings are valid. The object form is what a round trip
        // writes back; the bare package name means the personal profile.
        { "packageName": "org.thoughtcrime.securesms", "profile": "personal" },
        { "packageName": "com.example.work.mail", "profile": "work" },
        "com.example.dialer"
      ]
    },
    "widgets": {
      "enabled": false,
      "widgets": ["apps"]
    }
  }
}
```

Two things the example is here to settle, because nothing written down said
them before:

- **A favorite is an object or a package name**, never anything else. The short
  form was what the config generator emitted while the contract declared only
  the long one, and because this parser does not set `coerceInputValues`, a
  single bare string failed the decode and took the zone's *entire*
  configuration with it - wallpaper, dock, icons and all. Both decode now, and
  a favorite that is neither still fails loudly.
- **Comments and trailing commas are part of the format**, not a courtesy of
  whichever editor wrote the file. The reader above enables both.

`profile` accepts `personal`, `work` and `private`; Private Space is just
another profile here (ADR 0006).

**The document above is the contract, not a promise about one build.** A build
may accept a key and not serve it as written. Since #47 it does not stay silent
about that: a present key the build does not serve is reported as an
`inert-key` warning in the reload diagnostics, next to `unknown-key`, carrying
a reason. A warning does not fail the reload, so a config that names such a key
still applies.

"Does not serve" covers doing nothing and doing something else, and the second
is the more dangerous one. Today's example is `home.dock.enabled`: no dock is
drawn, because the favorites widget took that role and follows `home.widgets`
(#46) - but the value still reaches the favorite affordances in search results.
A host that sets it gets an effect it did not ask for, which is why the
diagnostic carries the specifics instead of a blanket "ignored".

## Consequences

- One serialization stack everywhere: app settings, config file, schema, tests.
- Hand-editing is comfortable (comments, trailing commas, schema-aware editors).
- `ignoreUnknownKeys` lets the dotfiles repo move ahead of the app (and vice versa)
  without hard failures; unknown keys are reported through read-back diagnostics
  instead.
