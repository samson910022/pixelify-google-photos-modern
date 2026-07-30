# Pixel 6+ DeviceProps Review

**Date:** 2026-07-30  
**Repo:** `/home/samson1357924/projects/pixelify-google-photos-modern`  
**Branch:** `feat/pixel10-multi-scope`  
**Source under review:** `app/src/main/java/io/github/samson910022/pixelifyphotos/DeviceProps.kt`  
**Scope:** Research / data review only (no app code changes)  
**Method:** Independent cross-check via Google Search skill, GrapheneOS, MobileModels, Pixel-Props releases, vendor sysconfig dumps, factory/OTA-adjacent sources

---

## Executive summary

| Area | Result |
|---|---|
| **DEVICE / PRODUCT / MODEL identity (Pixel 6 Pro → 9 Pro XL)** | **Mostly HIGH confidence OK** |
| **Pixel 9a codename** | **BLOCKING WRONG** — file uses `tehua`; real codename is **`tegu`** |
| **Feature levels (flagships 6/7/8/9)** | **OK** (2021 / 2022 / 2023 / 2024) |
| **Feature levels (“a” mid-year SKUs)** | **Intentional overshoot vs stock** (6a→2022, 7a→2023, 8a→2024). Stock 8a tops at **2023 midyear**, not 2024 |
| **Fingerprints / ID / INCREMENTAL** | **Synthetic / unverified** for all post–Pixel 6 Pro modern entries. Shared `13115780` across **AP1A (A15) and BP1A (A16)** is suspicious |
| **Pixel 6 Pro fingerprint** | Real launch-era A12 (`SD1A.210817.036`) — **stale but authentic**, inconsistent with A15/A16 siblings |
| **Missing SKUs** | **Pixel 6 (`oriole`)**, **Pixel Fold (`felix`)**, **Pixel 9 Pro Fold (`comet`)** absent |
| **Pixel 10 series (reconfirm)** | **frankel / blazer / mustang / rango / stallion** HIGH; A16 launch `BD3A.*` and A17 current `CP2A.260705.006/15641320` confirmed via Pixel-Props |

### Verdict

**PASS (with required fixes)** — identity data for existing Pixel 6 Pro–9 Pro XL entries is good enough to keep building on, **but Pixel 9a must not ship as `tehua`**, and fingerprints should be replaced with Pixel-Props / factory-derived values before treating the table as “production accurate.”

If BUILD is defined as “ship user-facing device list including Pixel 9a,” then **Pixel 9a codename fix is blocking**.  
If BUILD is “add Pixel 10 only and leave 6–9 untouched,” identity of 6–9 is mostly fine; still document synthetic FPs and 8a feature overshoot.

---

## Scrutiny answers (task-specific)

### 1) Pixel 6 Pro still on Android 12 fingerprint while others on AP1A/BP1A?

| Aspect | Assessment |
|---|---|
| Authenticity of raven FP | **OK** — `google/raven/raven:12/SD1A.210817.036/7805805:user/release-keys` matches early stock Pixel 6 Pro |
| Consistency with rest of table | **Poor** — siblings use A15/A16-style synthetic builds |
| Photos spoof impact | **Usually OK** — Photos primarily keys off MODEL / feature flags more than build age; very old FP can look anomalous to integrity checkers if those props ever leak outside Photos scope |
| Recommendation | **Optional FIX:** refresh raven (and add oriole) to a real recent monthly from Pixel-Props / factory images for era consistency. Not blocking for Photos-only spoof identity |

### 2) Pixel 8a → featureLevel “Pixel 2024” while 8/8 Pro → “Pixel 2023”?

| Aspect | Assessment |
|---|---|
| Stock reality | **Not correct as stock mapping.** `akita-vendor.mk` (ProjectEverest proprietary vendor) copies cumulative `pixel_experience_*.xml` through **`pixel_experience_2023.xml` + `pixel_experience_2023_midyear.xml`**. **No `2024` sysconfig on 8a.** |
| Pattern in this file | “a” phones are mapped one rung higher than same-year flagships (7a→2023, 8a→2024) — appears **deliberate max-feature spoof**, not stock fidelity |
| Recommendation | **Decide policy:**  
  - **Accuracy mode:** map 8a to a new `"Pixel 2023 mid-year"` rung (and similarly 7a → 2022 mid-year).  
  - **Max Photos spoof mode:** keep 8a at 2024 but **document as intentional overshoot**.  
  Current mapping is **not stock-correct**; treat as **optional FIX** unless product goal is authenticity |

