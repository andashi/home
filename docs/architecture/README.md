# Architecture

This directory documents the architecture of this Kvaesitso fork and the decisions
behind it. The fork turns Kvaesitso into a **single-page, widget-only launcher** in
the style of the iOS home screen, configured declaratively through dotfiles with hot
reload, and integrated into a GrapheneOS multi-profile setup.

## Principles

1. **One page.** No paging, no app icons on the home surface. Widgets are placed on a
   grid; a dock holds a handful of favorites. Everything else is reached through
   search / the app drawer, exactly as in stock Kvaesitso.
2. **Dotfiles are the source of truth.** The complete home configuration lives in one
   JSON document under version control. The launcher converges its state towards it —
   check-before-set, never blind writes. UI editing remains possible, but the config
   file wins on reload.
3. **Verifiable, not just applied.** Every externally applied change is readable back
   through an exported read-only interface, so provisioning can assert the state it
   intended (convergent steps, not set-once-unverifiable).
4. **Test-driven from day one.** The upstream codebase has effectively no tests; this
   fork is developed AI-assisted and therefore builds its own safety net first:
   pure-logic unit tests, Compose UI tests, and end-to-end tests on the GrapheneOS
   emulator driven by the provisioning repo.
5. **Liquid Glass, unapologetically.** One polished visual direction (iOS-style
   frosted glass, tinted monochrome icons). No half-maintained alternative styles.
6. **GrapheneOS-native.** No Play Services dependencies, no telemetry, storage-scopes
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

## Measurements

Findings measured on a device rather than derived, kept where the code they explain
lives. They exist because the behaviour has no test yet; each one names what it does
not cover.

| | Finding |
|---|---|
| [clock-dock-layout](clock-dock-layout.md) | Why exactly one pinned favorite clipped the clock, with the before/after dumps |

## Implementation order

1. **Test infrastructure** (ADR 0005) — before any feature work, including a
   dedicated emulator instance so tests never interfere with day-to-day use.
2. **Dotfiles config + hot reload** (ADR 0002, 0003) — immediately accelerates
   GrapheneOS provisioning; works against existing settings, dock favorites and
   widgets even before the grid exists. Grid-specific config keys are added later
   as a schema version bump (non-breaking by design, ADR 0002).
3. **Single-page widget grid** (ADR 0001) — the large piece, landing on top of the
   test net and the config system.
4. **Liquid Glass refinement** (ADR 0004) — iterated on top of a stable grid.

## Relationship to the provisioning repo

The declarative side lives in `~/Development/andashi/provisioning/config/` (`launcher.json` per
profile/zone, next to `theming.json`). The provisioning flow pushes the file,
triggers a reload, and verifies the result through the read-back provider. The
uiautomator-driven `45-launcher-prefs.sh` is deleted once this lands.

Prior analysis that led here: `~/Development/andashi/provisioning/docs/architecture/launcher.md`
(formerly `docs/kvaesitso-interfaces.md`)
and `~/Development/kvaesitso-patch/EXPORTED-SURFACE-PROPOSAL.md`.
