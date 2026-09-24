# Home grid

The home screen is one page: a grid of cells that holds widgets and the dock.
There are no app icons on it and no second page; everything else is one tap
away in search. Design background:
[ADR 0001](../architecture/adr/0001-single-page-widget-grid.md).

<!-- config -->
```json
{
  "schemaVersion": 2,
  "home": {
    "widgets": { "enabled": true },
    "grid": { "columns": 4, "locked": false, "labels": true }
  }
}
```

| Key | What it does | Accepted | Default |
|---|---|---|---|
| `home.widgets` | Holds the grid's master switch | object | — |
| `home.widgets.enabled` | Switches the grid on. Off, the home screen shows only the search bar | boolean | device setting |
| `home.grid.columns` | Columns of the grid on a phone and on the Fold's cover. The Fold's inner display has twice as many | 2–8 | 4 |
| `home.grid.locked` | No edit mode on the device, so nothing is ever written back into the file | boolean | `false` |
| `home.grid.labels` | A label under every widget, never under the dock. It shows the app's name | boolean | `true` |
| `home.grid.layouts` | The layouts, one per form factor (below) | `phone`, `fold` | — |

Rows are not configured. The launcher derives them from the screen, so a cell
stays square: the phone instance these screenshots come from has 6 rows, the
Pixel Fold has 7 on both displays. `y` is absolute, so each layout puts its
bottom row (a bottom dock, say) at its own device's last row.

| Labels on (default) | `labels: false` |
|---|---|
| <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="220"> | <img alt="labels off, phone" src="img/labels-off-phone.jpg" width="220"> |

## Layouts: phone and fold

`home.grid.layouts` has up to two entries:
- `home.grid.layouts.phone`: used by phones.
- `home.grid.layouts.fold`: used by foldables. It is twice as wide (8
  columns with the default 4). The inner display shows all of it; **the cover
  shows columns 0 to 3 only.**

A device uses exactly one of them. A layout the file does not name is left as
it is on the device. A layout key other than `phone` or `fold` is reported
(`unknown-layout`) and ignored.

Each layout is `home.grid.layouts.<layout>.items`, a list of up to 32 items:

| Key | What it does | Accepted |
|---|---|---|
| `home.grid.layouts.<layout>.items[].id` | A stable name for the item; write-back and edit mode match on it | lowercase letters, digits, `-`; starts with a letter or digit; up to 32; unique in the layout |
| `home.grid.layouts.<layout>.items[].widget` | `favorites` for the dock, or a widget provider as `package/class` | |
| `home.grid.layouts.<layout>.items[].x` | Column of the top-left cell, from 0 | 0 to 64 |
| `home.grid.layouts.<layout>.items[].y` | Row of the top-left cell, from 0 | 0 to 64 |
| `home.grid.layouts.<layout>.items[].w` | Width in cells | 1 to 64 |
| `home.grid.layouts.<layout>.items[].h` | Height in cells | 1 to 64 |
| `home.grid.layouts.<layout>.items[].profile` | Which profile's widget: `personal`, `work`, `private` | enum, default `personal` |
| `home.grid.layouts.<layout>.items[].borderless` | Draw the widget without the card's padding | boolean |
| `home.grid.layouts.<layout>.items[].background` | `false`: no glass surface behind the widget at all | boolean, default `true` |
| `home.grid.layouts.<layout>.items[].themeColors` | Hand the widget the zone's Material You colors | boolean |

**Geometry can be left out once.** An item without `x`, `y`, `w`, `h` is
placed at the first free cells with the widget's default size, and write-back
puts the geometry into the file. A position is `x` and `y` together; a lone
coordinate is ignored with a `partial-grid-position` warning. The launcher
keeps a layout valid on the actual screen:
- a size below the widget's minimum is enlarged (`widget-too-small`);
- an item across the Fold's middle is nudged (`grid-crosses-fold`);
- what does not fit is dropped (`grid-overflow`, `grid-out-of-bounds`).

Each correction is reported, and the file is still applied.

Widgets are named by their provider component. To find one, place it in edit
mode and read the file back, or run `adb shell dumpsys appwidget`.

## The dock

The dock is not a special bar. It is **the favorites widget on the grid**: an
item with `"widget": "favorites"`, moved and sized like any other. It shows the
apps from [`home.favorites`](favorites.md), filling its cells row by row, and
never has a label. Its shape is your choice:

| Where | Item |
|---|---|
| At the bottom, the traditional dock | `{ "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 }` (a 6-row phone; `"y": 6, "w": 8` on the Fold) |
| A column on the side | `{ "id": "dock", "widget": "favorites", "x": 3, "y": 0, "w": 1, "h": 6 }` (`"h": 7` on the Fold) |
| Two rows | `{ "id": "dock", "widget": "favorites", "x": 0, "y": 4, "w": 4, "h": 2 }` |
| None | leave the item out |

A dock of `w` × `h` cells shows `w × h` favorites. The rest stay reachable
through search, and edit mode shows how many do not fit.

### A screen full of widgets, the dock at the bottom

| Phone | Fold, cover | Fold, inner |
|---|---|---|
| <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="200"> | <img alt="full dock bottom, Fold cover" src="img/full-dock-bottom-fold-cover.jpg" width="200"> | <img alt="full dock bottom, Fold inner display" src="img/full-dock-bottom-fold-inner.jpg" width="300"> |

