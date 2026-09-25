#!/usr/bin/env python3
"""The phases of the launcher's slowest frames, from `dumpsys gfxinfo <pkg> framestats` (#122).

After an unfold the launcher's first frame at the new size is the slowest one
in the window; this prints the slowest frames with their phases in ms:

  delay     IntendedVsync -> HandleInputStart (the UI thread was busy before the frame)
  input     HandleInputStart -> AnimationStart
  anim      AnimationStart -> PerformTraversalsStart (Choreographer callbacks: Compose recomposition)
  layout    PerformTraversalsStart -> DrawStart (measure and layout)
  record    DrawStart -> SyncQueued (building the display list)
  sync      SyncStart -> IssueDrawCommandsStart (RenderThread uploads, bitmaps)
  issue     IssueDrawCommandsStart -> SwapBuffers (RenderThread draws)
  swap      SwapBuffers -> SwapBuffersCompleted
  total     IntendedVsync -> SwapBuffersCompleted

FrameCompleted and GpuCompleted are not used: on the emulator they carry a
GPU fence value from another clock (measured on the fold emulator, #122).

Times are CLOCK_MONOTONIC; `vsync_ms` is the frame's intended vsync, so it can be
lined up with a trace. Frames with flags, or whose timestamps are out of
order, are left out. Usage: unfold_framestats.py file.framestats [N]
(N slowest frames; N=first prints the first frame after the reset;
N=vsync=<id> the frame with that FrameTimelineVsyncId, which is the name
of its slice in a Perfetto frametimeline).
"""
import sys


def rows(path):
    header, out, inside = None, [], False
    for line in open(path, errors="replace"):
        line = line.strip()
        if line.startswith("---PROFILEDATA---"):
            inside = not inside
            header = None
            continue
        if not inside or not line:
            continue
        cells = line.rstrip(",").split(",")
        if header is None:
            header = cells
            continue
        out.append(dict(zip(header, (int(c) for c in cells))))
    return out


def ms(a, b):
    return (b - a) / 1e6


def phases(r):
    return {
        "vsync_ms": r["IntendedVsync"] / 1e6,
        "flags": r["Flags"],
        "delay": ms(r["IntendedVsync"], r["HandleInputStart"]),
        "input": ms(r["HandleInputStart"], r["AnimationStart"]),
        "anim": ms(r["AnimationStart"], r["PerformTraversalsStart"]),
        "layout": ms(r["PerformTraversalsStart"], r["DrawStart"]),
        "record": ms(r["DrawStart"], r["SyncQueued"]),
        "sync": ms(r["SyncStart"], r["IssueDrawCommandsStart"]),
        "issue": ms(r["IssueDrawCommandsStart"], r["SwapBuffers"]),
        "swap": ms(r["SwapBuffers"], r["SwapBuffersCompleted"]),
        "total": ms(r["IntendedVsync"], r["SwapBuffersCompleted"]),
    }


if __name__ == "__main__":
    path = sys.argv[1]
    arg = sys.argv[2] if len(sys.argv) > 2 else "5"
    order = ["IntendedVsync", "HandleInputStart", "AnimationStart", "PerformTraversalsStart", "DrawStart",
             "SyncQueued", "SyncStart", "IssueDrawCommandsStart", "SwapBuffers", "SwapBuffersCompleted"]
    valid = [r for r in rows(path) if r["Flags"] == 0 and r["IntendedVsync"] > 0
             and all(r[a] <= r[b] for a, b in zip(order, order[1:]))]
    if not valid:
        raise SystemExit(f"{path}: no valid frames")
    fr = [phases(r) for r in valid]
    keys = ["vsync_ms", "flags", "delay", "input", "anim", "layout", "record", "sync", "issue", "swap", "total"]
    print("\t".join(keys))
    if arg.startswith("vsync="):
        # The frame Perfetto's frametimeline names by this vsync id.
        want = int(arg.split("=", 1)[1])
        chosen = [phases(r) for r in valid if r.get("FrameTimelineVsyncId") == want]
        if not chosen:
            raise SystemExit(f"{path}: no valid frame with vsync id {want}")
    elif arg == "first":
        chosen = [min(fr, key=lambda p: p["vsync_ms"])]
    else:
        chosen = sorted(fr, key=lambda p: -p["total"])[:int(arg)]
    for p in chosen:
        print("\t".join(f"{p[k]:.1f}" if isinstance(p[k], float) else str(p[k]) for k in keys))
