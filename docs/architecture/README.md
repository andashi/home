# Architecture

This directory documents the architecture of this Kvaesitso fork and the decisions
behind it. The fork turns Kvaesitso into a **single-page, widget-only launcher** in
the style of the iOS home screen, configured declaratively through dotfiles with hot
reload, and integrated into a GrapheneOS multi-profile setup.

## Principles

1. **One page.** No paging, no app icons on the home surface. Widgets are placed on a
   grid; the dock is the favorites widget on that grid, movable and resizable like
   the rest (ADR 0001, revised 2026-09-22). Everything else is reached through
   search.
2. **Launch apps, host widgets, little else.** Search covers apps, app shortcuts
   and contacts. The clock, weather, calendar, music and notes are standard
   Android widgets, not built-in ones; files belong to a file manager, places to
   a maps app. A feature earns its place in the launcher when its cost is a
   scoped, revocable permission — not when it costs a blanket grant (ADR 0008).
3. **The config file is the source of truth, in both directions.** The complete
   home configuration lives in one JSON document under version control. The
   launcher converges its state towards it — check-before-set, never blind
   writes — and edit mode on the device writes the grid back into the same file
   (ADR 0003 section 5), so the host pulls before it pushes.
4. **Verifiable, not just applied.** Every externally applied change is readable back
   through an exported read-only interface, so provisioning can assert the state it
   intended (convergent steps, not set-once-unverifiable).
5. **Test-driven from day one.** The upstream codebase has effectively no tests; this
   fork is developed AI-assisted and therefore builds its own safety net first:
   pure-logic unit tests, Compose UI tests, and end-to-end tests on the GrapheneOS
   emulator driven by the provisioning repo.
6. **Liquid Glass, unapologetically.** One polished visual direction (iOS-style
   frosted glass, tinted monochrome icons). No half-maintained alternative styles.
7. **GrapheneOS-native.** No Play Services dependencies, no telemetry, storage-scopes
   friendly, per-profile configuration, reproducible builds with pinned signing.

## Decisions

| ADR | Decision |
|---|---|
| [0001](adr/0001-single-page-widget-grid.md) | Single-page widget grid replaces the widget column |
| [0002](adr/0002-config-format-json.md) | JSON (JSONC-tolerant) as the dotfiles config format |
| [0003](adr/0003-config-hot-reload.md) | File-based hot reload with convergence + read-back provider |
| [0004](adr/0004-liquid-glass-design.md) | Liquid Glass as the single visual direction |
| [0005](adr/0005-testing-strategy.md) | Test pyramid and AI-driven development harness |
| [0006](adr/0006-grapheneos-integration.md) | GrapheneOS-specific integration and constraints |
| [0007](adr/0007-fork-strategy.md) | Fork strategy: hard fork, upstream as a source for cherry-picks |
| [0008](adr/0008-launcher-scope.md) | The launcher launches apps and hosts widgets; it is not a search aggregator |

## Implementation order

1. **Test infrastructure** (ADR 0005) — before any feature work, including a
   dedicated emulator instance so tests never interfere with day-to-day use.
2. **Dotfiles config + hot reload** (ADR 0002, 0003) — immediately accelerates
   GrapheneOS provisioning; works against existing settings, dock favorites and
   widgets even before the grid exists. Grid-specific config keys are added later
   as a schema version bump (non-breaking by design, ADR 0002).
3. **Scope reduction** (ADR 0008) — before the grid, not after. The grid has to
   place widgets; if the built-in ones are going away, it only ever needs to
   handle external `AppWidget`s and the dock, which is a smaller job than
   building it twice. Removing providers also shrinks the settings surface the
   grid and the config contract have to cover.
4. **Single-page widget grid** (ADR 0001) — the large piece, landing on top of the
   test net, the config system and a launcher that is no longer carrying what it
   does not use. **Landed 2026-09-22** as a series: coverage gate (#63), layout
   engine `:core:grid` (#64), `HomeGridItem` table (#65), config contract v2
   (#66), renderer (#67), write-back (#68), edit mode (#70); the old widget
   column, the widget pages reached by gestures and their table were removed
   afterwards (PR 5b), so the launcher has exactly one widget surface.
5. **Liquid Glass** (ADR 0004, #24) — **landed 2026-09-23** as a series:
   the contract (#73), one cached backdrop per wallpaper and display (#74),
   glass cards, dock and search pill with labels (#75), the liquid look with
   edge lens and wallpaper blur (#82), Clear icons (#76), and the gate with
   goldens on the reference wallpaper (#77). The contract it added:

   ```jsonc
   "appearance": { "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true, "searchWallpaperBlur": true } },
   "home":       { "grid": { "labels": true } }
   ```

   `appearance.transparency` left the contract and is reported as an inert
   key. Keys, defaults and bounds: ADR 0002, section "Glass"; what was built:
   ADR 0004, "As built".

## Relationship to the provisioning repo

The declarative side lives in `~/Development/andashi/provisioning/config/` (`launcher.json` per
profile/zone, next to `theming.json`). The provisioning flow pushes the file,
triggers a reload, and verifies the result through the read-back provider. The
uiautomator-driven `45-launcher-prefs.sh` is deleted once this lands.

Prior analysis that led here: `~/Development/andashi/provisioning/docs/architecture/launcher.md`
(formerly `docs/kvaesitso-interfaces.md`)
and `~/Development/kvaesitso-patch/EXPORTED-SURFACE-PROPOSAL.md`.
