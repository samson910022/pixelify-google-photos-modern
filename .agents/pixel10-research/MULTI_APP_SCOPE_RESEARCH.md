# Multi-App Xposed Scope Redesign — Research

**Project:** Pixelify Infinity (`io.github.samson910022.pixelifyphotos`)  
**Repo:** `/home/samson1357924/projects/pixelify-google-photos-modern`  
**Date:** 2026-07-30  
**Scope of this doc:** Research only. No app source changes. No commit/push.  
**Identity invariant:** Application ID / Kotlin package / Xposed entry stay `io.github.samson910022.pixelifyphotos` (per `AGENTS.md`).

---

## 1. Current behavior summary

### 1.1 Modern Xposed packaging (what ships today)

| File | Current content | Role |
|------|-----------------|------|
| `META-INF/xposed/module.prop` | `minApiVersion=101`, `targetApiVersion=101`, **`staticScope=true`** | Modern module config |
| `META-INF/xposed/scope.list` | `com.google.android.apps.photos` | Recommended (and intended fixed) target package list |
| `META-INF/xposed/java_init.list` | `io.github.samson910022.pixelifyphotos.PixelifyModule` | Module entry |
| `distribution/xposed-repository/SCOPE` | `["com.google.android.apps.photos"]` | modules.lsposed.org recommended scope JSON |
| Gradle | `io.github.libxposed:api` / `service` **101.0.0** | Compile/runtime service API |

### 1.2 Runtime gate (hard Photos-only)

`PixelifyModule` dual-gates on Google Photos:

1. **`onPackageLoaded`**: early return unless `params.packageName == Constants.PACKAGE_NAME_GOOGLE_PHOTOS` (`com.google.android.apps.photos`), then only if `params.isFirstPackage`.
2. **`onPackageReady`**: `when` only matches Photos; registers `FeatureSpoofer` + re-applies `DeviceSpoofer`.

**Implication:** Even if a user (or a non-enforcing manager) adds another package to LSPosed scope, **this module still does nothing** in that process. Scope metadata and code filter are both Photos-hardcoded.

### 1.3 What the hooks actually do

| Component | Mechanism | Process locality | Photos-specific? |
|-----------|-----------|------------------|------------------|
| **DeviceSpoofer** | Rewrites `android.os.Build` / `Build.VERSION` static fields (Field.set / Unsafe / JNI); hooks `android.os.SystemProperties.get*` | **In-process only** (runs inside each scoped app process) | **No** — generic device fingerprint spoof |
| **FeatureSpoofer** | Hooks `ApplicationPackageManager.hasSystemFeature(String[, int])` | In-process | **Mixed** — flags include both `com.google.android.apps.photos.*` preload flags **and** global `com.google.android.feature.PIXEL_*_EXPERIENCE` flags |

Prefs are read via:

- Hook side: `XposedModule.getRemotePreferences("prefs")`
- UI side: `XposedService.getRemotePreferences("prefs")` with `MODE_PRIVATE` fallback (`PrefUtils`)

There is **one global prefs group**, not per-target-package prefs. Device profile + feature list apply identically to every process where hooks run.

### 1.4 UI / docs / product messaging

All user-facing guidance is Photos-only:

- README / SUPPORT / CONTRIBUTING / site: “scope **only** to Google Photos”
- Strings: force-stop / open Photos buttons; `module_not_enabled` says scope only to Photos
- Manifest `<queries>` only declares Photos
- `AGENTS.md` lists Xposed scope as Photos only

### 1.5 LSPosed UX implications of current `staticScope=true`

Official modern-API wiki meaning of `staticScope`:

> Indicates whether users **should not** apply the module on any other app out of scope.

`scope.list` is the modern replacement for legacy `xposedscope` metadata: **recommended default selection** when the module is enabled (one package name per line).

**Manager behavior observed in LSPosed `master` source (ModuleUtil + ScopeAdapter):**

