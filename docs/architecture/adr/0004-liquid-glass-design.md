# 0004: Liquid Glass as the single visual direction

Status: accepted, revised (2026-09-17; revised and built 2026-09-23, #24)

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
| Phase 2 (AGSL refraction, motion) | Edge refraction started after the first screenshots read frosted, not liquid (#82, decided with Dob); motion stays out. |

The config keys, their defaults and bounds are specified in ADR 0002, section
"Glass". The "Themes" section's `.kvtheme` transport is unaffected; the glass
parameters live in `launcher.json` as it says.

## As built (2026-09-23, #73–#77, #82)

What the epic shipped, in the order it is drawn:

1. **Backdrop** (#74). The config-managed wallpaper file is the only source:
   the system wallpaper is not readable without a privileged permission, and
   asking for broad storage access would break least privilege. Without a
   managed home wallpaper there is no backdrop and every surface is tint
   only. The image is center-cropped like the system crops a wallpaper set
   without a crop hint, area-averaged down to 1/8 of the window and
   box-blurred (three passes) on the CPU in `:core:glass`, keyed by
   wallpaper hash, window size and blur. An LRU of three (phone, fold cover,
   fold inner) means folding does not re-blur. The blur runs once per key,
   never per frame (`GlassBackdropTest`, `BackdropPipelineTest`).
2. **Home background** (#82). With `appearance.glass.wallpaperBlur` (default
   on) the same bitmap is drawn full-window behind the scaffold, so the
   whole screen is soft as in the reference.
3. **Surface** (#75, #82). `GlassSurface`, one composable for cards, the dock,
   the search pill and icon chips:
   - the backdrop region under it, through the AGSL edge lens: pulled inwards
     along the rounded rectangle's normal within 18 dp, up to 10 dp. The lens
     runs only on the hardware renderer; elsewhere the plain region is drawn.
   - the zone's surface color at the tint (default 0.12, 0.35 before #82);
   - the 12 % scrim on `contrast: high`;
   - a 1.25 dp rim as a sweep gradient, bright at the top-left, weaker at the
     bottom-right, faint on every side;
   - a 24 dp top-edge specular.
4. **Icons** (#76). Inside the launcher's scaffold every icon is *Clear*: a
   glass chip on the squircle, tinted 0.08 stronger than cards. On it goes
   the white glyph (a monochrome layer, a Lawnicons entry, clocks, the
   themed placeholder) or, without one, the desaturated original, including
   the silhouette `icons.enforceThemed` forced out of it. The settings
   screens keep upstream's icons.
5. **Labels** (#75). Under every grid item but the dock: the app's name, as
   the reference shows (Kalender, not the widget's own title).

The contract keys, defaults and bounds are in ADR 0002, section "Glass". The
look's constants without a key (rim, lens, chip boost, contrast factors) are
in `:core:glass` (`GlassLook`, `GlassStyle`).

**Measured on the foldable emulator** (`e2e/measurements/`). The emulator
renders in software (SwiftShader, andashi/provisioning#4), so only deltas
mean anything:

| Frame time p50 (15 transitions home → search → home, 8 surfaces) | Cover 1080×2364 | Inner 2076×2152 |
|---|---|---|
| cards without glass (#74, `glass-off-hook-c33378d24.tsv`) | 93 ms | 133 ms |
| + backdrop region on every card (#74, same file) | 113 ms | 150 ms |
| full stack: lens, rim, tint, wallpaper blur (#77, `glass-glass-a86be0b4d.tsv`) | 150 ms | 300 ms |

| One-time cost, not a frame time | Cover | Inner |
|---|---|---|
| making the blurred backdrop, once per wallpaper and display, off the main thread | 323 ms | 351 ms |

On a software renderer the edge lens is a per-pixel shader on the CPU, and
the inner display is four times the cover's area. That is where the
difference comes from. On a GPU the same shader is a trivial load, but that
has not been measured yet: the numbers with `GPU=host` come with
andashi/provisioning#4. The blur's time is the debug build's, largely
interpreted.

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
