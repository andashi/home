# 0001: Single-page widget grid replaces the widget column

Status: accepted (2026-09-17)

## Context

Stock Kvaesitso renders home widgets in a `WidgetColumn`: a vertical, scrollable
list. The persistence model (`data/widgets/Widget.kt`) is a linear order —
`position: Int` within a `parentId`. There are no cell coordinates, no spans, no
pages, and no dock concept; favorites are just one widget among others.

The target design (iOS home screen, reduced to a single page) needs:

- a fixed grid (configurable columns, e.g. 4) on **one** page — no paging
- widgets (internal Kvaesitso widgets and standard Android `AppWidget`s) placed at
  grid coordinates with spans (`x, y, spanX, spanY`)
- **no app icons on the grid** — the only icons are the dock favorites
- a dock: a fixed band at the bottom holding a small, ordered set of favorites
- everything else reachable via search / app drawer (narrowed by ADR 0008: the
  search reaches apps, app shortcuts and contacts, not stock Kvaesitso's full
  set of providers)

Upstream closed "app icons on the desktop" as *not planned* (MM2-0/Kvaesitso#1943),
so this cannot land upstream and defines the fork's core divergence.

## Decision

Introduce a new home surface, `HomeGrid`, next to the existing scaffold rather than
rewriting it in place (see ADR 0007 for why this limits merge conflicts).

Data model:

```kotlin
sealed class HomeGridItem {
    abstract val id: UUID
    abstract val x: Int       // column, 0-based
    abstract val y: Int       // row, 0-based
    abstract val spanX: Int
    abstract val spanY: Int

    data class InternalWidget(val widget: Widget, ...) : HomeGridItem()
    data class ExternalAppWidget(val providerComponent: ..., ...) : HomeGridItem()
}

data class Dock(val favorites: List<String>)  // ordered searchable keys
```

- Grid dimensions come from config (`columns`, `rows`), not from device heuristics.
- **Form factors:** the fork supports candybar Pixels and the Pixel Fold (cover +
  inner display, ADR 0006). The config therefore allows **per-form-factor grid
  definitions** (e.g. `grid.phone`, `grid.foldInner`, `grid.foldCover`), each with
  its own dimensions and item placements. Items missing from the active form
  factor's definition are simply not shown there — no automatic reflow between
  form factors, so every screen is exactly what the dotfiles say it is. Rotation
  within one display keeps the same grid (clamped if the aspect flips).
- Placement is free; collisions are resolved by **pushing the displaced item down to
  the next free position** (deterministic, easy to test). Swap-on-drop is explicitly
  rejected for v1: it makes config-driven state non-obvious.
- The grid does not scroll in v1. If content exceeds the grid, that is a config
  error surfaced in verification, not a UI mode.
- Edit mode: long-press enters wiggle/edit mode with drag & drop between cells and
  resize handles for AppWidgets. UI edits are written back through the same
  convergence path as config edits (ADR 0003) so both stay in one model.

Migration from the stock model: every existing `WidgetColumn` entry becomes a
full-width grid item (`spanX = columns`), stacked in order (`y` = running offset).
One-way, tested with golden migration tests.

## Consequences

- `data/database` gains grid columns; a migration ships with the fork.
- `AppWidgetHost` binding logic is reused, only the layout container changes.
- The pure layout engine (placement, collision, reflow) is isolated in a headless
  module — it is the fork's most-tested component (ADR 0005).
- The old `WidgetColumn` code path stays compiled-in but unreachable when the home
  grid is active; it can be removed once the fork stops tracking upstream releases
  for the scaffold.
