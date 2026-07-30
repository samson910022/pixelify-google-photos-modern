# DEFINE + PLAN — Pixel 10 full series + multi-app scope + default Pixel XL

**Branch:** `feat/pixel10-multi-scope`  
**Date:** 2026-07-30  
**Status:** REVISED after DEFINE_PLAN_REVIEW **FAIL** (v2 residuals R1/R2) — ready for re-review  
**Revision:** v3 (addresses B1–B5 + R1/R2 + nits)

**Research inputs:**
- `PIXEL10_DEVICE_PROPS_RESEARCH.md`
- `MULTI_APP_SCOPE_RESEARCH.md`
- `PIXEL6PLUS_PROPS_REVIEW.md` (PASS w/ required fixes)
- `DEFINE_PLAN_REVIEW.md` (FAIL v1)

---

## Locked product decisions (Samson)

1. Pixel **10 full series** in UI list: 10 / 10 Pro / 10 Pro XL / 10 Pro Fold / 10a  
2. Pixel 10 series Android version label: **Android 16**  
3. Multi-app scope: Photos recommended; user multi-select; **Option B**  
4. First-open default: **Pixel XL**  
5. App id / signing unchanged  
6. Each phase: builder → independent reviewer → fix loop until PASS  

---

## Hard policies (frozen for this PR — no builder invention)

### FP-1 — No fabricated fingerprints
- **Never invent** FINGERPRINT / ID / INCREMENTAL / SECURITY_PATCH.
- Only values with a **cited** source (Pixel-Props OTA extract, factory image, GrapheneOS/MobileModels-backed dump listed in research) may be written into props maps.
- If a device must appear in the spinner but lacks a cited FP: ship **identity props only** (`BRAND`, `MANUFACTURER`, `DEVICE`, `PRODUCT`, `MODEL`) + `featureLevelName` + `androidVersion`, **omit** FP/ID/INCREMENTAL/SECURITY_PATCH keys, and mark experimental in CHANGELOG.

### FP-2 — Pinned complete A16 props (10 / Pro / Pro XL)

| Device | DEVICE=PRODUCT | MODEL | featureLevel | androidVersion | FINGERPRINT | ID | INCREMENTAL | SECURITY_PATCH | Source |
|--------|----------------|-------|--------------|----------------|-------------|----|-------------|----------------|--------|
| Pixel 10 | frankel | Pixel 10 | Pixel 2025 | Android 16 | `google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys` | BD3A.250721.001 | 13808258 | 2025-08-05 | Pixel-Props launch module / research §2.1 |
| Pixel 10 Pro | blazer | Pixel 10 Pro | Pixel 2025 | Android 16 | `google/blazer/blazer:16/BD3A.250721.001/13808258:user/release-keys` | same train | same | same | same |
| Pixel 10 Pro XL | mustang | Pixel 10 Pro XL | Pixel 2025 | Android 16 | `google/mustang/mustang:16/BD3A.250721.001/13808258:user/release-keys` | same train | same | same | same |

All three also: `BRAND=google`, `MANUFACTURER=Google`.

### FP-3 — Pixel 10 Pro Fold + Pixel 10a (full series without inventing FP)

| Device | DEVICE=PRODUCT | MODEL | BRAND | MANUFACTURER | featureLevel | androidVersion | FP keys |
|--------|----------------|-------|-------|--------------|--------------|----------------|---------|
| Pixel 10 Pro Fold | rango | Pixel 10 Pro Fold | google | Google | Pixel 2025 | Android 16 | **OMIT** FINGERPRINT/ID/INCREMENTAL/SECURITY_PATCH (experimental identity-only) |
| Pixel 10a | stallion | Pixel 10a | google | Google | **Pixel 2025** (frozen max-spoof; no mid-year rung this PR) | Android 16 | **OMIT** FINGERPRINT/ID/INCREMENTAL/SECURITY_PATCH (experimental identity-only) |

- Do **not** add `"Pixel 2025 mid-year"` feature level in this PR.
- CHANGELOG must say Fold/10a are experimental (codename HIGH, fingerprint incomplete).

### FP-4 — Pixel 9a blocking fix (cited)

