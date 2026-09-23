# Appearance

`appearance` holds the look of the home screen: the glass surfaces and the
wallpaper. Design background: [ADR 0004](../architecture/adr/0004-liquid-glass-design.md).

## Glass

Every card, the dock, the search pill and every icon chip is a glass surface:
the blurred wallpaper under it, bent at the edge like a lens, a tint of the
zone's color, a light rim that is bright at the top-left, and a short
specular at the top edge.

<!-- config -->
```json
{
  "schemaVersion": 2,
  "appearance": {
    "glass": { "blur": 24, "tint": 0.12, "radius": 28, "contrast": "medium", "wallpaperBlur": true }
  }
}
```

These are the defaults. Every key is optional; an absent one keeps what the
device has.

| Key | What it does | Accepted | Default |
|---|---|---|---|
| `appearance.glass.blur` | How soft the wallpaper behind glass is, in dp. `0` is tint only | 0–64 | 24 |
| `appearance.glass.tint` | How much of the zone's surface color lies over the backdrop | 0–1 | 0.12 |
| `appearance.glass.radius` | Corner radius of cards, the dock and the search bar while open, in dp | 0–64 | 28 |
| `appearance.glass.contrast` | `low`, `medium` or `high`. Scales blur and tint; `high` adds a dark scrim behind text and glyphs | enum | `medium` |
| `appearance.glass.wallpaperBlur` | The whole home background is the blurred wallpaper, not only what lies under glass | boolean | `true` |

A value out of range is an `invalid-glass` error and the file is not applied.
The glass needs a wallpaper the launcher manages (below). With a wallpaper set
by hand the surfaces are tint only.

### Contrast

`low` lowers the tint by 0.10 and the blur by a quarter. `high` raises the
tint by 0.15 and the blur by a quarter, and puts a 12 % black scrim behind
labels and glyphs.

<!-- config -->
```json
{ "schemaVersion": 2, "appearance": { "glass": { "contrast": "high" } } }
```

| `low` | `medium` | `high` |
|---|---|---|
| <img alt="contrast low, phone" src="img/contrast-low-phone.jpg" width="220"> | <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="220"> | <img alt="contrast high, phone" src="img/contrast-high-phone.jpg" width="220"> |

### Blur and the soft background

`appearance.glass.wallpaperBlur: false` keeps the wallpaper sharp and blurs
only what lies under glass. `appearance.glass.blur: 0` turns the blur off
altogether, which leaves the surfaces as tinted panes.

<!-- config -->
```json
{ "schemaVersion": 2, "appearance": { "glass": { "wallpaperBlur": false } } }
```

<!-- config -->
```json
{ "schemaVersion": 2, "appearance": { "glass": { "blur": 0, "wallpaperBlur": false } } }
```

| Default | `wallpaperBlur: false` | `blur: 0` |
|---|---|---|
| <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="220"> | <img alt="wallpaper sharp, phone" src="img/wallpaper-sharp-phone.jpg" width="220"> | <img alt="blur 0, phone" src="img/blur-0-phone.jpg" width="220"> |

### Tint and radius

<!-- config -->
```json
{ "schemaVersion": 2, "appearance": { "glass": { "tint": 0.4, "radius": 8 } } }
```

| Default | `tint: 0.4` | `radius: 8` |
|---|---|---|
| <img alt="full dock bottom, phone" src="img/full-dock-bottom-phone.jpg" width="220"> | <img alt="tint 0.4, phone" src="img/tint-0-4-phone.jpg" width="220"> | <img alt="radius 8, phone" src="img/radius-8-phone.jpg" width="220"> |

The tint uses the zone's Material You surface color, derived from the
wallpaper or the theme. The lens (how far the edge bends the backdrop) and the
rim have no key: there is one look.

## Wallpaper

<!-- config -->
```json
{
  "schemaVersion": 2,
  "appearance": { "wallpaper": { "image": "zone.jpg", "target": "both" } }
}
```

| Key | What it does | Accepted | Default |
|---|---|---|---|
| `appearance.wallpaper.image` | An image uploaded through the ingest provider (`content://<pkg>.config-ingest/wallpapers/<image>`) | a file name: letters, digits, `.`, `_`, `-`; no leading dot; up to 64 characters | — |
| `appearance.wallpaper.target` | `home`, `lock` or `both` | enum | `both` |

The launcher sets the wallpaper itself and remembers what it set. The
read-back names the image only while the device still shows it (not replaced
by hand, file unchanged). The system renders a wallpaper only for the profile
in front. A wallpaper configured for a background profile is therefore
recorded and set the next time that profile comes to the front; until then
the reload reports `wallpaper-pending-foreground`.

This managed wallpaper is also the only source of the glass backdrop. The
launcher cannot read a wallpaper set elsewhere without permissions it does not
ask for.

## Removed: transparency

`appearance.transparency` was the upstream transparency scheme. It is
replaced by `appearance.glass`. A file that still has it gets an `inert-key`
warning and nothing changes.
