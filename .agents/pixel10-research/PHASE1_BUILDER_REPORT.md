# Phase 1 Builder Report — DeviceProps

**Branch:** `feat/pixel10-multi-scope`  
**Date:** 2026-07-30  
**Status:** READY FOR REVIEW

## Changes
### `DeviceProps.kt`
1. Added **Pixel 2025** feature level:
   - `PIXEL_2025_EXPERIENCE` (HIGH)
   - `PIXEL_2025_PRELOAD` (MED/LOW, comment noted)
2. Fixed **Pixel 9a**: `tehua` → **`tegu`**
   - FP: `google/tegu/tegu:16/BP4A.260105.004.E1/14587043:user/release-keys`
   - ID/INCREMENTAL cited; **SECURITY_PATCH omitted**
3. Added Pixel 10 series (Android 16):
   - **Pixel 10** frankel — full pinned BD3A props
   - **Pixel 10 Pro** blazer — full pinned BD3A props
   - **Pixel 10 Pro XL** mustang — full pinned BD3A props
   - **Pixel 10 Pro Fold** rango — identity-only (no FP keys)
   - **Pixel 10a** stallion — identity-only (no FP keys)
4. Defaults: `defaultDeviceName = "Pixel XL"`, `defaultFeatures = getFeaturesUpTo("Pixel 2016")`
5. No oriole (optional skipped)

### `DevicePropsTest.kt`
- Feature count **13**, device count **26**
- Default Pixel XL / 1 feature level
- tegu asserts + no tehua residue
- Pinned frankel/blazer/mustang FPs
- Experimental carve-out for Fold/10a FP format tests
- Pixel 10 feature ladder → 13 names

## Verification
```
./gradlew :app:testDebugUnitTest --tests 'io.github.samson910022.pixelifyphotos.DevicePropsTest'
→ BUILD SUCCESSFUL
```

## Out of Phase 1
- multi-app scope / module.prop (Phase 2)
- docs/CHANGELOG experimental notes (Phase 3)
- commit/PR