- Manager **parses** `staticScope` from `module.prop` into `InstalledModule.staticScope`.
- Manager loads `META-INF/xposed/scope.list` as the module’s recommended list.
- On first enable with empty checked scope, apps in `scope.list` are **auto-checked**.
- Recommended apps are labeled “requested by module”, sorted first, and “Use recommended” menu re-applies them.
- **`ScopeAdapter` does not currently read `module.staticScope` when toggling checkboxes** — so UI restriction may be incomplete on stock LSPosed master. Treat wiki intent as authoritative for *correct module declaration*; do not rely on managers to hard-block extra apps forever.
- Dynamic scope APIs exist on the module app via `XposedService`: `getScope()`, `requestScope(packages, callback)`, `removeScope(packages)`.

Legacy wiki “Module Scope” still documents Manifest `xposedscope` meta-data for **legacy** modules; modern modules should use `scope.list`, not Manifest meta-data.

---

## 2. What must change (for multi-app selectable scope)

### 2.1 `module.prop` / scope metadata

| Item | Today | Needed for “Photos recommended, user multi-select” |
|------|-------|-----------------------------------------------------|
| `staticScope` | `true` | **`false`** (or omit; wiki marks it optional). `true` *declares* “do not apply outside scope.list”. |
| `scope.list` | Photos only | Keep **Photos as primary recommended** line. Optionally add curated extra recommended packages later (product decision). |
| `min/targetApiVersion` | 101 | Keep 101 unless deliberately bumping to 102 (hot-reload / running targets — not required for multi-scope). |
| Mirror `SCOPE` JSON | Photos only | Same as `scope.list` policy for modules.lsposed.org cards. |

### 2.2 Hook code (required; metadata alone is insufficient)

| Change | Why |
|--------|-----|
| Remove / replace hard `== PACKAGE_NAME_GOOGLE_PHOTOS` checks in `PixelifyModule` | Otherwise extra LSPosed scopes never hook |
| Gate on **“whatever LSPosed injected us into”** (framework already filters by user scope) | Correct modern model: manager scope is the allowlist |
| Keep `isFirstPackage` guard | Avoid double-apply on shared processes / non-primary packages |
| Decide feature policy for non-Photos apps | Photos preload flags are harmless no-ops elsewhere; PIXEL_* flags **do** change generic `hasSystemFeature` answers |
| Decide device spoof policy for non-Photos apps | Build spoof is powerful and risky outside Photos |
| Optional: soft denylist (GMS, Play Store, banking patterns) with log + skip | Defense-in-depth beyond LSPosed UI |
| Optional: per-package enable / profile in remote prefs | Only if product wants app-specific configs |

### 2.3 UI

| Area | Change |
|------|--------|
| Force-stop / open buttons | Generalize to “scoped apps” or keep Photos shortcuts + “manage scope in LSPosed” |
| Snackbars | “Force-stop affected apps” not only Photos |
| `module_not_enabled` copy | “Enable module; recommended scope is Google Photos; add other apps only if you understand risks” |
| Safety dialog | First-run / when detecting multi-scope via `XposedService.getScope()` |
| Optional in-app “Request recommended scope” | `requestScope(listOf(PHOTOS), …)` for onboarding |
| Package visibility | Expand `<queries>` or use safer launch/stop flows for non-Photos packages |

### 2.4 Docs / distribution / AGENTS

- README, SUPPORT, CONTRIBUTING, PRIVACY, site, translated READMEs: stop saying “**only** Google Photos” as a hard rule; rephrase as **recommended default**.
- `AGENTS.md` identity block: change “Xposed scope” from singular fixed package to “recommended scope Photos; user-selectable multi-app (staticScope=false)”.
- `docs/XPOSED_REPOSITORY.md` / `PUBLICATION_CHECKLIST`: allow multi-line `SCOPE` when product decides.
- SECURITY / risk wording: spoofing outside Photos is user-accepted risk.

### 2.5 LSPosed UX (user-facing, not code)

After `staticScope=false` + code allow multi-package:

1. Enable Pixelify Infinity in manager.
2. Photos should appear pre-selected (from `scope.list`) on first enable.
3. User may check additional apps (manager-dependent).
4. Force-stop each newly scoped app (or reboot if manager requires).
5. Do **not** recommend scoping system / GMS / Play Store / banks.

---

## 3. Recommended design options (A / B / C)

### Option A — Minimal multi-scope (framework-trust)

**Mechanism**

