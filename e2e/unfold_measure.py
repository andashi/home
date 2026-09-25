#!/usr/bin/env python3
"""Per-frame analysis of an unfold recording (#122).

Input: a recording of the inner display made through the emulator console
(`adb emu screenrecord`, 2076x2152, 24 fps) that starts folded and ends
unfolded and settled. Before the switch the recording shows the cover's
content, then the displays switch - a run of black frames - then the inner
display. The script reports, relative to the first black frame:

  inner   the first inner frame that is not black (the display is on)
  bar     the search bar region matches the settled state
  col7    the right-edge column (the fold dock) matches it
  col3    a mid-screen column matches it
  full    the whole frame matches it
  letterboxed  inner frames that show a cover-sized buffer between black bands

"Matches the settled state" is an absolute reference: a launcher that is
complete in the first inner frame reports the same time for inner and full.
The settled state is the per-pixel median of the last second (one late
artefact does not define it), and the status bar is left out (its clock can
turn over during the recording).
The first version of this script (provisioning's, 2026-09-24) compared
against the first inner frame instead, which reports "never arrived" for
exactly that case.

At 24 fps every time is quantised to about 42 ms. The recording says which
build shows its content in fewer frames; the split between the system and
the launcher comes from a Perfetto trace, not from here.

A standalone tool, not called by measure-unfold.sh: record with
`adb emu screenrecord start --time-limit 8 f.webm`, unfold, stop.
Usage: unfold_measure.py file.webm [file.webm ...]   (one line per file)
Needs ffmpeg, ffprobe, numpy.
"""
import subprocess
import sys

import numpy as np

W, H = 2076, 2152
COL = W / 8
STATUS_BAR = 120  # rows of the status bar on the inner display
REGIONS = {
    "bar": (60, 1960, W - 60, 2090),
    "col7": (int(7 * COL) + 10, 120, W - 10, 1300),
    "col3": (int(3 * COL) + 10, 120, int(4 * COL) - 10, 1300),
    # Below the status bar: its clock can turn over during a recording, and
    # then every earlier frame differs from the settled state (provisioning
    # hit an 8.8 s "settle" that way, 2026-09-24).
    "full": (0, STATUS_BAR, W, H),
}
SETTLED = 24      # the settled state: the per-pixel median of the last second
BLACK = 30.0      # mean luma below this is a display that is off
MATCH = 4.0       # mean abs luma difference to the last frame that counts as settled
SCALE = 4         # regions are compared at a quarter of the resolution


def frames(path):
    """Yields (pts, luma array) per frame, decoded in one ffmpeg pipe, nothing on disk."""
    w, h = W // SCALE, H // SCALE
    pts = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "frame=pts_time", "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True).stdout.split()
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-fps_mode", "passthrough",
         "-vf", f"scale={w}:{h}", "-pix_fmt", "gray", "-f", "rawvideo", "-"],
        capture_output=True, check=True).stdout
    n = len(raw) // (w * h)
    if n == 0 or n > len(pts):
        raise SystemExit(f"{path}: {n} frames decoded, {len(pts)} timestamps")
    arr = np.frombuffer(raw[: n * w * h], np.uint8).reshape(n, h, w).astype(np.float32)
    return [float(t) for t in pts[:n]], arr


def crop(a, r):
    x0, y0, x1, y1 = (v // SCALE for v in r)
    return a[..., y0:y1, x0:x1]


def analyse(path):
    ts, f = frames(path)
    mean = f.mean(axis=(1, 2))
    dark = np.flatnonzero(mean < BLACK)
    if len(dark) == 0:
        raise SystemExit(f"{path}: no black frames - did the posture change take?")
    off = int(dark[0])
    if off == 0:
        raise SystemExit(f"{path}: starts black - the recording has no cover frames before the switch")
    lit = np.flatnonzero(mean[off:] >= BLACK)
    if len(lit) == 0:
        raise SystemExit(f"{path}: never lights up after the switch")
    on = off + int(lit[0])
    if np.any(mean[on:] < BLACK):
        raise SystemExit(f"{path}: a second black run after the inner display came on")
    if len(f) - on < 2 * SETTLED:
        raise SystemExit(f"{path}: under two seconds after the switch - record longer")
    # A median, so one late artefact does not define "settled" for the run.
    last = np.median(f[-SETTLED:], axis=0)
    # The end has to be settled, or "matches the settled state" means nothing.
    below = STATUS_BAR // SCALE
    if np.abs(f[-SETTLED:, below:] - last[below:]).mean(axis=(1, 2)).max() > MATCH / 2:
        raise SystemExit(f"{path}: the last second still changes - record longer")
    out = {"inner": ts[on] - ts[off], "gap_frames": on - off}
    # The cover's buffer shown centred on the inner display, black on both
    # sides: frames at the old window size (seen once on the fold emulator).
    side = W // 5 // SCALE
    out["letterbox"] = sum(1 for i in range(on, len(f))
                           if f[i][:, :side].mean() < BLACK and f[i][:, -side:].mean() < BLACK
                           and mean[i] >= BLACK)
    for name, r in REGIONS.items():
        ref = crop(last, r)
        diff = np.abs(crop(f[on:], r) - ref).mean(axis=(1, 2))
        hit = np.flatnonzero(diff < MATCH)
        out[name] = None if len(hit) == 0 else ts[on + int(hit[0])] - ts[off]
        if name == "full":
            out["full_first_diff"] = float(diff[0])
    return out


def fmt(v):
    return "never" if v is None else f"+{v:.3f}"


if __name__ == "__main__":
    for p in sys.argv[1:]:
        o = analyse(p)
        print(f"{p.rsplit('/', 1)[-1]}\tinner {fmt(o['inner'])} ({o['gap_frames']} black)"
              f"\tbar {fmt(o['bar'])}\tcol7 {fmt(o['col7'])}\tcol3 {fmt(o['col3'])}"
              f"\tfull {fmt(o['full'])}\tfirst-inner-vs-last {o['full_first_diff']:.1f}"
              f"\tletterboxed {o['letterbox']}")
