#!/usr/bin/env python3
"""Builds the feature summary page from e2e/screenshots/{phone,fold}/captions.tsv
and the PNGs next to them (embedded as data URIs, downscaled to keep the page
small). Usage: build-summary.py <screenshots-dir> <out.html> [build-label]
The build label defaults to `git describe --tags --always` of the checkout the
screenshots were taken from, so a regenerated page names its own build."""
import base64, csv, io, pathlib, subprocess, sys
from PIL import Image

root = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2])
build = sys.argv[3] if len(sys.argv) > 3 else subprocess.run(
    ["git", "describe", "--tags", "--always", "--dirty"], capture_output=True, text=True,
    cwd=root).stdout.strip() or "unknown build"

def data_uri(png: pathlib.Path, max_h: int) -> tuple[str, int, int]:
    im = Image.open(png).convert("RGB")
    w, h = im.size
    if h > max_h:
        im = im.resize((round(w * max_h / h), max_h), Image.LANCZOS)
    buf = io.BytesIO(); im.save(buf, "WEBP", quality=82, method=6)
    return "data:image/webp;base64," + base64.b64encode(buf.getvalue()).decode(), *im.size

def scenes(kind: str):
    d = root / kind
    with open(d / "captions.tsv", newline="") as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    for r in rows:
        png = d / f"{r['scene']}.png"
        if not png.exists():
            continue
        uri, w, h = data_uri(png, 1200)
        yield r["scene"], r["caption"], r["guarded by"], uri, w, h

def card(scene, caption, guard, uri, w, h, wide=False):
    title = scene.split("-", 1)[1].replace("-", " ")
    return f'''<figure class="card{' wide' if wide else ''}">
  <div class="shot"><img src="{uri}" width="{w}" height="{h}" alt="{title}" loading="lazy"></div>
  <figcaption>
    <span class="num">{scene.split('-',1)[0]}</span>
    <h3>{title}</h3>
    <p>{caption}</p>
    <p class="guard">Guarded by: {guard}</p>
  </figcaption>
</figure>'''

phone = "\n".join(card(*s) for s in scenes("phone"))
fold = "\n".join(card(*s, wide=(s[4] > s[5])) for s in scenes("fold"))

html = f'''<title>Andashi Home Grid Features</title>
<style>
:root{{--ground:#f2f5f9;--paper:#fff;--ink:#15202b;--ink-2:#4b5a6b;--ink-3:#7d8a99;--line:#d9e0e8;--accent:#2f6fd0;--accent-ink:#1f4f9b;--accent-soft:#dfeafc;--code:#eef2f7;color-scheme:light}}
@media (prefers-color-scheme:dark){{:root:not([data-theme="light"]){{--ground:#0f161e;--paper:#151e28;--ink:#e7edf3;--ink-2:#b4c0cc;--ink-3:#7f8d9b;--line:#26323f;--accent:#6ea3f0;--accent-ink:#9dc0f6;--accent-soft:#1c2e48;--code:#0e151d;color-scheme:dark}}}}
:root[data-theme="dark"]{{--ground:#0f161e;--paper:#151e28;--ink:#e7edf3;--ink-2:#b4c0cc;--ink-3:#7f8d9b;--line:#26323f;--accent:#6ea3f0;--accent-ink:#9dc0f6;--accent-soft:#1c2e48;--code:#0e151d;color-scheme:dark}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--ground);color:var(--ink);font:16px/1.5 "IBM Plex Sans",system-ui,sans-serif}}
.wrap{{max-width:1100px;margin:0 auto;padding-block:36px 80px;padding-inline:20px}}
h1,h2,h3{{font-family:"Sora",system-ui,sans-serif;text-wrap:balance;margin:0}}
h1{{font-size:2rem;font-weight:700}}h2{{font-size:1.3rem;margin-top:48px;padding-top:18px;border-top:1px solid var(--line)}}
.eyebrow{{font-size:.75rem;letter-spacing:.08em;text-transform:uppercase;color:var(--ink-3);font-weight:600;margin-bottom:10px}}
.lede{{font-size:1.05rem;color:var(--ink-2);max-width:72ch;margin-top:12px}}
.meta{{display:flex;flex-wrap:wrap;gap:8px 18px;margin-top:14px;font-size:.9rem;color:var(--ink-3)}}.meta b{{color:var(--ink-2);font-weight:500}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:22px;margin-top:20px}}
.card{{margin:0;background:var(--paper);border:1px solid var(--line);border-radius:14px;overflow:hidden;display:flex;flex-direction:column}}
.card.wide{{grid-column:span 2}}@media (max-width:660px){{.card.wide{{grid-column:span 1}}}}
.shot{{background:#000;display:flex;justify-content:center;align-items:center;max-height:560px;overflow:hidden}}
.shot img{{max-width:100%;height:auto;max-height:560px;display:block}}
figcaption{{padding:14px 16px 16px}}
.num{{font-family:"IBM Plex Mono",monospace;font-size:.8rem;color:var(--accent);font-weight:500}}
figcaption h3{{font-size:1rem;font-weight:600;margin-top:2px;text-transform:capitalize}}
figcaption p{{margin:8px 0 0;font-size:.93rem;color:var(--ink-2)}}
.guard{{font-size:.8rem !important;color:var(--ink-3) !important;font-family:"IBM Plex Mono",monospace}}
code{{font-family:"IBM Plex Mono",monospace;background:var(--code);padding:1px 5px;border-radius:4px;font-size:.88em}}
a{{color:var(--accent-ink)}}
</style>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Sora:wght@600;700&family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<div class="wrap">
  <div class="eyebrow">andashi/home · epic #23 · {build}</div>
  <h1>Andashi Home Grid Features</h1>
  <p class="lede">Every feature of the single-page widget grid, photographed on the GrapheneOS emulator by <code>e2e/screenshots.sh</code>: each scene is a config the launcher received through its public surface plus, where it applies, a gesture sent with <code>adb input</code>. Nothing was staged by hand, so the pictures can be regenerated for every release. The look is still the pre-glass one: liquid glass surfaces are the next epic (#24).</p>
  <div class="meta"><span><b>Build</b> {build}</span><span><b>Phone</b> GrapheneOS emulator, 1080×2364</span><span><b>Fold</b> GrapheneOS foldable instance, 2076×2152 inner, 1080×2364 cover</span><span><b>Widgets</b> AOSP DeskClock (digital 3×1, analog 2×2)</span></div>

  <h2>Phone</h2>
  <div class="grid">
{phone}
  </div>

  <h2>Fold</h2>
  <div class="grid">
{fold}
  </div>
</div>
'''
out.write_text(html)
print(out, len(html) // 1024, "KiB")
