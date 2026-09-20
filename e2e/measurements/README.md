# Footprint measurements

Results of `e2e/measure-footprint.sh`, kept in the repo so a module-diet PR
(#20) can point at a baseline instead of restating numbers that nobody can
check afterwards.

One measurement is two files:

| File | Contents |
|---|---|
| `<label>.tsv` | `metric<TAB>value<TAB>unit`, with `#` comment lines naming the commit, the APK and the UTC timestamp |
| `<label>.permissions` | the permissions the manifest declares, one per line, sorted |

Both are plain text and diff cleanly. `<label>` defaults to the short commit
hash; the diet series uses speaking names instead (`baseline`, `no-backup`,
`no-crashreporter`, …) so the sequence reads as a sequence.

## Producing one

```bash
./gradlew :app:app:assembleDefaultDebug
e2e/measure-footprint.sh --label no-backup           # static + runtime
e2e/measure-footprint.sh --label no-backup --static  # host only, seconds
```

The static half needs nothing but the APK and `apkanalyzer`. The runtime half
boots the GrapheneOS test instance, takes its device lock and measures the
launcher process, so it costs a boot cycle and obeys the usual instance rules
(`SERIAL`/`OVERLAY_DIR` together, never another session's instance — see
`AGENTS.md`).

## Reading one

```bash
e2e/measure-footprint.sh --compare measurements/baseline.tsv measurements/no-backup.tsv
```

Prints every metric with its delta and names the permissions that appeared or
disappeared. That permission list is the point as much as the byte counts:
several security issues close in this series by a permission no longer being
requested at all.

## What the metrics mean

| Metric | Meaning |
|---|---|
| `apk.size.file` | the APK on disk |
| `apk.size.download` | `apkanalyzer`'s estimate of the shipped (recompressed) size |
| `apk.size.dex` / `.res` / `.arsc` / `.assets` / `.lib` / `.other` | uncompressed bytes per category, which is what tracks removed content — compression ratios move when content moves |
| `dex.files` | number of dex files (a debug build is not minified, so this is high) |
| `dex.method_refs` | method *references*, the figure the 64k-per-dex limit counts; it tracks how much library surface the build still reaches into |
| `manifest.permissions` | number of declared permissions |
| `gradle.modules` | `include(...)` lines in `settings.gradle.kts` |
| `start.cold.median` / `.min` / `.max` | `am start -W` TotalTime over `START_RUNS` runs after `WARMUP_RUNS` discarded ones |
| `mem.pss.total` / `mem.rss.total` | App Summary totals from `dumpsys meminfo` after the settle window |
| `mem.java_heap` / `mem.native_heap` / `mem.code` / `mem.graphics` | the App Summary rows that move when modules go |
| `cpu.startup` | CPU seconds the process burned from launch through the settle window — the cost of starting up and reaching steady state, where a removed module's initialisation shows |
| `cpu.home_screen` | share of one core it keeps burning afterwards, unplugged, from `/proc/<pid>/stat` over `CPU_WINDOW`. See below: not called "idle" for a reason |
| `cpu.home_screen_charging` | the same window with the battery plugged back in, which is where the charging animation's cost lives |
| `battery.status` | the battery state the run was taken in (3 = discharging); before/after comparisons must share it |
| `threads` | threads in the launcher process; modules that spawn workers show up here |
| `profiles.non_managed` | non-managed profiles in the provisioning config — how often the launcher exists on the real device |
| `mem.pss.all_profiles_est` | `mem.pss.total × profiles.non_managed`. **An extrapolation, not a measurement**: the emulator keeps at most three users running and evicts the rest, so a device-wide total cannot be measured there |

## `apk.size.file` is not the headline number

Measured 2026-09-20 while taking the baseline: an APK built before the
`minSdk 36` bump (PR #25) was 34.5 MB on disk, one built after it 80.7 MB —
with near-identical contents (dex 74.2 MB vs 74.0 MB uncompressed) and a
*smaller* `apk.size.download`. The reason is packaging, not code: at
`minSdk >= 28` AGP stores dex entries uncompressed (`Stored`, 0%) so the
platform can map them directly, which trades on-disk size for install and
start-up speed.

It can also stay bit-identical while the content shrinks. Removing
NavBarEffects took 33 KB out of the dex payload, 6.8 KB out of the resource
table and 104 method references, and left `apk.size.file` on exactly the same
byte (80721418). The APK carries ~292 KB of alignment padding, and entries
stored uncompressed are page-aligned, so a shrink inside an entry is absorbed
by the padding that follows it instead of moving what comes after.

So `apk.size.file` tracks packaging policy and alignment as much as content.
It is recorded because it is what actually occupies the device, but the
numbers to quote for a removal are `apk.size.download` and the uncompressed
category sizes, which no packaging switch can move.

## Two results that look like bugs and are not

**`mem.native_heap` is 0.** That is what the device reports, in both the Pss
and the Rss column — verified against the raw `dumpsys meminfo` output on
2026-09-20. The parser is not dropping it, and it should not be "fixed".

**`cpu.home_screen` was around 110% until the battery was unplugged.** The
first baseline showed the launcher holding roughly one whole core on an idle
home screen, steady across windows and reproducible across boots. Broken down
per thread it was RenderThread 56% and the main thread 46%, with the frame
counter climbing by ~306 frames per 10 s — a screen nobody was touching was
redrawing at ~30 fps.

The cause is `NavBarEffects` (`app/ui/.../component/NavBarEffects.kt`, drawn
full-screen from `SharedLauncherActivity`): while the battery status is
CHARGING *or FULL* it runs `while (isActive) { withInfiniteAnimationFrameMillis
{} … }`, reallocating its bubble array and invalidating a full-screen Canvas
every frame. An emulator is permanently on AC and reports 100%, so the loop
never stops there.

This is why the runtime half unplugs the battery (`dumpsys battery unplug`,
`set status 3`) before measuring, and records `battery.status` in the result
so a file says which state it was taken in. Unplugging changed the same build
from 111.00% to **1.80%** of a core on the home screen, and cut `cpu.startup`
from 23.36 s to 10.92 s — the animation accounted for essentially all of it.
Only the second figure leaves room for a module's background work to show at
all.

The animation is a real cost on a charging phone, and it is on by default
(`animationsCharging = true`, `LauncherSettingsData.kt`), but it is a constant
that has nothing to do with how many modules the build contains.

Before/after comparisons must be taken under the same protocol, which
`battery.status` in each file makes checkable.

## How much of a delta is real

The static metrics are deterministic: the same source produces the same APK
size, dex method references, permission list and module count every time. A
change there is a change.

The runtime metrics are not. Two runs measured on 2026-09-20 from the same
snapshot, on builds differing by a single unused vector drawable (~1 KB),
came out like this:

| Metric | run 1 | run 2 | spread |
|---|---|---|---|
| `mem.pss.total` | 186896 KB | 198402 KB | 6.2% |
| `mem.code` | 85640 KB | 93300 KB | 8.9% |
| `cpu.startup` | 12.73 s | 10.91 s | 14.3% |
| `cpu.home_screen` | 2.40% | 1.90% | 20.8% |
| `cpu.home_screen_charging` | 3.10% | 2.70% | 12.9% |
| `start.cold.min` | 2621 ms | 1927 ms | 26.5% |

So a single run cannot support a claim like "this removal saved 5% of the
launcher's memory". Treat a one-run runtime delta below roughly 10% for
memory, 20% for CPU and 25% for cold start as noise, and repeat the
measurement before concluding anything inside those bands. Earlier two runs
of this harness happened to agree on PSS to 0.003%, which was luck rather
than precision — the table above is the honest picture.

What the runtime half is good for is catching the large, unambiguous effects:
the charging animation showed up as 99.20% versus 1.60% of a core, which no
amount of this noise can manufacture.

## Caveats that apply to every number here

- Debug builds, not minified. Release figures are smaller across the board;
  the *deltas* are what this series is about, not the absolute values.
- The emulator is a self-built GrapheneOS `sdk_phone64_x86_64` with test-keys.
  Timings are emulator timings and do not transfer to a Pixel.
- Cold start on an emulator is noisy even with warm-up runs; treat a change
  under roughly 10% as noise unless it repeats.