### 3) Pixel 9a codename `tehua`?

| Source | Codename |
|---|---|
| MobileModels `google.md` | **`tegu`** (`GXQ96` / `GTF7P` / `G3Y12`) |
| GrapheneOS build targets | **`tegu` (Pixel 9a)** |
| OpenPhone / Lineage docs | **`tegu`**; sample stock FP `google/tegu/tegu:16/BP4A.260105.004.E1/14587043` |
| Firmware dump | `tegu-user-15-BD4A.250405.003-13238919` → FP `google/tegu/tegu:15/BD4A.250405.003/13238919:user/release-keys` |
| OTAChecker | `google/tegu/tegu:16/CP1A.260505.005/15081906` → `17/CP2A.260605.012/15430684` |
| DeviceProps.kt | **`tehua` / `tehua`** |

**Verdict: BLOCKING FIX.** `tehua` is incorrect. Replace DEVICE/PRODUCT with **`tegu`** and rebuild FINGERPRINT/ID/INCREMENTAL accordingly. No credible public source uses `tehua` for Pixel 9a.

### 4) Missing Pixel 6 non-Pro?

**Confirmed missing.** Codename **`oriole`** is universal (GrapheneOS, MobileModels, Lineage, Android Police).  
**Optional** for completeness (parity with 6 Pro); not required to implement Pixel 10.

Also missing folds: **Pixel Fold `felix`**, **Pixel 9 Pro Fold `comet`**.

### 5) Shared INCREMENTAL `13115780` across many devices?

| Observation | Meaning |
|---|---|
| Same INCREMENTAL across **different devices on the same monthly train** | **Normal.** Pixel-Props 2026-07-11 shows `CP2A.260705.006` / **`15641320`** on frankel, blazer, cheetah, husky, caiman, komodo, etc. |
| Same INCREMENTAL **`13115780` on both `AP1A.*` (A15) and `BP1A.*` (A16)** in DeviceProps | **Abnormal / synthetic.** Different major release letters should not share one fabricated incremental |
| Search for exact `AP1A.250405.002` + `13115780` | **No solid factory/OTA hits** found |
| Nearby real April-2025 build | Pixel 9a dump: **`BD4A.250405.003` / `13238919`** (A15) — same security-patch *date family*, different ID + incremental |

**Verdict:** Treat modern FPs in DeviceProps as **template-synthesized** (likely from v5.1 bulk add, June 2026 commit). Fine as placeholders; **replace from Pixel-Props / Google OTA before claiming accuracy.**

---

## Per-device table

Legend for **Verdict:**  
- **OK** — identity good; no blocking change  
- **FIX** — change recommended or required (see notes)  
- **Confidence** — for DEVICE/PRODUCT/MODEL correctness unless noted