| Field | Value |
|-------|--------|
| DEVICE / PRODUCT | **tegu** (not tehua) |
| MODEL | Pixel 9a |
| featureLevel | Pixel 2024 (unchanged policy) |
| androidVersion | Android 16 |
| FINGERPRINT | `google/tegu/tegu:16/BP4A.260105.004.E1/14587043:user/release-keys` |
| ID | `BP4A.260105.004.E1` |
| INCREMENTAL | `14587043` |
| SECURITY_PATCH | **OMIT** this key (no cited patch date found for this exact BP4A.260105.004.E1 build in research). Do **not** keep old tehua-era `2025-04-05`. Never leave `tehua` / `BP1A.250405.002` / `13115780` residues on the 9a entry. |
| Source | OpenPhone/Lineage / research PIXEL6PLUS samples (A16 tegu FP/ID/INCREMENTAL). Patch date intentionally omitted under FP-1 rather than guessed. |

### FP-5 — Optional same-pass adds
- **Pixel 6 (`oriole`)**: add if builder uses a **cited** FP from research/Pixel-Props; else skip (not blocking).
- Do **not** bulk-refresh all 6a–9 synthetic FPs in this PR unless trivial; document remaining synthetic cluster as known debt.
- Keep 8a→Pixel 2024 max-spoof overshoot as intentional (document only).

### FEAT-1 — Pixel 2025 feature level
```
Features(
  "Pixel 2025", // Pixel 10 series
  "com.google.android.feature.PIXEL_2025_EXPERIENCE",      // HIGH — sysconfig
  "com.google.android.apps.photos.PIXEL_2025_PRELOAD",    // MED/LOW — historical pairing; not factory-confirmed
)
```
No mid-year feature rung this PR.

### DEFAULT-1
- `defaultDeviceName = "Pixel XL"`
- `defaultFeatures = getFeaturesUpTo("Pixel 2016")` (matches Pixel XL featureLevel)
- **No migration** of already-saved `PREF_DEVICE_TO_SPOOF`; only first-run / missing-pref / reset-settings paths pick up Pixel XL.

### SCOPE-1 — Option B for this PR = **B0 + denylist** (explicit)

**In scope:**
1. `module.prop`: `staticScope=false`
2. `scope.list` remains **only** `com.google.android.apps.photos` (recommended)
3. Mirror `distribution/xposed-repository/SCOPE` stays Photos-only JSON array matching scope.list
4. Remove Photos-only equality gates in `PixelifyModule` (`onPackageLoaded` / `onPackageReady`); keep `isFirstPackage`
5. For any non-denylisted scoped package: apply **both** DeviceSpoofer **and** FeatureSpoofer with the **same global prefs** as Photos (policy **(a)** full spoof)
6. Soft denylist: skip Device+Feature spoof, log reason (module still loads)
7. UI/docs warnings: multi-app risk; Photos recommended not exclusive
8. Keep Photos force-stop/open buttons as convenience shortcuts

**Out of this PR:**
- strict allowlist pref
- `requestScope` / `getScope()` UI
- per-package profiles
- multi-app force-stop manager UI
- expanding recommended scope.list beyond Photos

### DENY-1 — v1 denylist (exact set)
Skip spoof when `packageName` is any of:
- `com.google.android.gms`
- `com.android.vending`
- `com.google.android.gsf`
- `com.google.android.gsf.login`
- `com.google.android.packageinstaller`
- `com.google.android.permissioncontroller`
- `com.android.settings`
- `com.android.systemui`
- `com.android.phone`
Implement as a single pure `ScopePolicy` (or similar) object with unit tests — easy to extend later.
**Both** `PixelifyModule.onPackageLoaded` and `onPackageReady` must call the **same** ScopePolicy helper (deny → skip spoof; allow → Device+Feature) so gates cannot diverge.

### TEST-1 — pinned post-change contracts (DevicePropsTest)

Baseline today: **12** feature levels, **21** devices (incl None), default **Pixel 5**.

After Phase 1 minimum (no optional oriole):

| Metric | Expected |
|--------|----------|
| `allFeatures.size` | **13** (add Pixel 2025 only) |
| Feature name order tail | `…, Pixel 2023, Pixel 2024, Pixel 2025` |
| `allDevices.size` | **26** = 21 − 0 + 5 (10, 10 Pro, 10 Pro XL, 10 Pro Fold, 10a) **and** 9a still one entry (tegu fix in place) → **26** |
| Wait recount | Current 21 includes 9a. Add 5 new → **26**. Yes. |
| `defaultDeviceName` | `Pixel XL` |
| `defaultFeatures.size` | **1** level display set size from getFeaturesUpTo(Pixel 2016) → currently defaultFeatures is list of Features up to 2020 size 7; for XL should be **1** feature level object in list → `getFeaturesUpTo("Pixel 2016").size == 1` |
| 9a | `getDeviceProps("Pixel 9a").props["DEVICE"] == "tegu"`; no `tehua` anywhere in DeviceProps.kt |
| Pixel 10 | frankel + pinned FP string exact |
| Fold/10a | present; DEVICE/PRODUCT rango/stallion; BRAND/MANUFACTURER set; **assert `FINGERPRINT`/`ID`/`INCREMENTAL`/`SECURITY_PATCH` keys absent** |
| 9a residue | no `tehua`; 9a entry must not contain `BP1A.250405.002` or `13115780` |