- `staticScope=false`
- `scope.list` = Photos only (recommended default)
- `PixelifyModule` applies Device + Feature spoof to **any** package LSPosed loads the module into (still `isFirstPackage`)
- Global remote prefs unchanged
- Docs/UI: Photos recommended; multi-select allowed with warnings

**Pros**

- Smallest code delta; matches “stop hard-limiting”
- Correct split of duties: LSPosed owns scope, module owns hooks
- Remote prefs already multi-process safe (LSPosed DB + change listener support per wiki)

**Cons**

- One mis-tap in manager can spoof banking / Wallet / GPay process Build props
- No per-app feature differentiation
- Feature flags still include Photos-named strings (harmless) and PIXEL_* (not harmless)

**Fit:** Good MVP if paired with strong warnings + optional denylist.

---

### Option B — Recommended default + safety rails (recommended)

**Mechanism**

- Same as A for metadata (`staticScope=false`, Photos in `scope.list`)
- Code allowlist modes:
  1. **Default mode:** hook any LSPosed-scoped app **except** a built-in **danger denylist** (e.g. `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf`, common bank package patterns / user-extensible set)
  2. Optional **strict mode** pref: only packages in an allowlist pref (default `{Photos}`) even if LSPosed scope is wider — belt-and-suspenders
- UI shows current `getScope()` list, danger highlights, “open LSPosed scope” guidance
- Optional `requestScope([Photos])` on first launch
- Global device/feature prefs still shared (simple)

**Pros**

- Matches product goal: Photos recommended, user multi-select
- Reduces catastrophic mis-scope without re-locking to Photos
- Still one prefs group (simple remote-pref model)

**Cons**

- Denylist maintenance; false positives/negatives
- Strict mode can confuse users (“I scoped it in LSPosed but module ignores it”) — needs clear UI
- Slightly more test surface

**Fit:** Best balance for Pixelify Infinity’s risk profile.

---

### Option C — Full multi-app product (per-package profiles)

**Mechanism**

- B’s metadata + denylist
- Remote prefs structure:
  - Global: defaults
  - Per-package: `prefs_<package>` or JSON map under one group (size limits — remote prefs are **not** for large blobs; wiki says large content → remote files)
- UI: pick installed apps, toggle device spoof / feature spoof independently, import/export includes per-app map
- Possibly curated “known gallery apps” recommended extras in `scope.list`

**Pros**

- Maximum flexibility; power-user friendly
- Can disable Feature spoof on apps that only need Build spoof (or reverse)

**Cons**

- Largest implementation + support cost
- Easy to over-engineer vs current ~single-target product
- Remote prefs change listeners / process restart still required for live updates

**Fit:** Phase 2+ only if users demand per-app configs.

---

## 4. Recommended default

**Ship Option B (recommended default + safety rails), phased:**

| Phase | Deliver |
|-------|---------|
| **B0 (must)** | `staticScope=false`; remove Photos-only early-return; keep Photos-only `scope.list`; docs/UI “recommended not exclusive”; safety warnings |
| **B1** | Built-in danger denylist + log/skip; surface `getScope()` in UI |
| **B2 (optional)** | Strict allowlist pref; `requestScope(Photos)` helper; generalize force-stop |
| **C (later)** | Per-package profiles only if needed |

**Default user experience**

1. Install / enable module → LSPosed preselects **Google Photos**.
2. Advanced users may add other apps in LSPosed scope.
3. Module applies the same device/feature prefs to each non-denied scoped process.
4. Product copy never claims multi-app spoofing is “safe” or “supported for banking / Play Integrity”.

**Package ID:** unchanged (`io.github.samson910022.pixelifyphotos`).

---

## 5. Safety warnings to show users

Show prominently in README, in-app dialog (first multi-scope or always under Advanced), and SUPPORT:

1. **Recommended scope is Google Photos only.** Extra apps are unsupported advanced use.
2. **Never scope** (examples): Google Play Services, Play Store, system framework, payment/banking/wallet apps, work profile MDM agents, attestation checkers.
3. **Build / fingerprint spoof is process-wide for that app.** Spoofed `Build.*` and `SystemProperties` can break updates, DRM assumptions, device-specific APIs, crash loops, or trigger fraud/risk engines.
4. **Play Integrity / SafetyNet are not solved by this module** and may be **worsened** if GMS or dependent apps see inconsistent device identity. Root + Xposed already affect integrity; device spoof adds another signal apps can use.
5. **Feature flags are not Photos-exclusive.** `com.google.android.feature.PIXEL_*` answers affect any app calling `hasSystemFeature`.
6. **Force-stop every newly scoped app** after config changes (prefs are remote, but many reads happen at startup).
7. **No warranty** — account bans, broken apps, or failed payments are user risk.
8. **Do not use for impersonation / fraud.** Module is for personal feature experimentation (Photos Unlimited-style unlocks historically).