| Marketing name | DEVICE | PRODUCT | MODEL | featureLevelName (current) | featureLevel expected (stock) | Sample real / better FINGERPRINT | Match vs DeviceProps | Verdict | Conf. |
|---|---|---|---|---|---|---|---|---|---|
| **Pixel 6** *(missing)* | `oriole` | `oriole` | `Pixel 6` | — | Pixel 2021 | (add from factory/Pixel-Props) | **ABSENT** | **OPTIONAL ADD** | HIGH |
| **Pixel 6 Pro** | `raven` | `raven` | `Pixel 6 Pro` | Pixel 2021 | Pixel 2021 | Current: `google/raven/raven:12/SD1A.210817.036/7805805:user/release-keys` (authentic launch). Prefer recent monthly if refreshing | Identity **MATCH**; FP **stale** | **OK** (optional FP refresh) | HIGH |
| **Pixel 6a** | `bluejay` | `bluejay` | `Pixel 6a` | Pixel 2022 | ~2021 / 2022 mid-year | Example real older: `google/bluejay/bluejay:14/AP1A.240405.002/11480754…`. Prefer current Pixel-Props monthly | Identity **MATCH**; FP **synthetic** | **FIX FP**; feature overshoot **NOTE** | HIGH / MED FP |
| **Pixel 7** | `panther` | `panther` | `Pixel 7` | Pixel 2022 | Pixel 2022 | Prefer Pixel-Props current (e.g. family `CP2A.260705.006/15641320` style if still supported) | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 7 Pro** | `cheetah` | `cheetah` | `Pixel 7 Pro` | Pixel 2022 | Pixel 2022 | Pixel-Props: `cheetah-user 17 CP2A.260705.006 15641320` → `google/cheetah/cheetah:17/CP2A.260705.006/15641320:user/release-keys` | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 7a** | `lynx` | `lynx` | `Pixel 7a` | Pixel 2023 | ~2022 mid-year | Prefer real monthly; GrapheneOS target `lynx` | Identity **MATCH**; FP synthetic; feature **overshoot** | **FIX FP**; feature **NOTE** | HIGH |
| **Pixel 8** | `shiba` | `shiba` | `Pixel 8` | Pixel 2023 | Pixel 2023 | Prefer Pixel-Props monthly for shiba | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 8 Pro** | `husky` | `husky` | `Pixel 8 Pro` | Pixel 2023 | Pixel 2023 | Pixel-Props: `husky-user 17 CP2A.260705.006 15641320` | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 8a** | `akita` | `akita` | `Pixel 8a` | **Pixel 2024** | **Pixel 2023 mid-year** (sysconfig through 2023_midyear) | Prefer real akita monthly | Identity **MATCH**; feature **not stock**; FP synthetic | **FIX/DECIDE feature**; **FIX FP** | HIGH id / MED feature |
| **Pixel 9** | `tokay` | `tokay` | `Pixel 9` | Pixel 2024 | Pixel 2024 | Prefer Pixel-Props / OTA tokay (A16/A17). Current file uses synthetic `BP1A.250405.002/13115780` | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 9 Pro** | `caiman` | `caiman` | `Pixel 9 Pro` | Pixel 2024 | Pixel 2024 | Pixel-Props: `caiman-user 17 CP2A.260705.006 15641320` | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 9 Pro XL** | `komodo` | `komodo` | `Pixel 9 Pro XL` | Pixel 2024 | Pixel 2024 | Pixel-Props: `komodo-user 17 CP2A.260705.006 15641320` | Identity **MATCH**; FP **synthetic** | **FIX FP** | HIGH |
| **Pixel 9a** | **`tehua` (WRONG)** | **`tehua` (WRONG)** | `Pixel 9a` | Pixel 2024 | Pixel 2024 or 2024 mid-year (TBD); **DEVICE must be tegu** | Real samples: `google/tegu/tegu:15/BD4A.250405.003/13238919:user/release-keys`; `…:16/BP4A.260105.004.E1/14587043…`; `…:16/CP1A.260505.005/15081906…`; `…:17/CP2A.260605.012/15430684…` | **MISMATCH codename + FP** | **BLOCKING FIX** | **HIGH (tegu)** |
| **Pixel Fold** *(missing)* | `felix` | `felix` | `Pixel Fold` | — | ~2023 | Pixel-Props has Felix modules | **ABSENT** | **OPTIONAL ADD** | HIGH |
| **Pixel 9 Pro Fold** *(missing)* | `comet` | `comet` | `Pixel 9 Pro Fold` | — | Pixel 2024 | Pixel-Props has Comet modules | **ABSENT** | **OPTIONAL ADD** | HIGH |

### Current DeviceProps values (as read from file)

| Device | Current FINGERPRINT | ID | INCREMENTAL | SECURITY_PATCH | AndroidVersion label |
|---|---|---|---|---|---|
| Pixel 6 Pro | `google/raven/raven:12/SD1A.210817.036/7805805:user/release-keys` | *(none extra)* | *(none)* | *(none)* | S 12.0 |
| Pixel 6a | `google/bluejay/bluejay:15/AP1A.250405.002/13115780:user/release-keys` | AP1A.250405.002 | 13115780 | 2025-04-05 | Android 15 |
| Pixel 7 | `google/panther/panther:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 7 Pro | `google/cheetah/cheetah:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 7a | `google/lynx/lynx:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 8 | `google/shiba/shiba:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 8 Pro | `google/husky/husky:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 8a | `google/akita/akita:15/AP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 15 |
| Pixel 9 | `google/tokay/tokay:16/BP1A.250405.002/13115780:user/release-keys` | BP1A.250405.002 | 13115780 | 2025-04-05 | Android 16 |
| Pixel 9 Pro | `google/caiman/caiman:16/BP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 16 |
| Pixel 9 Pro XL | `google/komodo/komodo:16/BP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 16 |
| Pixel 9a | `google/tehua/tehua:16/BP1A.250405.002/13115780:user/release-keys` | same | same | same | Android 16 |

