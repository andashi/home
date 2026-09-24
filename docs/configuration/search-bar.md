# Search bar

The search bar is a glass pill. Tapping it opens search: apps, app shortcuts
and contacts. It is configured under `home.searchBar`.

<!-- config -->
```json
{ "schemaVersion": 2, "home": { "searchBar": { "position": "bottom" } } }
```

| Key | What it does | Accepted | Default |
|---|---|---|---|
| `home.searchBar.position` | Where the pill sits | `top`, `bottom` | `top` |

| `top` | `bottom` |
|---|---|
| <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="220"> | <img alt="search bottom, phone" src="img/search-bottom-phone.jpg" width="220"> |

## Search open

| Phone | Fold, cover | Fold, inner |
|---|---|---|
| <img alt="search open, phone" src="img/search-open-phone.jpg" width="200"> | <img alt="search open, Fold cover" src="img/search-open-fold-cover.jpg" width="200"> | <img alt="search open, Fold inner display" src="img/search-open-fold-inner.jpg" width="300"> |

Search is drawn in the same glass as the home screen: the pill, every result
card, chip, menu, the keyboard's filter bar and the hidden-items sheet follow
`appearance.glass`, and every icon follows `icons`. The background behind
search has its own setting, `appearance.glass.searchWallpaperBlur` (see
[appearance](appearance.md#behind-search)).

Not yet (#91, next): the number of app columns does not follow
`home.grid.columns`, and on the Fold's inner display search runs full width
across the hinge instead of two panes at the seam.