#### Experimental identity-only carve-out (R1 — mandatory test updates)

Define helper set in tests, e.g.:
`EXPERIMENTAL_IDENTITY_ONLY = setOf("Pixel 10 Pro Fold", "Pixel 10a")`

**Existing test that must change:**
- `all device fingerprints contain brand slash device slash device pattern`
  - Apply **only** to non-None devices **not** in `EXPERIMENTAL_IDENTITY_ONLY`
  - For experimental devices: assert `FINGERPRINT` key is **absent** (or null), do **not** invent values to satisfy the old universal assert

**Other universal completeness checks:**
- Any “every non-None device has FINGERPRINT/ID/…” style assertion must use the same carve-out
- `all non-None devices have non-empty props` / BRAND=google / MANUFACTURER=Google / MODEL matches deviceName / androidVersion non-null: **still apply to experimental devices** (identity props required)
- Do **not** add new universal FP completeness checks that re-break Fold/10a

**Positive experimental asserts (mandatory):**
- Fold: DEVICE/PRODUCT=`rango`; FP/ID/INCREMENTAL/SECURITY_PATCH absent
- 10a: DEVICE/PRODUCT=`stallion`; same key absences

**Feature ladder after Pixel 10:**
- `getFeaturesUpToFromDeviceName("Pixel 10")` (and Pro / Pro XL / Fold / 10a) → **13** display names
- Existing `Pixel 9 Pro XL → 12` remains valid if 9 Pro XL stays on Pixel 2024

If optional Pixel 6 oriole added: `allDevices.size == 27` and test must assert oriole with cited FP (not experimental).

New `ScopePolicyTest`:
- Photos not denied
- each DENY-1 package denied (unique list)
- random app `com.example.gallery` allowed

---

## Phased implementation

### Phase 1 — DeviceProps + DevicePropsTest
Builder implements FP-2..5, FEAT-1, DEFAULT-1, TEST-1.  
Reviewer: exact string pins, no tehua, no invented Fold/10a FP, TEST-1 carve-out applied to universal FP test, 9a has no SECURITY_PATCH residue from tehua era, tests green.

### Phase 2 — Scope runtime Option B0+denylist
Builder implements SCOPE-1, DENY-1, ScopePolicy tests, minimal string updates for warnings.  
Reviewer: staticScope false; Photos recommended; full spoof policy for allowed apps; denylist works.

### Phase 3 — Docs + CHANGELOG
README/translations/SUPPORT/AGENTS/CONTRIBUTING + Unreleased notes; PRELOAD confidence; Fold/10a experimental; multi-app risk.  
Reviewer: no hard “only Photos”; honest experimental markers.

### Phase 4 — Full verify + final acceptance
`./gradlew test` (+ lint/assembleDebug if env allows). Final review. Then commits + PR only.

---

## Acceptance criteria (PR-level)

- [ ] Pixel 10 / Pro / Pro XL selectable with pinned A16 FPs
- [ ] Pixel 10 Pro Fold + 10a selectable as experimental identity-only (no fake FP)
- [ ] Pixel 2025 feature level present; PRELOAD documented MED/LOW
- [ ] Pixel 9a uses tegu + cited A16 FP
- [ ] Default first open = Pixel XL
- [ ] staticScope=false; scope.list Photos-only
- [ ] Non-Photos scoped apps get Device+Feature spoof unless denylisted
- [ ] Denylist packages skip spoof
- [ ] DevicePropsTest + ScopePolicyTest pass with pinned counts
- [ ] Docs: recommended scope, multi-app risk, experimental Fold/10a
- [ ] No signing / app id changes

## Risks
- PRELOAD 2025 may be no-op
- Identity-only Fold/10a weaker spoof until FP filled
- Full feature spoof outside Photos can affect non-denylisted apps — warned

