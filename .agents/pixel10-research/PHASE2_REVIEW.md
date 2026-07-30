# PHASE2_REVIEW — Multi-app scope Option B (B0 + denylist)

**Verdict: PASS**

**Branch:** `feat/pixel10-multi-scope`  
**Date:** 2026-07-30  
**Reviewer role:** Independent Phase 2 code reviewer (strict)  
**Model:** cpa/grok-4.5  
**No commits made.**

**Inputs reviewed:**
- `.agents/pixel10-research/DEFINE_PLAN.md` (SCOPE-1, DENY-1, Phase 2)
- `.agents/pixel10-research/PHASE2_BUILDER_REPORT.md`
- `ScopePolicy.kt`, `PixelifyModule.kt`, `ScopePolicyTest.kt`
- `META-INF/xposed/module.prop`, `scope.list`, `distribution/xposed-repository/SCOPE`
- EN + zh-TW `strings.xml` relevant keys
- Optional unit tests re-run

---

## Checklist (required)

| # | Requirement | Result | Evidence |
|---|-------------|--------|----------|
| 1 | `staticScope=false` | **PASS** | `module.prop` line 3: `staticScope=false` (diff from `true`) |
| 2 | `scope.list` + `SCOPE` still Photos-only | **PASS** | `scope.list` = `com.google.android.apps.photos`; `SCOPE` = JSON array with that single package |
| 3 | No Photos-only hard gate in `PixelifyModule`; `isFirstPackage` kept | **PASS** | Diff removes `packageName != PACKAGE_NAME_GOOGLE_PHOTOS` and `when (… GOOGLE_PHOTOS)` gates; `isFirstPackage` retained in `onPackageLoaded` |
| 4 | Both loaded + ready use same `ScopePolicy.shouldSpoof` | **PASS** | Two call sites, both `ScopePolicy.shouldSpoof(params.packageName)`; no divergent inline package checks |
| 5 | Full Device+Feature spoof for allowed packages | **PASS** | Allowed path: early `DeviceSpoofer.hook` on load; on ready `FeatureSpoofer.hook` + `DeviceSpoofer.hook` with same global prefs (policy (a), same as former Photos flow) |
| 6 | DENY-1 exact set matches plan | **PASS** | `ScopePolicy.DENYLIST` exact 9-package set; tests assert set equality to plan list |
| 7 | `ScopePolicy` pure + unit tests cover required cases | **PASS** | No Android imports; tests: Photos allowed, each deny, random allowed, null/empty (+ exact-match / no-dup extras) |
| 8 | UI strings: no exclusive-only hard rule; risk warning present | **PASS** | EN/zh-TW: recommended scope + multi-app risk; hard “only Photos” removed from `module_not_enabled` |
| 9 | No signing / app id changes; Phase 2 scope only | **PASS** | No `app/build.gradle.kts` / Manifest applicationId diff; Phase 2 files are scope runtime + strings + module.prop (Phase 1 DeviceProps coexists on branch, out of this phase’s change set) |

---

## Detailed findings

### SCOPE-1

1. **`staticScope=false`** — confirmed in `app/src/main/resources/META-INF/xposed/module.prop`.
2. **Recommended scope remains Photos-only** — both embedded `scope.list` and distribution `SCOPE` mirror only `com.google.android.apps.photos`. Not expanded.
3. **Runtime gate removed** — previous hard equality / `when` on Photos is gone. Module trusts LSPosed scope, then soft-denies via `ScopePolicy`.
4. **`isFirstPackage` preserved** on `onPackageLoaded` only (correct for early path). `onPackageReady` has no Photos package switch residual.
5. **Full spoof policy (a)** — non-denylisted packages get Device + Feature with shared prefs, matching prior Photos behavior split across load/ready.
6. **Soft denylist** — deny returns early with warning log; module load still occurs (no throw / no disable).
7. **UI warnings** — multi-app risk and “recommended not exclusive” present (see strings).
8. **Photos force-stop/open convenience** — left intact in `ActivityMain` (allowed by plan).

