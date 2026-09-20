# 0008: The launcher launches apps and hosts widgets; it is not a search aggregator

Status: accepted (2026-09-20)

## Context

Stock Kvaesitso is a search-first launcher, and its search reaches well beyond
apps: files (local, Nextcloud, ownCloud, plugin-supplied), contacts, calendar
events, places over OpenStreetMap and HERE, Wikipedia articles, websites, a
calculator, a unit converter and currency conversion. It ships built-in home
widgets for clock, weather, calendar, music and notes, and an SDK so third
parties can add further search providers.

ADR 0001 kept that assumption and said everything not on the grid is "reached
through search / the app drawer, exactly as in stock Kvaesitso". This ADR
revises that clause. The rest of ADR 0001 — one page, a grid, a dock, no app
icons on the grid — stands.

Three things forced the question while the module diet (#20) was under way.

**Almost none of it is used here.** The provisioning configuration installs no
Kvaesitso plugin, and several zones run with `net:false`, which already
disables online search and weather. The andashi deployment is one person's
GrapheneOS devices, not a general audience.

**Android already provides most of it.** The launcher has hosted standard
`AppWidget`s all along (`ui/base/AppWidgetHost.kt`, surfaced as the `external`
widget type), and ADR 0001 plans the grid for internal and external widgets
alike. A weather widget, a calendar widget, a music widget and a notes widget
are all things any app can supply through the platform's own mechanism. The
built-in versions are a second implementation of a solved problem, maintained
by us. Likewise a file manager searches files, and a maps app searches places.

**The cost is concentrated in permissions.** Local file search needs
`MANAGE_EXTERNAL_STORAGE` — "all files access", the broadest storage
permission Android has — to reach anything that is not media, which
contradicts the storage-scopes-friendly principle in ADR 0006 outright. Map
tiles are fetched over cleartext HTTP. The weather integration exports a
receiver without a permission guard. Plugin discovery calls into any installed
app that declares the plugin intent, before any trust check. Each of these is
a tracked security issue (#8, #9, #10, #11, #13, #14), and each of them is a
cost paid for a feature this deployment does not use.

## Decision

The launcher's job is to **launch apps and host widgets**. Everything else is
a separate app's job unless it is cheaper *inside* the launcher than outside
it.

**Search** covers apps and app shortcuts, plus contacts. Contacts stay for one
reason: the permission they cost, `READ_CONTACTS`, is a normal runtime
permission that can be revoked, and GrapheneOS narrows it further with Contact
Scopes. It is a bounded, reversible cost for a real saving in taps. Local file
search is dropped for the mirror-image reason: its only useful form costs
blanket access to all files, and a file manager under Storage Scopes does the
same work with less privilege.

That is the rule this ADR asks future changes to apply, rather than a list to
memorise: **keep a feature whose cost is a scoped, revocable permission; drop
one whose cost is a blanket grant.**

Dropped from search: files (local and remote), calendar events, places,
Wikipedia, websites, the calculator, the unit converter and currency
conversion, and the plugin system that let third parties add more.

**Built-in home widgets** are kept only where the platform offers no
equivalent. That leaves the AppWidget adapter (`external`) and favourites,
which is the dock. The clock, weather, calendar, music and notes widgets are
dropped; standard Android widgets cover them, and the clock in particular
duplicated what the lock screen and the status bar already show.

**The plugin SDK** (`plugins/sdk`) is withdrawn. It exists to be published for
third-party plugin authors, which is not a goal of a hard fork with one
operator, and it is not compiled into the app.

## Consequences

- Roughly a third of the launcher's Kotlin goes, which is far past a cleanup.
  It lands as a sequence of separately reviewable removals, each measured with
  `e2e/measure-footprint.sh` against the previous step, not as one commit.
- Six tracked security issues close by deletion rather than by hardening: #8,
  #9, #10, #11, #13 and #14. #5 remains as a fix that still has to be written.
- Losing a provider later is not free. ADR 0007 keeps `upstream` as a quarry,
  so a dropped provider can be cherry-picked back, but the cost of doing so
  grows as the fork diverges. This is a first step taken deliberately, with
  that bill accepted.
- Config keys for dropped providers leave the public contract (ADR 0002), and
  #3 shrinks to the surface that is left. An unknown *key* is ignored and
  reported as a diagnostic, so that part is safe. An unknown *enum value* is
  not: `ConfigParser` does not set `coerceInputValues`, so a `launcher.json`
  naming a dropped value - `"widgets": ["weather"]`, say - fails to decode
  **as a whole** and takes that zone's entire configuration with it. The
  parser test `invalid enum value fails decode without throwing` pins exactly
  that behaviour. The provisioning repo regenerates `launcher.json` from the
  current schema and names no dropped value, so nothing breaks today, but
  making the parser tolerate unknown enum values is real work that this ADR
  does not do.
- The search UI keeps its filter mechanism even though few categories remain.
  Collapsing that is a separate decision, made once the removals have settled.
- What the launcher no longer does, something else must: weather, calendar,
  music and notes move to standard widgets, file search to a file manager,
  place search to a maps app. Provisioning has to install those, which is a
  change in the provisioning repo, not here.