<!-- config -->
```json
{
  "schemaVersion": 2,
  "home": {
    "favorites": ["com.android.dialer", "com.android.messaging", "app.vanadium.browser", "app.grapheneos.camera", "com.android.contacts", "com.android.settings"],
    "grid": {
      "columns": 4,
      "labels": true,
      "layouts": {
        "phone": { "items": [
          { "id": "analog", "widget": "com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider", "x": 0, "y": 0, "w": 2, "h": 2 },
          { "id": "messages", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 2, "y": 0, "w": 2, "h": 2 },
          { "id": "search", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 0, "y": 2, "w": 4, "h": 1 },
          { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 3, "w": 2, "h": 1 },
          { "id": "bookmarks", "widget": "app.vanadium.browser/com.google.android.apps.chrome.appwidget.bookmarks.BookmarkThumbnailWidgetProvider", "x": 2, "y": 3, "w": 2, "h": 2 },
          { "id": "clock-2", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 4, "w": 2, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 0, "y": 5, "w": 4, "h": 1 }
        ] },
        "fold": { "items": [
          { "id": "analog", "widget": "com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider", "x": 0, "y": 0, "w": 2, "h": 2 },
          { "id": "messages", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 2, "y": 0, "w": 2, "h": 2 },
          { "id": "search", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 0, "y": 2, "w": 4, "h": 1 },
          { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 3, "w": 2, "h": 1 },
          { "id": "bookmarks", "widget": "app.vanadium.browser/com.google.android.apps.chrome.appwidget.bookmarks.BookmarkThumbnailWidgetProvider", "x": 2, "y": 3, "w": 2, "h": 2 },
          { "id": "clock-2", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 4, "w": 2, "h": 1 },
          { "id": "clock-3", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 5, "w": 2, "h": 1 },
          { "id": "clock-4", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 2, "y": 5, "w": 2, "h": 1 },
          { "id": "bookmarks-2", "widget": "app.vanadium.browser/com.google.android.apps.chrome.appwidget.bookmarks.BookmarkThumbnailWidgetProvider", "x": 4, "y": 0, "w": 4, "h": 3 },
          { "id": "messages-2", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 4, "y": 3, "w": 4, "h": 2 },
          { "id": "search-2", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 4, "y": 5, "w": 4, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 0, "y": 6, "w": 8, "h": 1 }
        ] }
      }
    }
  }
}
```

On the Fold the dock is eight wide. The cover shows its first four columns,
so the cover's dock shows the first four favorites.

### A screen full of widgets, the dock on the side

| Phone | Fold, cover | Fold, inner |
|---|---|---|
| <img alt="full dock side, phone" src="img/full-dock-side-phone.jpg" width="200"> | <img alt="full dock side, Fold cover" src="img/full-dock-side-fold-cover.jpg" width="200"> | <img alt="full dock side, Fold inner display" src="img/full-dock-side-fold-inner.jpg" width="300"> |

<!-- config -->
```json
{
  "schemaVersion": 2,
  "home": {
    "favorites": ["com.android.dialer", "com.android.messaging", "app.vanadium.browser", "app.grapheneos.camera", "com.android.contacts", "com.android.settings"],
    "grid": {
      "columns": 4,
      "labels": true,
      "layouts": {
        "phone": { "items": [
          { "id": "analog", "widget": "com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider", "x": 0, "y": 0, "w": 3, "h": 2 },
          { "id": "search", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 0, "y": 2, "w": 3, "h": 1 },
          { "id": "messages", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 0, "y": 3, "w": 3, "h": 2 },
          { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 5, "w": 3, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 3, "y": 0, "w": 1, "h": 6 }
        ] },
        "fold": { "items": [
          { "id": "analog", "widget": "com.android.deskclock/com.android.alarmclock.AnalogAppWidgetProvider", "x": 0, "y": 0, "w": 3, "h": 2 },
          { "id": "search", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 0, "y": 2, "w": 3, "h": 1 },
          { "id": "messages", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 0, "y": 3, "w": 3, "h": 2 },
          { "id": "clock", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 5, "w": 3, "h": 1 },
          { "id": "clock-2", "widget": "com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "x": 0, "y": 6, "w": 3, "h": 1 },
          { "id": "dock", "widget": "favorites", "x": 3, "y": 0, "w": 1, "h": 7 },
          { "id": "bookmarks", "widget": "app.vanadium.browser/com.google.android.apps.chrome.appwidget.bookmarks.BookmarkThumbnailWidgetProvider", "x": 4, "y": 0, "w": 4, "h": 3 },
          { "id": "messages-2", "widget": "com.android.messaging/com.android.messaging.widget.BugleWidgetProvider", "x": 4, "y": 3, "w": 4, "h": 3 },
          { "id": "search-2", "widget": "app.vanadium.browser/org.chromium.chrome.browser.searchwidget.SearchWidgetProvider", "x": 4, "y": 6, "w": 4, "h": 1 }
        ] }
      }
    }
  }
}
```

**On the Fold, a side dock that both displays show has to sit in column 3**,
because the cover shows columns 0 to 3. On the inner display it then stands in
the middle, with more widgets to its right. A dock in column 7 sits at the
inner display's right edge but is not on the cover at all.

## Editing on the device

A long press on an empty cell enters edit mode:
- drag an item to move it (the rest are pushed down);
- use the handles to resize it within the widget's limits;
- remove it, with undo;
- tap the dock to edit its favorites.

**Done** writes the layout back into `launcher.json`, so the file keeps
matching what you see. Pull it before pushing a changed file from elsewhere.
`home.grid.locked: true` disables all of this.