---

## 6. Test plan outline

### 6.1 Metadata / install

- [ ] APK contains `META-INF/xposed/module.prop` with `staticScope=false`
- [ ] `scope.list` still lists Photos (and only intended recommended packages)
- [ ] LSPosed manager shows Photos as recommended / auto-selected on clean enable
- [ ] Manager allows selecting a second user app (non-static behavior)
- [ ] Mirror `SCOPE` JSON matches `scope.list` policy

### 6.2 Hook correctness

- [ ] Photos only scoped: Device VERIFY + Feature spoof behave as today
- [ ] Photos + harmless second app (e.g. a simple feature-test APK): both receive hooks; logs show both package names
- [ ] `isFirstPackage=false` path still skipped
- [ ] Denylist packages: module loads but skips spoof (B1) with clear log
- [ ] Prefs change in module UI → force-stop target → new device profile applied (remote prefs path)

### 6.3 Regression

- [ ] Device “None” / empty features still pass-through for FeatureSpoofer
- [ ] Android 17 multi-strategy Build write path unchanged
- [ ] Native lib load from module `nativeLibraryDir` / host extract still works in non-Photos process
- [ ] Import/export config still works
- [ ] Unit tests + `lint` + assemble gate

### 6.4 Negative / safety

- [ ] Documented manual test: do **not** automate against real banking apps; use stubs
- [ ] Confirm module does **not** hook `system_server` unless explicitly scoped (should not be in scope.list)
- [ ] Play Integrity checker app left **unscoped** still reflects device baseline (control)

### 6.5 Docs / i18n

- [ ] EN + zh-TW/zh-CN/ja strings no longer mandate exclusive Photos scope
- [ ] Site install steps updated consistently

---

## 7. Source links + confidence

