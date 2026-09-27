# Apps

`apps` gives an app a name of its own on this launcher, and decides where it
appears: normally, in search only, or not at all. It is what a person sets in
an app's **Customize** sheet on the phone, written down, and it travels both
ways: a rename made on the phone goes back into the file.

<!-- config -->
```json
{
  "schemaVersion": 2,
  "apps": [
    { "packageName": "org.thoughtcrime.securesms", "label": "Chat" },
    { "packageName": "com.android.stk", "visibility": "hidden" },
    { "packageName": "app.vanadium.browser", "profile": "work", "visibility": "search-only" }
  ]
}
```

| Key | What it does | Accepted |
|---|---|---|
| `apps` | The apps that differ from their own name or from being shown normally | up to 512, no app twice |
| `apps[].packageName` | The app | a package name |
| `apps[].profile` | Which profile's copy of the app: `personal`, `work` or `private` (Private Space) | enum, default `personal` |
| `apps[].activity` | One launcher entry of a package that has several; without it, the package's first | a fully qualified class name |
| `apps[].label` | The name shown instead of the app's own, in search, the dock and the home grid | 1 to 100 characters, not blank; no control characters or line breaks |
| `apps[].visibility` | `default`: shown everywhere. `search-only`: found by searching, but not in the app list. `hidden`: not shown at all | enum, default `default` |

An entry is always an object, never a bare package name. An app named twice
(same package, profile and entry) is an error, `duplicate-app`. Only the device
knows which entry is a package's first, so an entry without `activity` and one
naming that first activity are the same app there: the first of the two applies,
and the second is reported as a `duplicate-app` warning. An app that is
not installed is reported, and its entry stays in the file: when the app is
installed again, the next reload gives it its name back.

## Hidden is not a lock

**Hiding an app is tidying, not a restriction.** The hidden-items button in
search, and the setting behind it, show every hidden app to whoever holds the
phone, and a hidden app still opens from anywhere else that starts it. If a
zone must not have an app, the answer is not to install it there; provisioning
decides that. `hidden` keeps the app list short. It protects nothing.

## The list is the whole state

A present `apps` is the complete list of customizations, like
`home.favorites` is the complete list of pins:

- an app the list does not name has **no** custom name and is shown normally;
- a key an entry leaves out is that key's default: an entry with a `label` and
  no `visibility` makes the app visible again.

**`"apps": []` does not mean "manage nothing".** It means *no app has a custom
name or visibility*, and applying it clears every rename and every hidden app
on the phone. To leave them alone, leave `apps` out of the file entirely: an
absent `apps` manages nothing, and what the phone has stays.

The read-back always serves `apps`, sorted, and leaves out anything that is a
default. It is an empty list on a phone with no customizations.

## What the file does not reach

The file names apps. Contacts and app shortcuts can be renamed and hidden on
the phone too; the file leaves them exactly as they are, whatever `apps` says.
