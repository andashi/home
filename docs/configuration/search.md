# Search

`search` is how search behaves. What it looks like comes from elsewhere:

- the glass from [`appearance.glass`](appearance.md), including the background
  behind search (`searchWallpaperBlur`);
- the app columns from [`home.grid.columns`](home-grid.md);
- the position of the bar from [`home.searchBar`](search-bar.md).

<!-- config -->
```json
{
  "schemaVersion": 2,
  "search": {
    "favorites": true,
    "allApps": true,
    "layout": "grid",
    "labels": true,
    "contacts": true,
    "shortcuts": true,
    "filterBar": true,
    "openKeyboard": true,
    "launchOnEnter": true,
    "reversed": false,
    "hiddenItemsButton": false
  }
}
```

| Key | What it does | Accepted | Default |
|---|---|---|---|
| `search.favorites` | The favorites row at the top of search, with its tag chips. It is not shown while there are no favorites, no pinned tags and no selected tag | boolean | `true` |
| `search.allApps` | All apps while the query is empty; off, an empty query shows only favorites | boolean | `true` |
| `search.layout` | App results as icons in the home grid's columns, or as a list | `grid`, `list` | `grid` |
| `search.labels` | Labels under app icons in search (the dock never has labels) | boolean | `true` |
| `search.contacts` | Contacts in the results. It grants no permission: without it, search shows a banner that asks | boolean | `true` |
| `search.shortcuts` | App shortcuts in the results | boolean | `true` |
| `search.filterBar` | The filter bar above the keyboard (apps, shortcuts, contacts) | boolean | `true` |
| `search.openKeyboard` | The keyboard opens when search opens | boolean | `true` |
| `search.launchOnEnter` | Enter on the keyboard launches the best match | boolean | `true` |
| `search.reversed` | Results from the bottom up, the best match nearest a bottom search bar | boolean | `false` |
| `search.hiddenItemsButton` | A button in the search bar that shows hidden items | boolean | `false` |
| `search.barPosition` | Where the search bar sits while search is open. Absent, it follows [`home.searchBar.position`](search-bar.md) | `top`, `bottom` | follows the home position |

The defaults are the launcher's behavior before this section existed, so a
file without `search` changes nothing. A key that is left out stays as it is
on the device. The read-back always serves every key, except
`search.barPosition`, which it serves once a config has set it.

## The search bar in open search

With `home.searchBar.position: bottom`, the bar sits where the thumb is on the
home screen. With the keyboard open, it then sits in the middle of the screen,
the best match at the very top and half the screen between them.
`search.barPosition: top` moves the bar to the top while search is open, next
to the results:

<!-- config -->
```json
{ "schemaVersion": 2, "home": { "searchBar": { "position": "bottom" } }, "search": { "barPosition": "top" } }
```

The bar moves between the two positions with the search transition, so the
eye can follow it, and back when search closes; the search-action chips move
with it. `search.reversed: true` together with `barPosition: top` is applied,
with a `search-reversed-with-top-bar` warning: reversed results put the best
match the farthest from a bar at the top. On the Fold the bar keeps spanning
both panes of the inner display.

## A list instead of icons

<!-- config -->
```json
{ "schemaVersion": 2, "search": { "layout": "list" } }
```

| Phone | Fold, cover | Fold, inner |
|---|---|---|
| <img alt="search as a list, phone" src="img/search-list-phone.jpg" width="200"> | <img alt="search as a list, Fold cover" src="img/search-list-fold-cover.jpg" width="200"> | <img alt="search as a list, Fold inner display" src="img/search-list-fold-inner.jpg" width="300"> |

## Without the favorites row

<!-- config -->
```json
{ "schemaVersion": 2, "search": { "favorites": false } }
```

| Phone | Fold, cover | Fold, inner |
|---|---|---|
| <img alt="search without favorites, phone" src="img/search-no-favorites-phone.jpg" width="200"> | <img alt="search without favorites, Fold cover" src="img/search-no-favorites-fold-cover.jpg" width="200"> | <img alt="search without favorites, Fold inner display" src="img/search-no-favorites-fold-inner.jpg" width="300"> |

The favorites row shows while the query is empty, so these pictures are of
search opened without typing.

`search.favorites: false` turns the row off for good. With it on, the row is
hidden only while all three are absent: no favorites (nothing pinned or used
often yet), no pinned tags and no selected tag. Search then starts with the
apps. A favorite or a pinned tag brings the row back; a selected tag keeps it,
showing "There are no items with this tag" when the tag is empty, so there is
a way back.

## On the Fold

On the cover, search uses the home grid's four columns. On the inner display
it has two panes that meet at the fold line: favorites and apps in the half
the cover shows (the right one), at the home grid's pitch, and shortcuts,
contacts and the filters in the left half. Nothing crosses the hinge.
