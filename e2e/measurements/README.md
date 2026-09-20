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
e2e/measure-footprint.sh --label no-backup --static   # host only, seconds
e2e/measure-footprint.sh --label no-backup            # + one runtime cycle
e2e/measure-footprint.sh --label no-backup --runs 3   # + three, with spreads
```

`--runs N` repeats the whole runtime cycle — snapshot load, install, measure —
N times and reports the median of each metric plus a `<metric>.spread` line
giving the range as a percentage of that median. Each cycle reboots rather
than relaunching, because the variance that matters sits between boots;
repeating inside one boot would report a spread narrower than the truth.

Use `--runs 3` (or more) whenever a runtime figure will be quoted. A single
cycle is enough only for effects far larger than the spreads in the table
below.

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
| `<metric>.spread` | with `--runs N`: the range across cycles, as a percentage of the median. A delta smaller than this is not a result |
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

The runtime metrics are not, and the spread differs enormously between them.
Both `--runs 3` measurements taken on 2026-09-20, on the baseline build and
on the build three removals later:

| Metric | spread, baseline | spread, no-backup |
|---|---|---|
| `mem.java_heap` | 1.5% | 0.2% |
| `mem.rss.total` | 5.5% | 2.1% |
| `mem.pss.total` | 6.9% | 3.0% |
| `mem.code` | 7.7% | 5.8% |
| `threads` | 9.1% | 12.3% |
| `cpu.startup` | 14.3% | 13.4% |
| `cpu.home_screen_charging` | 18.4% | 87.1% |
| `start.cold.median` | 29.1% | 26.5% |
| `cpu.home_screen` | 71.1% | 247.1% |

`cpu.home_screen` varied by a factor of 2.5 between cycles in one of those
measurements: a reported median of 1.70% of a core could have come out
anywhere from roughly 0.7 to 4.9. Do not quote it for anything but an effect
of the size the charging animation had.

**The spread is a lower bound, and it is not itself stable.** The same metric
reported 6.9% and 3.0% in the two measurements above. Consecutive cycles
share whatever state the host and the emulator are in at that moment, so they
agree with each other more than measurements taken minutes apart: two runs
separated by a rebuild spread 6.2% on PSS where three back-to-back cycles
spread 3.0%. Read a printed spread as "at least this much", and measure the
before and the after in the same sitting whenever a delta sits anywhere near
it.

What the runtime half is good for is catching the large, unambiguous effects.
The charging animation showed up as 89.20% against 3.10% of a core, which no
amount of this noise can manufacture. What it could not see is equally worth
knowing: after removing an entire module and an always-on animation,
`mem.pss.total` came out 5.2% *higher* than the baseline, inside both
spreads. This series has not yet freed measurable memory, and the harness
says so rather than letting a single flattering cycle claim otherwise.

## Check the mechanism before believing a runtime delta

Removing the plugin system (`no-tools` -> `no-plugins`) printed a 13.0% faster
cold start, 22.5% less startup CPU and a 61.5% lower `cpu.home_screen`. All
three are worth nothing: the previous measurement's own spreads on those
metrics were 31.2%, 16.3% and 71.8%, every one of them larger than or close to
the delta it would have to support.

The useful part was asking *how* the removal could have made startup faster.
`PluginServiceImpl` did real work in its `init` - a `queryIntentContentProviders`
scan across every installed package, two database writes, and a receiver for
five package-change actions - but it was registered as a plain Koin `single`,
which is lazy, and the only things that injected it were settings screens. It
was therefore never constructed during a launcher start, and its removal cannot
have changed startup at all. The numbers were noise wearing a plausible shape.

If a runtime delta matters, find the code path that produced it before quoting
it. A delta with no mechanism is a measurement artifact until proven otherwise,
and the metrics in the table above are more than capable of manufacturing one.

## Caveats that apply to every number here

- Debug builds, not minified. Release figures are smaller across the board;
  the *deltas* are what this series is about, not the absolute values.
- The emulator is a self-built GrapheneOS `sdk_phone64_x86_64` with test-keys.
  Timings are emulator timings and do not transfer to a Pixel.
- Cold start on an emulator is noisy even with warm-up runs and repeated
  cycles: it spread 26-29% across cycles of one build, so only a very large
  change means anything.