---

## Required fixes before BUILD

### Blocking

1. **Pixel 9a codename**  
   - Change `DEVICE` / `PRODUCT` from `tehua` → **`tegu`**.  
   - Change FINGERPRINT to a real tegu build, e.g.  
     - Launch-ish A15: `google/tegu/tegu:15/BD4A.250405.003/13238919:user/release-keys`  
     - Recent A17 (align with Pixel-Props style): `google/tegu/tegu:17/CP2A.260705.006/15641320:user/release-keys` *(confirm module exists for tegu in chosen release; if not, use latest OTAChecker/factory value)*  
   - Update `ID`, `INCREMENTAL`, `SECURITY_PATCH`, and `androidVersion` to match chosen FP.  
   - **Do not ship `tehua` under any policy.**

### Strongly recommended (treat as blocking if claiming “accurate props”)

2. **Replace synthetic `AP1A.250405.002` / `BP1A.250405.002` / `13115780` cluster**  
   - Source of truth: [Pixel-Props/build.prop releases](https://github.com/Pixel-Props/build.prop/releases) (OTA-extracted) or Google factory/OTA pages.  
   - Keep **same INCREMENTAL only within the same build ID train** (e.g. all `CP2A.260705.006` devices share `15641320` — good).  
   - Do **not** share one incremental across AP1A and BP1A.

3. **Align AndroidVersion with chosen fingerprint**  
   - If FP is `:17/CP2A…` → use `Android 17` (sdk 37).  
   - If FP is `:16/…` → `Android 16`.  
   - Current mix (A12 raven, synthetic A15 mid-gens, synthetic A16 P9) is inconsistent.

### Optional / policy

4. **Pixel 8a / 7a / 6a featureLevel policy**  
   - Document whether mid-year “a” devices intentionally overshoot for Photos features.  
   - If accuracy: add mid-year rungs (`Pixel 2022 mid-year`, `Pixel 2023 mid-year`, `Pixel 2024 mid-year`) and map a-phones accordingly. Stock 8a evidence strongly supports **2023 mid-year max**, not 2024.

5. **Add missing devices (completeness)**  
   - Pixel 6 `oriole` (pair with 6 Pro)  
   - Pixel Fold `felix`  
   - Pixel 9 Pro Fold `comet`  

6. **Pixel 6 Pro FP refresh**  
   - Optional; only for consistency / integrity aesthetics.

---

## Feature-level reference (stock ladder)

| Generation | Typical stock top experience flags | DeviceProps displayName |
|---|---|---|
| Pixel 6 / 6 Pro (2021 fall) | `PIXEL_2021_EXPERIENCE` | Pixel 2021 ✅ |
| Pixel 6a (2022 mid) | 2021 + mid-year ladder (exact mid-year year varies by dump) | File uses **Pixel 2022** (overshoot possible) |
| Pixel 7 / 7 Pro (2022 fall) | `PIXEL_2022_EXPERIENCE` | Pixel 2022 ✅ |
| Pixel 7a (2023 mid) | through ~2022 mid-year / early 2023 | File uses **Pixel 2023** (overshoot) |
| Pixel 8 / 8 Pro (2023 fall) | `PIXEL_2023_EXPERIENCE` | Pixel 2023 ✅ |
| Pixel 8a (2024 mid) | through **`PIXEL_2023` + `PIXEL_2023_MIDYEAR`** (confirmed in akita vendor mk) | File uses **Pixel 2024** ❌ stock / ✅ max-spoof |
| Pixel 9 series (2024 fall) | `PIXEL_2024_EXPERIENCE` | Pixel 2024 ✅ |
| Pixel 9a (2025 mid) | expect 2024 / 2024 mid-year (not fully dumped here) | Pixel 2024 plausible for max spoof; confirm mid-year if accuracy |

Note: DeviceProps `getFeaturesUpTo()` is cumulative by list order — mid-year rungs are **not** present after 2021 mid-year in the current `allFeatures` list (2022/2023/2024 mid-year missing).

---

## Pixel 10 series reconfirm (Android 16 fingerprints)

Independent reconfirm (also in `PIXEL10_DEVICE_PROPS_RESEARCH.md`):

| Marketing | DEVICE | PRODUCT | MODEL | Launch-era A16 sample | Current A17 sample (Pixel-Props 20260711) |
|---|---|---|---|---|---|
| Pixel 10 | `frankel` | `frankel` | `Pixel 10` | `google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys` | `google/frankel/frankel:17/CP2A.260705.006/15641320:user/release-keys` |
| Pixel 10 Pro | `blazer` | `blazer` | `Pixel 10 Pro` | `…/blazer/blazer:16/BD3A.250721.001/13808258…` | `…/blazer/blazer:17/CP2A.260705.006/15641320…` |
| Pixel 10 Pro XL | `mustang` | `mustang` | `Pixel 10 Pro XL` | `…/mustang/mustang:16/BD3A.250721.001/13808258…` | `…/mustang/mustang:17/CP2A.260705.006/15641320…` |
| Pixel 10 Pro Fold | `rango` | `rango` (convention) | `Pixel 10 Pro Fold` | **Stock FP still weaker / may need dump** | GrapheneOS target exists; Pixel-Props module coverage less complete |
| Pixel 10a | `stallion` | `stallion` (convention) | `Pixel 10a` | **Stock FP weaker** | GrapheneOS + MobileModels HIGH for codename |

Confidence: **frankel/blazer/mustang HIGH**; **rango/stallion codenames HIGH**, full stock prop modules **MED**.

Suggested feature rung for 10 series: **`Pixel 2025`** with  
`com.google.android.feature.PIXEL_2025_EXPERIENCE` (+ Photos PRELOAD by historical pairing; PRELOAD string MED/LOW verification).

---

## Sources consulted

- DeviceProps.kt (this repo, branch `feat/pixel10-multi-scope`)
- Google Search skill queries (codenames, FPs, midyear features, Pixel-Props, factory IDs)
- [GrapheneOS build targets](https://grapheneos.org/build) — oriole…tegu…frankel…stallion
- [MobileModels google.md](https://github.com/KHwang9883/MobileModels/blob/master/brands/google.md) — codenames + model SKUs
- [Pixel-Props/build.prop releases](https://github.com/Pixel-Props/build.prop/releases) — real shared INCREMENTAL pattern; CP2A/BD3A samples
- [OpenPhone tegu.md](https://github.com/secondly-com/OpenPhone/blob/main/docs/devices/tegu.md) — Pixel 9a stock FP
- [gm-stuffs/google_tegu_dump](https://github.com/gm-stuffs/google_tegu_dump) — `BD4A.250405.003/13238919` build.prop
- [ProjectEverest akita-vendor.mk](https://github.com/ProjectEverest-Devices/proprietary_vendor_google_akita/blob/14/akita-vendor.mk) — 8a sysconfig through 2023_midyear
- OTAChecker tegu OTA metadata — CP1A→CP2A progression
- Prior research: `.agents/pixel10-research/PIXEL10_DEVICE_PROPS_RESEARCH.md`
- Upstream/original Pixelify DeviceProps (Xposed-Modules-Repo) — historical baseline stopped at Pixel 6 Pro / Pixel 2021

---

## Final verdict

### **PASS (with required fixes)**

**Rationale**

- Codename/MODEL identity for **Pixel 6 Pro, 6a, 7, 7 Pro, 7a, 8, 8 Pro, 8a, 9, 9 Pro, 9 Pro XL** is solid (HIGH).  
- Feature levels for **fall flagships** match the intended year ladder.  
- **One hard data bug:** Pixel 9a **`tehua` → must be `tegu`**.  
- Modern fingerprints are **good enough as placeholders** for Photos MODEL spoofing but **not verified stock builds**; replace from Pixel-Props before accuracy claims.  
- Pixel 10 codenames/fingerprints for implementation planning remain **confirmed**.

**Minimum gate to BUILD device-list work that includes 9a:**

1. Fix 9a to `tegu` + real fingerprint family.  
2. Explicitly accept or rewrite synthetic FP cluster.  
3. Record 8a feature-level policy (stock mid-year vs max spoof).

**Do not FAIL the whole table** solely for missing oriole/felix/comet or for raven’s old-but-real A12 FP — those are optional completeness/consistency items.
