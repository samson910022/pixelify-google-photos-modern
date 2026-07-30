# Pixel 10 Series — Device Props Research for Pixelify Infinity

**Date:** 2026-07-30  
**Repo target shape:** `app/src/main/java/io/github/samson910022/pixelifyphotos/DeviceProps.kt`  
**Scope:** Research only (no app source changes)

---

## Executive summary

| Marketing name | DEVICE (codename) | PRODUCT | MODEL | featureLevelName (proposed) | Launch Android | Confirmed? |
|---|---|---|---|---|---|---|
| Pixel 10 | `frankel` | `frankel` | `Pixel 10` | **Pixel 2025** | 16 (SDK 36) | **HIGH** |
| Pixel 10 Pro | `blazer` | `blazer` | `Pixel 10 Pro` | **Pixel 2025** | 16 (SDK 36) | **HIGH** |
| Pixel 10 Pro XL | `mustang` | `mustang` | `Pixel 10 Pro XL` | **Pixel 2025** | 16 (SDK 36) | **HIGH** |
| Pixel 10 Pro Fold | `rango` | `rango` (assumed) | `Pixel 10 Pro Fold` | **Pixel 2025** | 16 (SDK 36) | **MED–HIGH** (codename HIGH; full build.prop dump MED) |
| Pixel 10a | `stallion` | `stallion` (assumed) | `Pixel 10a` | **Pixel 2025 mid-year** *(or 2026 mid-year — unresolved)* | 16 (SDK 36) | **MED–HIGH** (codename HIGH; props/features MED) |

**Common props (all SKUs researched):**

- `BRAND` = `google`
- `MANUFACTURER` = `Google`
- SoC (flagship 10 / Pro / Pro XL / Pro Fold): **Tensor G5**
- SoC (Pixel 10a): **Tensor G4** (official Google Support)
- `ro.product.first_api_level` (flagship trio from extracted build.prop): **36** (Android 16)

**Feature flags (confirmed in Pixel-Props sysconfig extracted from official-style OTA props modules):**

```
com.google.android.feature.PIXEL_2025_EXPERIENCE
com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE
```

**Photos PRELOAD string:**

```
com.google.android.apps.photos.PIXEL_2025_PRELOAD
```

→ **Not found in public sysconfig dumps.** Follows long-standing Pixelify/DotOS pairing pattern with `PIXEL_YYYY_EXPERIENCE`. Treat as **MED/LOW** until verified on a real device / Photos APK feature probe.

---

## 1. Lineup existence (2025–2026)

| Device | Exists? | Evidence | Confidence |
|---|---|---|---|
| Pixel 10 | Yes (2025 flagship) | Google Support specs, Pixel-Props OTA modules, GrapheneOS targets | HIGH |
| Pixel 10 Pro | Yes | same | HIGH |
| Pixel 10 Pro XL | Yes | same | HIGH |
| Pixel 10 Pro Fold | Yes (2025 fold) | Google Support “Pixel 10 Pro Fold (2025)”, GrapheneOS `rango`, MobileModels | HIGH |
| Pixel 10a | Yes (2026 mid-cycle “a”) | Google Support “Pixel 10a phone (2026)”, GrapheneOS `stallion`, MobileModels | HIGH |
| Pixel 10 XL (non-Pro) | **No** | Android Police / Authority: no non-Pro XL; horse-theme codenames only Frankel/Blazer/Mustang/Rango | HIGH |
| Second fold beyond Pro Fold | **No** known 2025–2026 | — | HIGH |

---

## 2. Per-SKU technical data

### 2.1 Pixel 10 — `frankel`

