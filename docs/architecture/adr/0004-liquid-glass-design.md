# 0004: Liquid Glass as the single visual direction

Status: accepted (2026-09-17), revised 2026-09-23 (#24)

## Revision 2026-09-23

The glass epic (#24) settled the questions this ADR left open, before any
code, so nobody re-argues them mid-implementation. Where the sections below
say otherwise, this table wins:

| Question | Decision |
|---|---|
| Icon look | **Clear**: white glyph on a neutral glass chip. The zone color lives in the surfaces' tint, never in the icons. |
| Apps without a monochrome glyph | **Desaturated original on the same chip.** A colored icon never appears on the home screen, in the dock or in search. |
| The old `appearance.transparency` scheme | **Replaced** by `appearance.glass`. A file that still carries it gets a diagnostic and no effect (#73). |
| Muting third-party widgets' colors | **Not in this epic.** Follow-up: #78. |
| Blur strategy | One blurred copy per wallpaper change and per display, cached; every surface draws its region. No per-frame blur, no `haze`, no system cross-window blur. |
| Icon shape | Squircle, fixed, no key. |
| Labels | Under grid items (`home.grid.labels`, default on), never on the dock. |
| Phase 2 (AGSL refraction, motion) | Not started; the reference does not need it. |

The config keys, their defaults and bounds are specified in ADR 0002, section
"Glass". The "Themes" section's `.kvtheme` transport is unaffected; the glass
parameters live in `launcher.json` as it says.

## Context

The reference is the iOS home screen: frosted, translucent widget cards with
rounded corners over a blurred wallpaper, and monochrome/tinted icons. The fork
deliberately does **not** maintain alternative visual styles — one polished path
instead of three mediocre ones. (Upstream theming code stays where it is; the fork
just doesn't ship its own second style.)

Constraints on Android:

- A launcher can only blur its **own** wallpaper as backdrop — there is no
  cross-app backdrop blur. That is sufficient here: the grid floats over the
  wallpaper and nothing else.
- Real-time blur needs API 31+ (`RenderEffect`); the fork targets GrapheneOS only,
  so this is always available (see ADR 0006 on minSdk).

## Decision

### Rendering

- Widget cards and the dock are **glass surfaces**: wallpaper backdrop blur
  (RenderEffect / RenderNode, or the `haze` library if it proves stable on
  GrapheneOS) + translucent tint from the Monet zone color + 1 dp inner highlight +
  subtle top-edge specular gradient. Corner radius iOS-like (~28 dp, configurable).
- Blur radius and tint opacity are config values, not settings-screen sliders.
- Specular/refraction effects beyond blur+tint ("real" Liquid Glass) are phase 2,
  implemented as custom AGSL shaders only if the simple stack falls short visually.

### Icons

- iOS "tinted/clear" look: **monochrome glyph on a frosted tile**. Lawnicons (or
  any themed-icon source) provides the glyph; the fork renders it tinted against a
  glass chip — more extreme minimalism than stock icon packs, matching the reference
  screenshot's muted dock.
- Labels: hidden on the dock, shown under grid widgets (as in the reference) —
  config flag.

### Themes

- The existing `.kvtheme` import (fix patch already prepared in
  `kvaesitso-patch/0001-Fix-crash-when-opening-a-theme-file-from-outside-the.patch`)
  stays the transport for colors/typography; the glass parameters live in
  `launcher.json` so one file fully describes a zone's look (ties into the
  provisioning repo's `themes/` + `theming.json` model).

## Consequences

- Visual regression risk is high (blur is GPU/compose-version sensitive) →
  screenshot tests with a fixed test wallpaper are part of the test strategy
  (ADR 0005).
- Performance on the Pixel Fold must be measured: one blurred backdrop cached per
  wallpaper change, not per frame.
- Accessibility: glass tint must keep contrast within WCAG-ish bounds; a config
  `contrast: low|medium|high` scales blur/tint, not a separate theme.