| # | Topic | Source | Confidence |
|---|-------|--------|------------|
| 1 | Modern module layout: `java_init.list`, `scope.list`, `module.prop`, `staticScope` meaning | [LSPosed wiki — Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API) | **High** (primary spec) |
| 2 | Remote Preferences vs XSharedPreferences; remote files for large content; dynamic scope via service | Same wiki + [libxposed/service](https://github.com/libxposed/service) | **High** |
| 3 | Legacy recommended scope via `xposedscope` meta-data (not used by this modern module) | [LSPosed wiki — Module Scope](https://github.com/LSPosed/LSPosed/wiki/Module-Scope) | **High** for legacy; **N/A** for modern packaging |
| 4 | Manager parses `staticScope` + `scope.list`; recommended auto-select / labeling | [ModuleUtil.java](https://raw.githubusercontent.com/LSPosed/LSPosed/master/app/src/main/java/org/lsposed/manager/util/ModuleUtil.java), [ScopeAdapter.java](https://raw.githubusercontent.com/LSPosed/LSPosed/master/app/src/main/java/org/lsposed/manager/adapters/ScopeAdapter.java) | **High** for parse + recommend UX; **Medium** that `staticScope` is *enforced* (field parsed, **not referenced** in ScopeAdapter/`ConfigManager` on inspected master paths) |
| 5 | Example modern `module.prop` / `scope.list` | [libxposed/example module.prop](https://raw.githubusercontent.com/libxposed/example/master/app/src/main/resources/META-INF/xposed/module.prop) (`staticScope=true`), [scope.list](https://raw.githubusercontent.com/libxposed/example/master/app/src/main/resources/META-INF/xposed/scope.list) | **High** |
| 6 | `XposedService.requestScope` / `getScope` / `getRemotePreferences` | [XposedService.java](https://raw.githubusercontent.com/libxposed/service/master/service/src/main/java/io/github/libxposed/service/XposedService.java) | **High** |
| 7 | Lifecycle: `onPackageLoaded` / `onPackageReady` / `isFirstPackage` | [XposedModuleInterface Javadoc](https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html) | **High** |
| 8 | Play Integrity purpose / attestation context | [Android Developers — Play Integrity overview](https://developer.android.com/google/play/integrity/overview) | **High** for API purpose; **Medium** for interaction specifics with in-app Build spoof (depends on whether GMS is scoped) |
| 9 | Ecosystem discussion of fingerprint spoof / PIF / banking risk | Community (XDA, PIF forks, security blogs) | **Medium–Low** as implementation guidance; use only for user-warning rationale |
| 10 | Current Pixelify code constraints | Local: `PixelifyModule.kt`, `Constants.kt`, `FeatureSpoofer.kt`, `DeviceSpoofer.kt`, `module.prop`, `scope.list`, `AGENTS.md` | **High** |

**Google Search skill queries run**

1. `libxposed API 101 staticScope module.prop meaning` → wiki hit + AI overview aligned with wiki  
2. `LSPosed module scope user selectable vs static scope` → Module Scope wiki + manager source leads  
3. `META-INF/xposed scope.list recommended scope modern xposed` → modern API wiki  
4. `How Xposed modules recommend default apps but allow user multi-scope LSPosed` → install guides; weaker direct hits (synthesized from wiki + ScopeAdapter)  
5. `risks spoofing Build props … SafetyNet Play Integrity banking` → integrity / PIF ecosystem; mixed quality  
6. `best practices multi-package hook remote preferences libxposed getRemotePreferences` → thin organic results; practices derived from official service API + wiki comparison table  

---

## 8. Open questions for Samson (before implementation)

1. **Target apps:** Is multi-scope “any app the user picks”, or only a **curated gallery/photos-related list** extra to Photos?
2. **Feature spoof outside Photos:** Apply full FeatureSpoofer (including PIXEL_* and photos.* flags), **Build-only** for non-Photos, or user toggle?
3. **Denylist:** Approve Option B danger denylist (GMS / Play Store / …)? Any packages you want always blocked?
4. **Strict mode:** Want an in-module allowlist that can ignore LSPosed extras, or always trust LSPosed scope?
5. **UI scope:** Keep Photos-centric force-stop/open, or invest in multi-app management UI + `getScope()` display this release?
6. **`requestScope`:** Use service API to prompt recommended Photos scope on first run, or document-only?
7. **API 102:** Stay on 101.0.0 deps, or bump `targetApiVersion` / libraries for hot-reload diagnostics later?
8. **Naming / branding:** Keep “Pixelify Infinity” + package `…pixelifyphotos` while supporting multi-app (recommended), or plan a future display-name tweak only (// not package rename)?
9. **modules.lsposed.org `SCOPE`:** Keep advertising Photos-only on the store card even if APK allows multi-select (recommended for safer defaults)?
10. **Support policy:** Are non-Photos targets “best effort / no support”, or first-class?

---

## 9. Implementation checklist (for a future BUILD agent — not done here)

- [ ] `module.prop`: `staticScope=false`
- [ ] Keep `scope.list` → Photos (unless Q1 adds curated lines)
- [ ] `PixelifyModule`: replace Photos equality with allow-any / denylist / strict allowlist
- [ ] Strings + README + SUPPORT + site + `AGENTS.md` scope wording
- [ ] `distribution/xposed-repository/SCOPE` policy decision
- [ ] Optional: denylist constants + UI warning
- [ ] Tests for package gate helper (unit-test pure allow/deny logic)
- [ ] Manual LSPosed scope matrix on a device

---

## 10. Bottom line

**Two independent locks** currently force Photos-only:

1. **Declaration lock:** `staticScope=true` + single-line `scope.list` (manager recommendation / intended restriction).
2. **Code lock:** `PixelifyModule` package equality checks (absolute).

Product goal needs **(1) recommendation without exclusivity** → `staticScope=false`, Photos remains in `scope.list`, and **(2) code that honors LSPosed multi-scope** with **safety rails**.

**Recommended design:** Option **B** (Photos recommended, user multi-select, denylist + warnings, global remote prefs).  
**Do not** change application ID.  
**Do not** treat multi-app Build spoof as Play Integrity strategy.

---

*End of research deliverable.*