| Field | Value | Confidence | Sources |
|---|---|---|---|
| Marketing name | Pixel 10 | HIGH | Google Support, store listings |
| DEVICE | `frankel` | HIGH | Pixel-Props system.prop, GrapheneOS, Android Police |
| PRODUCT | `frankel` | HIGH | `ro.product.name=frankel` in extracted system.prop |
| MODEL | `Pixel 10` | HIGH | `ro.product.model=Pixel 10` |
| BRAND | `google` | HIGH | system.prop |
| MANUFACTURER | `Google` | HIGH | system.prop |
| Hardware model IDs | US mmWave `GLBW0`; Global `GK2MP`; Japan `GL066` | HIGH | [MobileModels google.md](https://github.com/KHwang9883/MobileModels/blob/master/brands/google.md) |
| SoC | Tensor G5 | HIGH | system.prop `ro.soc.model=Tensor G5`; Google Support |
| first_api_level | 36 | HIGH | system.prop |
| device_family | `FL5BZ5MT5RG5` | HIGH | system.prop (family token for FL/BZ/MT/RG) |
| featureLevelName | **Pixel 2025** | HIGH for year mapping; MED for Photos-specific effect | sysconfig + chronology vs Pixel 9 = 2024 |
| Android (launch) | label `Android 16`, release `16`, sdk `36` | HIGH | launch build + Google Support “Launched with Android 16” |
| Android (current as of Jul 2026 research) | label `Android 17`, release `17`, sdk `37` | HIGH | Jul 2026 Pixel-Props modules |

**Fingerprints / builds**

| Era | FINGERPRINT | ID | INCREMENTAL | SECURITY_PATCH | Notes |
|---|---|---|---|---|---|
| Launch / early stock (Aug 2025) | `google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys` | `BD3A.250721.001` | `13808258` | `2025-08-05` | HIGH — Pixel-Props `20250829` module from Google OTA extraction |
| Recent (Jul 2026) | `google/frankel/frankel:17/CP2A.260705.006/15641320:user/release-keys` | `CP2A.260705.006` | `15641320` | `2026-07-05` | HIGH — Pixel-Props `20260711` |
| Intermediate A17 | `google/frankel/frankel:17/CP2A.260605.012/15430684:user/release-keys` | `CP2A.260605.012` | `15430684` | (Jun 2026 family) | HIGH |

**Suggested DeviceProps keys (aligned with existing Pixel 9 entries):**

```
BRAND, MANUFACTURER, DEVICE, PRODUCT, MODEL, FINGERPRINT, ID, INCREMENTAL, SECURITY_PATCH
```

---

### 2.2 Pixel 10 Pro — `blazer`

| Field | Value | Confidence |
|---|---|---|
| Marketing name | Pixel 10 Pro | HIGH |
| DEVICE / PRODUCT | `blazer` / `blazer` | HIGH |
| MODEL | `Pixel 10 Pro` | HIGH |
| BRAND / MANUFACTURER | `google` / `Google` | HIGH |
| Hardware model IDs | US mmWave `G4QUR`; Global `GEHN3`; Japan `GN4F5` | HIGH (MobileModels) |
| SoC | Tensor G5 | HIGH |
| first_api_level | 36 | HIGH |
| featureLevelName | **Pixel 2025** | HIGH/MED |
| Launch Android | 16 / SDK 36 | HIGH |
| Current (Jul 2026) | 17 / SDK 37 | HIGH |

**Fingerprints**

| Era | FINGERPRINT | ID | INCREMENTAL | SECURITY_PATCH |
|---|---|---|---|---|
| Launch | `google/blazer/blazer:16/BD3A.250721.001/13808258:user/release-keys` | `BD3A.250721.001` | `13808258` | `2025-08-05` |
| Recent | `google/blazer/blazer:17/CP2A.260705.006/15641320:user/release-keys` | `CP2A.260705.006` | `15641320` | `2026-07-05` |

---

### 2.3 Pixel 10 Pro XL — `mustang`

| Field | Value | Confidence |
|---|---|---|
| Marketing name | Pixel 10 Pro XL | HIGH |
| DEVICE / PRODUCT | `mustang` / `mustang` | HIGH |
| MODEL | `Pixel 10 Pro XL` | HIGH |
| BRAND / MANUFACTURER | `google` / `Google` | HIGH |
| Hardware model IDs | US mmWave `GUL82`; Global `G45RY`; Japan `GYPW4` | HIGH (MobileModels; AI Overview also cited GUL82) |
| SoC | Tensor G5 | HIGH |
| first_api_level | 36 | HIGH |
| featureLevelName | **Pixel 2025** | HIGH/MED |
| Launch Android | 16 / SDK 36 | HIGH |
| Current (Jul 2026) | 17 / SDK 37 | HIGH |

**Fingerprints**

| Era | FINGERPRINT | ID | INCREMENTAL | SECURITY_PATCH |
|---|---|---|---|---|
| Launch | `google/mustang/mustang:16/BD3A.250721.001/13808258:user/release-keys` | `BD3A.250721.001` | `13808258` | `2025-08-05` |
| Recent | `google/mustang/mustang:17/CP2A.260705.006/15641320:user/release-keys` | `CP2A.260705.006` | `15641320` | `2026-07-05` |

---

### 2.4 Pixel 10 Pro Fold — `rango`

| Field | Value | Confidence | Notes |
|---|---|---|---|
| Marketing name | Pixel 10 Pro Fold | HIGH | Google Support section “Pixel 10 Pro Fold (2025)” |
| DEVICE | `rango` | HIGH | GrapheneOS build target; leaks; MobileModels |
| PRODUCT | `rango` | **MED** | Strong convention (DEVICE==PRODUCT for modern Pixels); **no Pixel-Props module zip observed** for Rango as of 2026-07 |
| MODEL | `Pixel 10 Pro Fold` | HIGH (marketing) / MED (ro.product.model string assumed identical) |
| BRAND / MANUFACTURER | `google` / `Google` | HIGH (assumed; universal for Pixels) |
| Hardware model IDs | Global `GU0NP`; Japan `GM66V` | HIGH (MobileModels) |
| SoC | Tensor G5 | HIGH (Google Support) |
| Launch Android | 16 | HIGH (Support: Launched with Android 16) |
| featureLevelName | **Pixel 2025** | MED | Same calendar generation as non-fold 10 series |
| FINGERPRINT | **UNKNOWN** (needs OTA/device dump) | — | GrapheneOS ships `rango` builds but those are GrapheneOS fingerprints, not stock Google |

**Recommendation:** implement only after a stock `build.prop` / factory image / Pixel-Props-style extraction is available. Codename is safe; fingerprint is not.

---

### 2.5 Pixel 10a — `stallion`

| Field | Value | Confidence | Notes |
|---|---|---|---|
| Marketing name | Pixel 10a | HIGH | Google Support “Pixel 10a phone (2026)” |
| DEVICE | `stallion` | HIGH | GrapheneOS; Android Authority exclusive; MobileModels |
| PRODUCT | `stallion` | **MED** | Convention; not extracted from stock prop module |
| MODEL | `Pixel 10a` | HIGH marketing / MED prop string |
| BRAND / MANUFACTURER | `google` / `Google` | HIGH assumed |
| Hardware model IDs | US `GE1GQ`; Global `G4H7L`; Japan `GV0BP` | HIGH (MobileModels + Support network model labels) |
| SoC | **Tensor G4** (not G5) | HIGH | Official Support specs |
| Launch Android | 16 | HIGH | Support |
| featureLevelName | **Pixel 2025 mid-year** *candidate* | **LOW–MED** | See conflicts below |
| FINGERPRINT | **UNKNOWN** | — | No Pixel-Props Stallion zip in releases surveyed |

**Feature-level conflict for 10a:**

- Historical “a” phones sometimes map to `PIXEL_YYYY_MIDYEAR_EXPERIENCE` (3a/4a/5a).
- Recent “a” phones in *this* repo map to the full year flag of the *next* flagship generation year (8a → Pixel 2024, 9a → Pixel 2024).
- Sysconfig already defines both `PIXEL_2025_EXPERIENCE` and `PIXEL_2025_MIDYEAR_EXPERIENCE`.
- Pixel 10a is a **2026** device with **Tensor G4** → could also be `PIXEL_2026_MIDYEAR_*` once Google ships that sysconfig (not observed in extracted 10-series modules).

→ **Do not hard-commit 10a featureLevelName without a device feature dump.**

---

## 3. Feature flags deep dive

### 3.1 Confirmed system features (from extracted Magisk prop modules)

Path inside module (example Frankel Jul 2026):

`system/product/etc/sysconfig/pixel_experience_2025.xml`:

```xml
<feature name="com.google.android.feature.PIXEL_2025_EXPERIENCE" />
```

`pixel_experience_2025_midyear.xml`:

```xml
<feature name="com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE" />
```

These modules also ship the full historical ladder through 2024 midyear (for spoof completeness), matching Pixelify’s `getFeaturesUpTo()` design.

### 3.2 Proposed `allFeatures` addition (DeviceProps.kt shape)

Chronology: append **after** existing `"Pixel 2024"`:

```kotlin
Features("Pixel 2025", // Pixel 10 series
    "com.google.android.feature.PIXEL_2025_EXPERIENCE",
    "com.google.android.apps.photos.PIXEL_2025_PRELOAD",
),

// OPTIONAL — only if mid-year “a” / spring devices need a distinct rung:
Features("Pixel 2025 mid-year",
    "com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE",
    "com.google.android.apps.photos.PIXEL_2025_MIDYEAR_PRELOAD", // UNVERIFIED
),
```

| Flag | Confidence | Notes |
|---|---|---|
| `com.google.android.feature.PIXEL_2025_EXPERIENCE` | **HIGH** | Present in sysconfig from OTA-derived modules |
| `com.google.android.feature.PIXEL_2025_MIDYEAR_EXPERIENCE` | **HIGH** (exists) / **MED** (which SKU owns it) | Exists; assignment to 10a unproven |
| `com.google.android.apps.photos.PIXEL_2025_PRELOAD` | **MED/LOW** | Not in sysconfig; inferred from Pixelify historical pairing |
| `com.google.android.apps.photos.PIXEL_2025_MIDYEAR_PRELOAD` | **LOW** | Pure analogy; may not exist |

### 3.3 Mapping to Photos unlimited / editor features

Pixelify’s model: spoof **build props** + **cumulative feature flags up to `featureLevelName`**.  
For “latest Pixel experience” on Photos, `"Pixel 2025"` should sit at the top of `allFeatures` so `getFeaturesUpTo("Pixel 2025")` includes 2016→2025.

**Unverified:** whether Photos gates any 2025-only editor/AI on `PIXEL_2025_*` specifically vs model name / first_api / other Play features. Needs runtime probe on spoofed vs real Pixel 10.

---

## 4. Android version guidance for DeviceProps

Existing `allAndroidVersions` already includes:

- `Android 16` → release `"16"`, sdk `36`
- `Android 17` → release `"17"`, sdk `37`

| Use case | Recommended default `androidVersion` |
|---|---|
| Match launch-era authenticity | `getAndroidVersionFromLabel("Android 16")` |
| Match current stock (mid-2026) | `getAndroidVersionFromLabel("Android 17")` |
| Match existing Pixel 9 entries style in this fork | Pixel 9 entries already use **Android 16** with BP1A-style IDs — for Pixel 10, either launch **16/BD3A** or current **17/CP2A** is coherent |

**Note:** `ro.product.first_api_level=36` on flagship 10 series means they *launched* as Android 16 devices even when running 17.

---

## 5. Recommended DeviceEntries drafts (PROPOSAL — mark unverified)

> **UNVERIFIED / PROPOSAL ONLY.** Do not treat as committed implementation.  
> Prefer **recent CP2A** fingerprints if the goal is “current Pixel 10”; prefer **BD3A launch** if matching first retail images.  
> `SECURITY_PATCH` / `INCREMENTAL` should stay consistent with the chosen `FINGERPRINT`/`ID`.

### 5.1 allFeatures rung

```kotlin
// PROPOSAL — append after "Pixel 2024"
Features(
    "Pixel 2025", // Pixel 10 series
    "com.google.android.feature.PIXEL_2025_EXPERIENCE",
    "com.google.android.apps.photos.PIXEL_2025_PRELOAD", // UNVERIFIED PRELOAD string
),
```

### 5.2 Device entries (current Jul 2026 stock-style)

```kotlin
// PROPOSAL — Pixel 10 (frankel) — props HIGH from Pixel-Props extraction
DeviceEntries(
    "Pixel 10", hashMapOf(
        Pair("BRAND", "google"),
        Pair("MANUFACTURER", "Google"),
        Pair("DEVICE", "frankel"),
        Pair("PRODUCT", "frankel"),
        Pair("MODEL", "Pixel 10"),
        Pair("FINGERPRINT", "google/frankel/frankel:17/CP2A.260705.006/15641320:user/release-keys"),
        Pair("ID", "CP2A.260705.006"),
        Pair("INCREMENTAL", "15641320"),
        Pair("SECURITY_PATCH", "2026-07-05"),
    ),
    "Pixel 2025",
    getAndroidVersionFromLabel("Android 17"),
),

// PROPOSAL — Pixel 10 Pro (blazer)
DeviceEntries(
    "Pixel 10 Pro", hashMapOf(
        Pair("BRAND", "google"),
        Pair("MANUFACTURER", "Google"),
        Pair("DEVICE", "blazer"),
        Pair("PRODUCT", "blazer"),
        Pair("MODEL", "Pixel 10 Pro"),
        Pair("FINGERPRINT", "google/blazer/blazer:17/CP2A.260705.006/15641320:user/release-keys"),
        Pair("ID", "CP2A.260705.006"),
        Pair("INCREMENTAL", "15641320"),
        Pair("SECURITY_PATCH", "2026-07-05"),
    ),
    "Pixel 2025",
    getAndroidVersionFromLabel("Android 17"),
),

// PROPOSAL — Pixel 10 Pro XL (mustang)
DeviceEntries(
    "Pixel 10 Pro XL", hashMapOf(
        Pair("BRAND", "google"),
        Pair("MANUFACTURER", "Google"),
        Pair("DEVICE", "mustang"),
        Pair("PRODUCT", "mustang"),
        Pair("MODEL", "Pixel 10 Pro XL"),
        Pair("FINGERPRINT", "google/mustang/mustang:17/CP2A.260705.006/15641320:user/release-keys"),
        Pair("ID", "CP2A.260705.006"),
        Pair("INCREMENTAL", "15641320"),
        Pair("SECURITY_PATCH", "2026-07-05"),
    ),
    "Pixel 2025",
    getAndroidVersionFromLabel("Android 17"),
),
```

### 5.3 Optional launch-era variants (Android 16)

```kotlin
// PROPOSAL alternate — launch fingerprint example (Pixel 10)
// FINGERPRINT = google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys
// ID = BD3A.250721.001
// INCREMENTAL = 13808258
// SECURITY_PATCH = 2025-08-05
// androidVersion = getAndroidVersionFromLabel("Android 16")
```

### 5.4 Fold / 10a stubs (NOT ready to ship)

```kotlin
// PROPOSAL STUB — Pixel 10 Pro Fold (rango) — FINGERPRINT UNKNOWN
// DEVICE=rango, PRODUCT=rango?, MODEL=Pixel 10 Pro Fold, featureLevelName=Pixel 2025
// NEEDS: stock build.prop dump

// PROPOSAL STUB — Pixel 10a (stallion) — FINGERPRINT UNKNOWN, feature level UNRESOLVED
// DEVICE=stallion, PRODUCT=stallion?, MODEL=Pixel 10a
// featureLevelName candidates: "Pixel 2025 mid-year" | "Pixel 2025" | future "Pixel 2026 mid-year"
// NEEDS: stock build.prop + pm hasSystemFeature dump
```

---

## 6. Conflicts & caveats explicitly called out

1. **Android 16 vs 17 defaults**  
   - Official launch: Android 16.  
   - Current extracted stock modules (2026-06+): Android 17 (`CP2A.*`).  
   - Existing in-tree Pixel 9 entries use Android 16 labels with possibly synthetic/older BP1A IDs — consistency policy is a product decision.

2. **`ro.build.fingerprint` vs product fingerprint in Magisk modules**  
   - Recent Pixel-Props modules sometimes show *system* fingerprint as `google/generic_system_google/generic:17/...` while **product/vendor** fingerprints remain `google/<device>/<device>:17/...`.  
   - For DeviceProps spoofing (Build class fields), use the **product** form: `google/frankel/frankel:17/...` (matches historical Pixelify entries and launch-era modules where system==product).

3. **PIXEL_2025_PRELOAD not publicly confirmed**  
   - Only `PIXEL_2025_EXPERIENCE` confirmed in sysconfig.  
   - PRELOAD is extrapolated from module tradition.

4. **Pixel 9a codename in current DeviceProps is `tehua`**  
   - GrapheneOS / MobileModels use **`tegu`** for Pixel 9a.  
   - Out of scope to fix here, but relevant if validating codename sources — prefer GrapheneOS + MobileModels + extracted props over older guesses.

5. **Pixel-Props does not currently publish Rango/Stallion zips** (releases through 20260711 surveyed)  
   - Frankel/Blazer/Mustang only for the 10 generation in that project’s automated releases.

6. **Pixel 10a Tensor G4 vs flagship G5**  
   - Spoofing 10a as G5 (or vice versa) may matter for integrity / hardware-gated features; Photos likely cares more about feature flags + model string.

7. **AI Overview noise**  
   - Some Google AI Overviews mixed beta product names (`frankel_beta`) and speculative PIF JSON — **discounted** in favor of extracted `system.prop` and GrapheneOS docs.

---

## 7. What is still UNKNOWN / needs a device dump

| Item | Why needed |
|---|---|
| Stock `rango` (Fold) full `build.prop` / fingerprint | No public Pixel-Props extraction; GrapheneOS ≠ stock Google FP |
| Stock `stallion` (10a) full `build.prop` / fingerprint | Same |
| Confirmation of `ro.product.name` for Fold/10a if ever non-equal to DEVICE | Rare but possible for carrier SKUs |
| `pm list features` / Photos feature checks for `PIXEL_2025_PRELOAD` | Confirm PRELOAD string existence & Photos gating |
| Which mid-year flag Pixel 10a actually ships | 2025 midyear vs 2026 midyear vs full 2025 |
| Carrier-specific PRODUCT suffixes | Usually none on modern unlocked Pixels; still worth one `getprop` dump |
| Exact retail security patch at first boot per region | Launch module used `2025-08-05` |
| Whether Photos cares about `FINGERPRINT` build ID freshness | May only need MODEL + features for unlimited backup |

**Ideal dump commands (for a real device owner):**

```bash
getprop ro.product.device
getprop ro.product.name
getprop ro.product.model
getprop ro.product.brand
getprop ro.product.manufacturer
getprop ro.build.fingerprint
getprop ro.system.build.fingerprint
getprop ro.product.build.fingerprint
getprop ro.build.id
getprop ro.build.version.incremental
getprop ro.build.version.security_patch
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.product.first_api_level
getprop ro.soc.model
pm list features | grep -i pixel
pm list features | grep -i photos
```

---

## 8. Source index (with confidence)

| Source | URL / artifact | Used for | Confidence |
|---|---|---|---|
| Pixel-Props build.prop releases | https://github.com/Pixel-Props/build.prop/releases | Frankel/Blazer/Mustang system.prop + sysconfig XML (downloaded zips `Frankel/Blazer/Mustang_CP2A.260705.006`, launch `BD3A.250721.001`) | **HIGH** (OTA-derived automation) |
| Extracted sysconfig | `pixel_experience_2025.xml`, `..._2025_midyear.xml` inside modules | Feature flag strings | **HIGH** |
| GrapheneOS build targets | https://grapheneos.org/build | frankel/blazer/mustang/rango/stallion mapping | **HIGH** |
| GrapheneOS releases device list | https://grapheneos.org/releases | Confirms production support for all 5 codenames | **HIGH** |
| Google Support hardware specs | https://support.google.com/pixelphone/answer/7158570 | Official lineup, OS launch Android 16, Tensor G5/G4, 10a 2026 | **HIGH** |
| MobileModels google.md | https://github.com/KHwang9883/MobileModels/blob/master/brands/google.md | Regional model numbers + codenames | **HIGH** |
| Android Police codenames | https://www.androidpolice.com/google-pixel-10-series-codenames/ | Early codename leak confirmation | **MED–HIGH** (leaks later confirmed) |
| Android Authority 10a codename | https://www.androidauthority.com/exclusive-pixel-10a-pixel-11-codename-3516163/ | stallion | **MED–HIGH** |
| Wikipedia Pixel comparison | https://en.wikipedia.org/wiki/Comparison_of_Google_Pixel_smartphones | Cross-check codenames | **MED** |
| DeviceProps.kt (this repo) | local | Expected Kotlin data shape; Pixel 2024 precedent | **HIGH** (schema) |
| Google factory images hub | https://developers.google.com/android/images | Context only (page shell; per-device rows not fully extracted here) | **MED** |
| DotOS / Lineage PixelPropsUtils | searched; no strong public Pixel 10 hit in this pass | — | **LOW** for P10-specific |
| Photos PRELOAD 2025 | web/GitHub search | No hard hit | **LOW** confirmation |

---

## 9. Implementation checklist (for a later BUILD agent — not done here)

1. Add `"Pixel 2025"` to `allFeatures` (and optionally mid-year rung).  
2. Add DeviceEntries for Frankel / Blazer / Mustang with chosen fingerprint generation.  
3. Optionally stub Fold/10a behind a comment until dumps exist.  
4. Keep `allAndroidVersions` as-is (16 & 17 already present).  
5. Runtime-verify: spoof → Photos storage/editor behavior; `hasSystemFeature(PIXEL_2025_EXPERIENCE)`.  
6. Do **not** invent Rango/Stallion fingerprints.

---

## 10. Bottom line for Pixelify Infinity

- **Ship-ready research data:** Pixel 10 / 10 Pro / 10 Pro XL codenames, models, brand/manufacturer, launch + current fingerprints, Android 16→17, `PIXEL_2025_EXPERIENCE`.  
- **Safe featureLevelName:** `"Pixel 2025"`.  
- **PRELOAD string:** include by convention but label unverified.  
- **Fold + 10a:** codenames confirmed; **hold DeviceEntries** until stock prop dumps.  
- **Lineup:** 10, 10 Pro, 10 Pro XL, 10 Pro Fold (2025), 10a (2026). No non-Pro “Pixel 10 XL”.

---

*End of research report.*
