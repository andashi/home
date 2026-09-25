# Favorites

`home.favorites` is the one list of pinned apps. The dock shows it, and so
does search (as its favorites row). Order matters: the dock fills row by row,
left to right, in this order, and shows as many as fit its size (see
[the dock](home-grid.md#the-dock)).

<!-- config -->
```json
{
  "schemaVersion": 2,
  "home": {
    "favorites": [
      "com.android.dialer",
      { "packageName": "com.android.messaging", "profile": "personal" },
      { "packageName": "com.example.work.mail", "profile": "work" },
      "app.vanadium.browser"
    ]
  }
}
```

| Key | What it does | Accepted |
|---|---|---|
| `home.favorites` | The pinned apps, in order | up to 64, no duplicates |
| `home.favorites[].packageName` | The app | a package name |
| `home.favorites[].profile` | Which profile's copy of the app: `personal`, `work` or `private` (Private Space) | enum, default `personal` |

A favorite can be written as a bare package name, which means the personal
profile, or as an object. The read-back always writes the object form and
leaves out a `personal` profile.

An app that is not installed is reported (`favorite-unavailable`) and skipped.
The rest of the list still applies.

## What the list manages, and what it leaves alone

Search can also pin app shortcuts, tags and contacts. The file names apps
only, so a present `home.favorites` manages the **app** pins and nothing
else:

- the apps are pinned in file order; an app that is pinned on the device but
  not in the list is unpinned;
- shortcuts, tags and contacts pinned on the device stay pinned, keep their
  order among themselves, and follow the configured apps;
- contacts are never named in the file and never touched by it.

So the first reload with this rule can move a contact that was pinned before
the apps to after them; nothing is lost. The read-back serves the app pins
only, because it serves what the file can hold. Shortcut and tag entries in
the list are planned (#3); contacts stay out, because their ids are local to
the device and they are personal data.
