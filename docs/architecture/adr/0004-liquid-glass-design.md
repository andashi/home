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
| full stack, emulator on the host GPU (`GPU=host`, cold boot, `glass-glass-gpu-host-f364925e0.tsv`) | 57 ms | 48 ms |

| One-time cost, not a frame time | Cover | Inner |
|---|---|---|
| making the blurred backdrop, once per wallpaper and display, off the main thread | 323 ms | 351 ms |

On a software renderer the edge lens is a per-pixel shader on the CPU, and
the inner display is four times the cover's area. That is where the
difference comes from. On a GPU the same shader is a trivial load, but that
was confirmed once the emulator could use the host GPU (andashi/provisioning#4,
2026-09-23). The same stack drops to 57 ms on the cover and 48 ms on the inner
display, with the inner now the faster one. These are still an emulated
radeonsi on a laptop: they tell which build is slower, not whether a Pixel
Fold keeps up. The on-device Fold measurement this ADR asks for is still open.
The blur's time is the debug build's, largely interpreted.

**Search in glass (#91).** Making every surface on the search screen glass
first made the same transition slower on the cover. Measured as above
(`GPU=host`, cold boot, Lawnicons, 15 transitions), series interleaved with
`main` so host drift hits both; `glass-search-gpu-host-<rev>[-runN].tsv`:

| Frame time p50, cover / inner | Series |
|---|---|
| `main` before #91 (8880f6a92) | 36/34, 36/48, 36/40, 36/32, 36/32, 36/32 ms |
| every search surface glass (51c0bbdb3) | 57/48, 57/48, 57/65 ms |
| + the lens drawn only in its ring (ac80b5f6a) | 57/48, 57/48, 57/48 ms |
| + `glassBackdrop` as a modifier node (3e2333777) | 36/32, 36/32 ms |

The frame phases (`dumpsys gfxinfo framestats`, medians of 120 frames on
the cover) located it. Without the lens the glass build drew at 37.7 ms, so
the lens was the cost - but drawing the shader only where it bends changed
nothing, so it was not per pixel. The time sat in the start delay: on this
emulator the UI thread already needs about 18 ms a frame, and every surface
kept its position as Compose state, so each moving surface recomposed and
rebuilt its lens every frame. As a modifier node a moving surface only
redraws; its recomposition phase dropped below `main`'s (7.3 ms against 9.4
ms), the frame time is back at `main`'s, and there are fewer janky frames
(190 and 195 against 248 on the cover). The lens is also drawn only in its
ring now, and surfaces reuse compiled lenses from a pool instead of compiling
the source each: both keep the pixels identical and save work, neither was
the fix.

**The search bar's own position in search (#107, 2026-09-24).** The bar
moves between the home and the search position with the transition, a
vertical bias read at placement only, so the move re-places the bar and does
not recompose it. Interleaved series on test-fold, `GPU=host`, cold boot,
RUNS=15, each build from a pinned worktree (`e2e/measurements/bar-position-*`;
each file records the build's revision and the APK's SHA-256 - the first
recording named the measuring worktree's HEAD, 780a1a777, and was corrected
from the APKs' provenance: main at df1989c26, the branch at 7e2ad7d74):

| Build | Cover: p50 / p90 / p99, janky | Inner: p50 / p90 / p99, janky |
|---|---|---|
| `main` (df1989c26), 3 series | 36 / 113 / 129-150 ms; 153, 156, 153 of about 248 | 32 / 117 / 133 ms; 175, 173, 180 of about 265 |
| #107 (7e2ad7d74), no position keys, 3 series | 36 / 113 / 129 ms; 159, 163, 157 | 32 / 117 / 133 ms; 178, 176, 176 |
| #107, bar bottom on home and top in search (BAR=bottom-top), 2 series | 36 / 113 / 129 ms; 158, 160 | 32 / 117 / 133 ms; 174, 177 |

The frame time is unchanged, also with the bar crossing the screen on every
transition. On the cover the branch had a few more janky frames in each of
the three pairs (about 160 against 154 of 248, some 2 percentage points); the
inner display shows no difference. Emulated numbers, and within the spread of
earlier series here; recorded rather than explained away, to look at again on
a real Pixel Fold.

**The first frame after unfold (#122, 2026-09-24).** On unfold the inner
display stays dark until every visible window has drawn at the new size, so
the launcher's first frame there is on the path to screen-on. Measured on
emulator-5562 (`instances/test-fold-gpu`, `GPU=host` since its first start),
release-like builds, one snapshot per build, the hinge sensor swept 0 to 180
degrees, times from the display-state request (`e2e/measure-unfold.sh`,
`e2e/measurements/unfold-*.tsv`). The GrapheneOS kernel has no usable ftrace,
so there are no atrace sections: the costs were located with simpleperf
(`--clockid monotonic`, as root, profiling only) lined up with `gfxinfo
framestats`, main-thread CPU in the launcher's first frame:

| First frame after unfold, main thread (median) | CPU until the frame is drawn | of it, the draw | whole unfold |
|---|---|---|---|
| `main` (170a1d59b), 6 profiles | 104 ms | 29 ms, 18-20 ms of it the icon chips' rims | 147 ms |
| + the squircle rim stroked (2090b1a32), 6 | 88 ms | 11 ms | 103 ms |
| + hidden pages keep their size through the switch (762f0c5cb), 3 | 44 ms | 4 ms | 107 ms |

- **The rim.** `Modifier.border` builds a generic shape's rim with `Path.op`
  and rasterizes it on the CPU, once per size - and on unfold every dock icon
  changes size. The squircle chip's rim is now its outline stroked, built once
  per size (`glassRimKind`); cards and pills keep the border. Goldens differ
  only along the chips' rims, by anti-aliasing.
- **Hidden pages.** The scaffold keeps search and the other closed pages
  composed and laid out out of the viewport. On unfold the hidden search
  page's app grid gained columns and composed them before the home screen's
  first frame. `OffscreenPages` never draws them and gives them a new window
  size a second after it settles. Two frames later - the first try,
  19ea40319 - was still inside the switch: the relayout invalidated the
  window again, 3-6 launcher frames before screen-on instead of 1-2, and
  screen-on did not move although the frame's work had halved.

Wall clock, interleaved, 12 rounds (4 per series), host load about 0.9:

| Median, ms from the display-state request | launcher's first frame presented | screen on |
|---|---|---|
| `main` (170a1d59b) | 214.5 (series 208 / 211 / 215) | 238.5 (234 / 270 / 223) |
| rim only (2090b1a32) | 177 (177 / 205 / 169) | 210 (197 / 261 / 206) |
| rim + hidden pages settle (762f0c5cb) | 167.5 (167 / 168 / 185) | 190 (199 / 187 / 225) |

The launcher's frame is ready about 47 ms earlier in every series; screen-on,
which also waits for SystemUI and the wallpaper, moved by a median 48 ms, and
one series of three is a tie. Screen recordings (24 fps) agree: the first
visible inner frame was the settled home screen in 12 of 13 unfolds with the
change and 7 of 13 on `main`, where the rest showed the cover's backdrop
stretched or a glyph missing for a frame. Twice on `main` and once with the
change the inner display first showed the cover-sized buffer letterboxed for
4-6 frames - a system behaviour when the dark period is very short, not the
launcher's. Emulated numbers again: they say which build is faster, not how
a Pixel Fold feels.

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