### DENY-1 exact set

```
com.google.android.gms
com.android.vending
com.google.android.gsf
com.google.android.gsf.login
com.google.android.packageinstaller
com.google.android.permissioncontroller
com.android.settings
com.android.systemui
com.android.phone
```

Matches DEFINE_PLAN DENY-1 with zero missing / zero extra. Implemented as pure `object ScopePolicy` with `shouldSpoof` / `isDenied` / public `DENYLIST`.

### PixelifyModule wiring

- **Loaded:** `isFirstPackage` → `shouldSpoof` → `DeviceSpoofer.hook(..., allowFailureUi = false)`
- **Ready:** `shouldSpoof` → `FeatureSpoofer.hook` + `DeviceSpoofer.hook`
- No residual `Constants.PACKAGE_NAME_GOOGLE_PHOTOS` reference in module.
- Both paths use the **same** helper; gates cannot diverge by package string.

### ScopePolicy purity + tests

- Host-JVM pure: no `android.*` dependencies.
- `ScopePolicyTest` (6 tests, 0 failures):
  - Photos allowed (`Constants.PACKAGE_NAME_GOOGLE_PHOTOS`)
  - each DENY-1 denied + set equality
  - random `com.example.gallery` allowed
  - null / empty not spoofed
  - extras: no duplicates; exact-match (not prefix) near-misses allowed

### Strings (EN + zh-TW)

| Key | Assessment |
|-----|------------|
| `spoofs_build_and_features` | Recommended Photos + extra apps advanced/unsupported + never scope GMS/Store/system UI/banking — **risk warning present** |
| `module_not_enabled` | Hard exclusive wording removed (`scope it only to` / `作用範圍僅設為` → recommended language) |
| `please_force_stop_google_photos` | Mentions other scoped apps — aligns with multi-app |

**Non-blocking nit:** zh-TW `spoofs_build_and_features` uses「建議**僅**勾選 Google 相簿」which is slightly stronger than EN “recommended scope”, but still soft (“建議”) and not a hard exclusive rule. Acceptable for Phase 2; optional polish later for closer EN/zh parity.

### Signing / identity

- `applicationId` / `namespace` remain `io.github.samson910022.pixelifyphotos` (unchanged; no build file diff in Phase 2 work).
- No signing config edits in this phase.

### Out-of-phase (correctly not done here)

- getScope / requestScope UI, strict allowlist pref, per-package profiles, multi-app force-stop manager, expanding `scope.list`, README/CHANGELOG (Phase 3).

---

## Verification run

```text
export JAVA_HOME=/home/samson1357924/Android/jdk-17
export ANDROID_HOME=/home/samson1357924/Android/Sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest \
  --tests io.github.samson910022.pixelifyphotos.ScopePolicyTest \
  --tests io.github.samson910022.pixelifyphotos.DevicePropsTest \
  --rerun-tasks
→ BUILD SUCCESSFUL
```

| Suite | Tests | Failures | Errors |
|-------|------:|---------:|-------:|
| `ScopePolicyTest` | 6 | 0 | 0 |
| `DevicePropsTest` | 62 | 0 | 0 |

---

## Non-blocking nits (do **not** flip verdict)

1. Deny skip log conflates denylisted vs null/empty (`denylisted/invalid`); could branch on `isDenied` for clearer reason (plan asked to “log reason”).
2. zh-TW「建議僅勾選」slightly stronger than EN; optional wording align.
3. `isDenied` is tested but unused by `PixelifyModule` (only `shouldSpoof`) — fine for pure policy API surface.
4. No module-level unit test proving both hooks call policy (would need mocking libxposed); acceptable given pure policy tests + source inspection.

---

## Blocking issues

**None.**

---

## Final decision

**PASS** — Phase 2 SCOPE-1 + DENY-1 implementation meets DEFINE_PLAN. Safe to proceed to Phase 3 (docs/CHANGELOG) or continue the builder→reviewer loop on subsequent phases. No Phase 2 code fixes required for acceptance.
