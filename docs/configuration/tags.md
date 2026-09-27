# Tags

`tags` decides which apps carry which tags, and gives each tag its icon. It is
what a person sets up in the **Edit tag** sheet on the phone, written down, and
it travels both ways: an app tagged or a tag's icon picked on the phone goes
back into the file.

<!-- config -->
```json
{
  "schemaVersion": 2,
  "tags": [
    { "name": "Music", "icon": { "text": "🎵" }, "apps": ["org.schabi.newpipe", "com.example.player"] },
    { "name": "Work", "icon": { "pack": "app.lawnchair.lawnicons", "drawable": "briefcase" },
      "apps": [{ "packageName": "app.vanadium.browser", "profile": "work" }] }
  ]
}
```

| Key | What it does | Accepted |
|---|---|---|
| `tags` | The tags the apps carry | up to 200, no name twice |
| `tags[].name` | The tag, as search shows and filters it | 1 to 100 characters, not blank; no control characters or line breaks |
| `tags[].icon` | The tag's icon; see [Icons](#icons) | an object below |
| `tags[].icon.pack` | An icon pack's package | a package name |
| `tags[].icon.drawable` | One of that pack's drawables, by name | a resource name, or up to 31 of them separated by commas |
| `tags[].icon.themed` | `false` for the pack's unthemed variant of the drawable | boolean, default `true` |
| `tags[].icon.text` | Text drawn as the icon: an emoji, or a few letters | 1 to 16 characters, without control characters or line breaks |
| `tags[].apps` | The apps that carry the tag | up to 512, no app twice |
| `tags[].apps[].packageName` | The app; a personal app's first launcher entry can be written as the package name alone | a package name |
| `tags[].apps[].profile` | Which profile's copy of the app: `personal`, `work` or `private` | enum, default `personal` |
| `tags[].apps[].activity` | One launcher entry of a package that has several; without it, the package's first | a fully qualified class name |

**`tags` is the whole state of the apps' tags**, as [`apps`](apps.md) is of
their names:
- a tag the list does not name is carried by **no** app;
- `"tags": []` removes every tag from every app;
- to leave the phone's tags alone, leave `tags` out of the file entirely.

**Only apps.** A tag can also hold contacts and shortcuts on the phone. Those
are the phone's: the file neither lists nor removes them, and a tag that holds
only them is not in the read-back.

The same tag twice is an error, `duplicate-tag`, and so is the same app twice
in one tag, `duplicate-app`: the file would say two things about one tag. An
app that is not installed is reported, `app-unavailable`, and stays in the
file: when it is installed, the file is applied again and the app carries the
tag.

## Icons

`icon` is what the tag's icon picker offers, and nothing else:

- `{ "pack", "drawable" }`: one drawable of an icon pack, as for an
  [app's icon](apps.md#icons); `"themed": false` is the pack's unthemed
  variant of it.
- `{ "text" }`: text drawn as the icon, which is what the picker's emoji tab
  sets.

Without `icon`, the tag has the launcher's own tag icon. A pack that is not
installed is reported, `icon-pack-unavailable`, and the entry stays in the
file: the tag shows its own icon until the pack is installed.
