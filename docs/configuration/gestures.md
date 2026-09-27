# Gestures

`gestures` sets what each gesture on the home screen does: the four swipes,
a double tap, a long press, and the home button pressed while the home screen
is already showing.

<!-- config -->
```json
{
  "schemaVersion": 2,
  "gestures": {
    "swipeDown": "notifications",
    "swipeUp": "search",
    "swipeLeft": { "packageName": "com.android.dialer" },
    "swipeRight": { "packageName": "com.example.work.mail", "profile": "work" },
    "doubleTap": "screen-lock",
    "longPress": "launcher-settings",
    "homeButton": "none"
  }
}
```

| Key | The gesture | Default |
|---|---|---|
| `gestures.swipeDown` | Swipe down | `search` |
| `gestures.swipeUp` | Swipe up | `search` |
| `gestures.swipeLeft` | Swipe left | `none` |
| `gestures.swipeRight` | Swipe right | `none` |
| `gestures.doubleTap` | Double tap | `screen-lock` |
| `gestures.longPress` | Long press | `none` |
| `gestures.homeButton` | The home button, on the home screen | `none` |

Each value is either an action, by name, or an app to open.

| Action | What it does |
|---|---|
| `none` | Nothing |
| `search` | Opens search |
| `notifications` | Pulls down the notifications |
| `quick-settings` | Pulls down quick settings |
| `screen-lock` | Turns the screen off. Needs the launcher's accessibility service |
| `power-menu` | Opens the power menu. Needs the accessibility service |
| `recents` | Opens the recent apps. Needs the accessibility service |
| `launcher-settings` | Opens the launcher's settings |

An app is written the way a favorite's object form is:

| Key | What it does | Accepted |
|---|---|---|
| `gestures.<gesture>.packageName` | The app | a package name |
| `gestures.<gesture>.profile` | Which profile's copy of the app: `personal`, `work` or `private` (Private Space) | enum, default `personal` |

Unlike a favorite, an app is always an object: a bare string is an action,
so a package name can never be mistaken for one. The read-back writes the
object form and leaves out a `personal` profile.

A key present manages that gesture; a key left out leaves the device's own
choice alone.

## What the file cannot set, and why

- **The feed.** The settings screen offers it only in builds where the feed
  exists, and release builds hide it. A configuration file must never be a
  route around a feature flag, so `feed` is refused like any unknown value,
  and the launcher does not open the feed from a gesture in a build that
  hides it, whatever the stored setting says.
- **Shortcuts and other search results.** On the device a gesture can open
  anything search finds. The file names apps only, as `home.favorites` does.

## When the effect differs from the file

- **An app that is not installed** in the named profile is reported
  (`gesture-app-unavailable`, or `profile-unavailable` when the profile does
  not exist) and the gesture keeps what it did before. The file keeps the app
  for the day it is installed.
- **The accessibility service.** `screen-lock`, `power-menu` and `recents`
  work through the launcher's accessibility service, which only the person
  using the device can turn on. The file sets the action anyway and the report
  carries `permission-missing` until the service is on; the launcher asks for
  it the first time the gesture is used.
- **A gesture set on the device to something the file cannot name** (a
  shortcut) is left out of the read-back, and a write-back keeps the file's
  value, with a `write-back-skipped:gesture-inexpressible` warning. The next
  reload applies the file's value again.
