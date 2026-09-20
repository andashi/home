# Why exactly one pinned favorite clipped the clock

The measurement behind the layout fixes in #33, kept because the fixes have no test
yet. Until the screenshot layer covers this case, this file and the dumps beside it
are the only evidence that the change did what it claims.

Measured on a 1080x1920 emulator at density 480, against `MM2-0/Kvaesitso` @
`37cff4e` — the code this fork was taken from, before the fix.

## The symptom

The Default (vertical) clock rendered **clipped** in a zone with exactly **one**
pinned favorite, and rendered clean with zero, two or three. Nothing in the
configuration said so, and no error appeared anywhere.

## The chain

1. **`FavoritesPartProvider`** renders the dock grid with
   `columns = columns.coerceAtMost(favorites.size)`. With one favorite the grid
   collapses to a single column, whatever the configured column count is.
2. **`SearchResultGrid`** lays out each cell as `GridItem(Modifier.weight(1f))`.
   One column means that single cell takes the entire row width.
3. **`GridItem`** applies `Modifier.aspectRatio(1f)` when `showLabels` is false,
   and the dock passes `showLabels = false`. The cell is therefore **square**: its
   height follows its width.
4. At n=1 the dock claims a square of the full column width — 1032 px on a 1080 px
   screen. Clock and dock are siblings competing for height in the same column
   (`ClockWidget`, `fillScreenHeight`), so the clock's box collapses from the
   616 px it wants to 432 px.
5. **`DigitalClock1`** had no `maxLines`, no `overflow`, no `softWrap` and no
   autosizing, so the default `TextOverflow.Clip` applied: at 100.sp in a 432 px
   box the second line — the minutes — was simply cut.

n=2 gives two columns, cells of half the width, a 528 px dock, and the clock gets
its 616 px back. n=0 renders no dock at all. Both clean, which is why only one zone
ever showed it.

## The measurement

`evidence/clock-dock/unpatched.{png,xml}` against `patched.{png,xml}`: same device,
same zone, same hour, one pinned item on both sides, the launcher's settings aligned
beforehand so that only the code differs.

| | unpatched | patched |
|---|---|---|
| dock container | 1056x1056 | **246x246** |
| inner cell | 1032x1032 | **222x222** (around a 168 px icon) |
| clock box | 432 | **616** |
| clock text node | 408 | **616** |
| battery row | *absent* | `100%` · `Charging` |

The clock goes to its natural height, not a smaller one: where there is room, the
autosize upper bound changes nothing. (The text *width* differs, 364 against 382,
only because the captures are five minutes apart and `1` and `4` are not the same
width. It is not a font-size change.)

`one-favorite.{png,xml}` against `two-favorites.{png,xml}` is the pair that
identified the trigger before any fix existed: same zone, same settings, differing
by one pinned icon.

## The second victim nobody predicted

In the unpatched capture the battery row is not clipped — it is **gone**. No `100%`,
no `Charging` text node anywhere in the dump. The clock's column holds the time *and*
the parts below it, so when the dock starved that column the parts were squeezed out
entirely rather than truncated. Neither the source analysis nor the prediction caught
this; it surfaced only in the after-dump, where both text nodes reappear.

The clipped clock was the visible symptom. A silently missing battery indicator was
the invisible one.

## There was no configuration lever

`dockRows` looks like the obvious escape and is not: it feeds only
`limit = columns * dockRows`, the maximum number of items displayed. It does not
influence the column count, which the `coerceAtMost` in step 1 fixes at 1. Raising
the global column count does not help for the same reason.

At configuration level the only ways out were: never exactly one favorite in a zone
with the dock enabled, disable the dock there, use the Compact layout (48.sp instead
of 100.sp), or set the watch face to `No clock`.

## What was fixed, and what was not

Two separate failures, two commits, both in #33:

- **The dock** now bounds its grid to the width its items occupy at the configured
  column count and centres it, so a cell never exceeds its natural size. It touches
  the dock only — `SearchResultGrid` and `GridItem` are shared with search results,
  where square cells at the configured column count are correct.
- **The clock** passes `TextAutoSize.StepBased` with the previous fixed size as the
  *upper* bound, so nothing changes where there is room and the digits shrink instead
  of being cut where there is not.

The second does not depend on the first, and it is the stronger of the two: whatever
the dock does, a clock that silently slices its minute digits rather than scaling is
wrong, and it cannot defend itself no matter where the pressure comes from — dock,
widgets, font scale, or a shorter screen. It is also why this cost a day of
misdiagnosis. A layout that degrades would have pointed straight at space as the
cause.

**Not fixed:** with seconds shown, time and seconds size themselves against their own
bounds, so the clipping can move to the seconds rather than disappear — #34.

## What this measurement does not cover

Every zone other than the one measured, any screen geometry other than 1080x1920 at
density 480, and the seconds case above. The falsification criterion set in advance —
the clock rendering *smaller* than unpatched in a zone that had room — did not occur.

## Why provisioning cares

A zone declaring exactly one favorite was predicted to clip on real hardware. The
case that exposed it was more interesting than a plain misconfiguration: two
favorites were declared, one of them an app absent from the emulator, so the
*effective* count was one. The clipped clock was a symptom of the unpinned favorite
that the provisioning step had already reported as a warning.

That is the argument for resolving favorites strictly: an unpinned favorite does not
just leave an icon missing, it can change the layout of an unrelated widget. The
provisioning generator now treats an unresolved favorite as fatal for that reason.
