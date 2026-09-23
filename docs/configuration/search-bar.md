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

**Search is not consistent with the home screen yet** (#91). The pill and
every icon follow `appearance.glass` and `icons`, but:
- the result cards and chips are opaque, and glass settings do not reach them;
- the wallpaper behind search is washed out rather than blurred;
- the number of app columns does not follow `home.grid.columns`;
- on the Fold's inner display search runs full width across the hinge.

#91 makes search glass, gives it the home grid's columns and splits it into
two panes at the Fold's seam.
