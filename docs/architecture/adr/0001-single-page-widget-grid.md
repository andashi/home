# 0001: Single-page widget grid replaces the widget column

Status: accepted (2026-09-17), revised 2026-09-22

Revision 2026-09-22 (#23): the dock is no longer a fixed band but the favorites
widget placed on the grid; rows are derived from the screen; the Fold uses one
layout twice as wide as the cover; items carry stable ids; edit mode writes back
into the config file. The per-form-factor `foldCover`/`foldInner` split and the
`Dock` type are withdrawn. The Context below is left as written on 2026-09-17;
the Decision is the revised one. This closes #46.

## Context

Stock Kvaesitso renders home widgets in a `WidgetColumn`: a vertical, scrollable
list. The persistence model (`data/widgets/Widget.kt`) is a linear order —
`position: Int` within a `parentId`. There are no cell coordinates, no spans, no
pages, and no dock concept; favorites are just one widget among others.

The target design (iOS home screen, reduced to a single page) needs:

- a fixed grid (configurable columns, e.g. 4) on **one** page — no paging
- widgets placed at grid coordinates with spans (`x, y, spanX, spanY`). Written
  when the fork still had the stock set of internal widgets; ADR 0008 keeps only
  the favourites widget and the `AppWidget` adapter, so `HomeGridItem` still
  needs both cases below — favourites is itself an internal widget — but the
  grid will hold one internal type, not a family of them
- **no app icons on the grid** — the only icons are the dock favorites
- a dock: a fixed band at the bottom holding a small, ordered set of favorites
- everything else reachable via search / app drawer (narrowed by ADR 0008: the
  search reaches apps, app shortcuts and contacts, not stock Kvaesitso's full
  set of providers)

Upstream closed "app icons on the desktop" as *not planned* (MM2-0/Kvaesitso#1943),
so this cannot land upstream and defines the fork's core divergence.

## Decision

Introduce a new home surface, `HomeGrid`, next to the existing scaffold rather than
rewriting it in place (see ADR 0007 for why this limits merge conflicts). It
replaced `WidgetsHomeComponent` as the home page in #67. The old widget
column, the widget pages reached by gestures and their `Widget` table were
removed afterwards (PR 5b, decided 2026-09-22): the launcher has one page.

### Geometry (D1)

- **Four columns, square cells, rows derived from the screen.** The config names
  the column count (`home.grid.columns`, default 4); the launcher derives the
  cell size from the usable width and the row count from the usable height. A
  configured row count that did not fit would either overflow or squash cells
  out of square, so rows are not configured. The reference screenshot is this
  shape: 2x2 and 4x2 widgets over four icon columns.
- The grid does not scroll. Items that do not fit are dropped with a diagnostic
  (`grid-overflow`, `grid-out-of-bounds`), never silently.

### The dock is the favorites widget (D2)

- **There is no dock element.** The favorites widget, the one internal widget that
  survived ADR 0008, is a grid item like any other. Its layout follows its span:
  one row tall it is a strip of icons side by side (the iOS dock), one column
  wide it is a vertical strip at the edge, taller it is an icon grid. It is
  placed by default in the bottom row, full width. Being internal, none of the
  Android widget sizing limits apply to it.
- **One pin list**, `home.favorites` (ADR 0002), shared by the search screen and
  the favorites widget. `home.dock` left the contract with schema version 2.
  More favorites than `w * h` cells are clipped; the rest stay reachable in
  search. Several favorites widgets with their own lists are a later, additive
  extension (a per-item `apps` list).

### Items, ids and the contract (D5)

- Every item carries a **stable `id`**; write-back and the database match on it,
  never on array position. The AppWidget host's integer id is device-local and
  lives only in the `HomeGridItem` table, keyed by that id, which is what makes
  the same file valid on a second device.
- AppWidgets are addressed by provider component (`pkg/cls`) and bound by the
  launcher on reload. **Measured 2026-09-22:** the HOME role does not carry the
  bind-widget grant; only the system's bind dialog ("always allow") or the shell
  (`appwidget grantbind --package <applicationId> --user <N>`) gives it.
  Provisioning runs the shell command per profile (andashi/provisioning#2); a
  cell whose provider could not be bound offers "Allow", which opens the dialog.
- Geometry may be omitted once: the launcher places the item at the first free
  cells in reading order and writes the geometry back. The full contract is the
  example document in ADR 0002.

### Collisions, resizing (D4)

- Placement is free; collisions are resolved by **pushing the displaced item down
  to the next free row at or below its own**, in its own column band,
  cascading, deterministic (sorted by row, column, id). This holds for a hand
  move and for a config that names overlapping items alike. Swap-on-drop stays
  rejected. A displaced item that runs out of rows rejects the move
  (`Overflow`).
- Resizing honours the provider's declared limits: the default span comes from
  `minWidth`/`minHeight`, the smallest from `minResizeWidth`/`minResizeHeight`,
  the largest from `maxResize*`, converted to cells the way Launcher3 does
  (rounded up, the larger of portrait and landscape). A config that asks for
  less gets `widget-too-small` and the minimum. Scaling a widget below its
  minimum is a knowingly non-contractual mode and stays a later per-item opt-in.
  Android enforces nothing here; the minimum is a convention, and Launcher3's
  policy is what every user knows as "how widgets behave".

### The Fold (D7)

- **One layout, twice as wide.** On a foldable the grid is `2 * columns` wide
  (8 on the Pixel 10 Pro Fold); the inner display shows all of it, the cover
  display renders columns `0 .. columns-1`. Everything in the left half is on
  both displays; everything in the right half is inner-only, exactly like the
  second page on a Pixel or the iPhone Duo's second home page. There is no
  separate inner layout; every item has one position and size, the same folded
  and unfolded (Apple's relocating dock is deliberately not reproduced).
- Items may not cross the fold line, except the favorites widget, which may span
  all columns and shows its cover half folded. A config that asks for a crossing
  gets `grid-crosses-fold` and the item is nudged to one side.
- **Amended 2026-09-24 (#93): the cover is the right half.** The cover renders
  columns `columns .. 2*columns-1` (4 to 7), not the left half: on the Pixel
  Fold the right half of the inner display is the part that stays in the hand,
  so what is on the cover stays in the same physical place when the device
  opens, and opening adds a screen on the left. Everything in the right half
  is on both displays; the left half is inner-only. The default fold dock is
  the right edge column (`x 7, y 0, w 1`, full height), the one edge that is
  an edge in both states. Stored layouts were not migrated; provisioning
  mirrored its fold layouts in the same release.
- Half-folded and landscape are treated as fully open; rotation keeps the grid,
  rows clamped. A foldable is detected by the hinge feature or by two built-in
  displays (the GrapheneOS emulator instance has the displays but not the
  feature).

### Edit mode and write-back (D3)

- Long-press enters edit mode: drag between cells with push-down visible, resize
  handles plus +/- per axis, remove with undo, add through the widget picker, the
  favorites list edited in place. `home.grid.locked: true` disables it.
- **The config file is the source of truth in both directions.** Leaving edit
  mode writes `home.grid` back into `launcher.json` on the device, replacing only
  that object's text so comments elsewhere survive byte for byte; a changed file
  changes the layout on reload. The mechanics, the self-write rule of the file
  watcher and the pull-before-push rule for provisioning are ADR 0003 section 5.

There is no migration from the stock model. The fork never had a stable
release whose widget column would need carrying over, so the seeder that
converted it was withdrawn with the column (PR 5b). The grid has exactly one
default: a launcher that starts without any config gets the favorites widget
in the bottom row, full width, once (`HomeGridDefaults`); after that, and
after any config that applied `home.grid`, an empty layout means empty.

## Consequences

- `data/database` gains the `HomeGridItem` table (migration 35 -> 36, #65) and
  loses the `Widget` table (migration 36 -> 37, PR 5b) together with the
  `data/widgets` module; the AppWidget picker keeps only the provider list.
- The pure layout engine is `:core:grid` (#64): placement, push-down, clamping,
  fold rule, cover clamp, all pure Kotlin with property tests; it is the fork's
  most-tested component (99 % line coverage gate, ADR 0005).
- `AppWidgetHost` binding logic is reused; the layout container is a single
  `Layout` with a custom measure policy (#67), no lazy grid, host views created
  once and told their size only when it changes.
- The config contract moved to schema version 2 (#66): `home.favorites`,
  `home.grid`, no `home.dock`, no `home.widgets.widgets`; version 1 files are
  migrated on read.
- Write-back (#68) makes the on-device file the last agreed state between host
  and device (ADR 0002, ADR 0003).
- The old `WidgetColumn` code path is gone (PR 5b): with the widget pages
  removed there is no secondary surface left that would draw it.
