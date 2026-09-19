# 0006: GrapheneOS-specific integration and constraints

Status: accepted (2026-09-17)

## Context

The launcher runs exclusively on GrapheneOS (Pixel 10 Pro Fold) inside a
multi-profile "network zones" model, provisioned by `~/Development/andashi/provisioning`.
That removes generic-Android constraints and adds a few specific ones.

## Decision

### Constraints we adopt deliberately

- **No Play Services, no telemetry, no network permission requirement for config.**
  Stock Kvaesitso already qualifies; the fork must not regress (watch upstream
  merges for new dependencies).
- **Storage-Scopes friendly:** config lives in the app-specific external files dir
  (ADR 0003); the launcher never requests broad storage access.
- **minSdk raised to the current Android release (36)**, matching `targetSdk`;
  `compileSdk` already tracks the newest SDK (37). Raised 2026-09-19 (issue #19).
  GrapheneOS ships only current Android, so all pre-36 compat code (`core/compat`
  shims, about 120 `SDK_INT` checks, legacy fallbacks) is dead and gets deleted
  with the module diet (issue #20) instead of being maintained. Older-Android
  support is explicitly dropped, not just deprioritized.
- **Own signing key, pinned in the provisioning repo** (`apks/SHA256SUMS` flow).
  Reproducible-ish builds: pinned toolchain, version catalog, build log archived
  (as `kvaesitso-patch/build-*.log` already does).
- **New applicationId: `org.andashi.home`** (debug builds `org.andashi.home.debug`,
  nightly `org.andashi.home.nightly`; the release build carries no suffix). Display
  name "Andashi Home". Decided 2026-09-19 together with the organization
  (andashi, andashi.org, `github.com/andashi/home`). Content authorities and the
  reload action derive from the *effective* applicationId via
  `${applicationId}` in the manifest, so the release build serves
  `org.andashi.home.state`, `org.andashi.home.config-ingest` and
  `org.andashi.home.action.RELOAD_CONFIG`, while a debug build serves
  `org.andashi.home.debug.state` and so on. Scripts derive every identifier
  from the package name they target; the e2e scripts do exactly that.
  Kotlin packages keep the upstream namespace for now; renaming them is cosmetic
  and part of the module diet (issue #20). Coexisting with/upgrading over upstream
  installs is not a goal; provisioning creates fresh profiles anyway. Side effect:
  AppWidget host bindings and favorites do not migrate from a stock install —
  accepted.

### Features we build because GrapheneOS enables them

- **Per-profile config as first-class design:** external files dirs are per-user;
  the provisioning repo maps zones → profiles → one `launcher.json` each. The
  read-back provider (ADR 0003) makes per-profile verification trivial.
  `LauncherActivity` already handles `android.os.UserHandle` via `core/profiles`;
  the grid respects work-profile apps through the existing profile infrastructure.
- **Private Space:** treated as just another profile for config purposes in v1;
  explicit lock/unlock integration (launcher APIs) is a later enhancement, tracked
  separately.
- **Verified boot / hardened_malloc:** no native code added by the fork, so the
  hardened memory allocator story stays upstream's.

### What we explicitly do not do

- No privileged/system-app integration, no sharedUserId, no requests for
  permissions beyond what stock Kvaesitso uses (notification listener, accessibility
  for global actions, contacts/calendar for search — all opt-in as upstream).
- No attempt to script profile PINs, Play installs, or Storage/Contact Scopes —
  they stay manual per the provisioning repo's documented split.

## Consequences

- The fork's support matrix is: **current GrapheneOS on Pixel devices — candybar
  phones and the Pixel Fold** (cover + inner display). Form-factor differences are
  in scope (see ADR 0001 for per-form-factor grids); OS diversity is not. Bugs
  that only reproduce on other OSes are out of scope.
- Release engineering (sign, hash, pin, changelog) is part of "done" for every
  fork release, because provisioning consumes pinned APKs.
