# PHASE4_REVIEW — Independent Phase 4 Final Acceptance for feat/pixel10-multi-scope

**Date:** 2026-07-30  
**Reviewer:** independent subagent (strict)  
**Model:** cpa/grok-4.5  
**Branch:** feat/pixel10-multi-scope  
**No commits**

## Verdict: **PASS**

All 10 PR-level acceptance criteria verified:

1. Pixel 10/Pro/Pro XL selectable with pinned A16 FPs (frankel/blazer/mustang BD3A.250721.001)  
2. Pixel 10 Pro Fold + 10a selectable as experimental identity-only (rango/stallion, FP/ID/INCREMENTAL/SECURITY_PATCH keys omitted)  
3. Pixel 2025 feature level present; `PIXEL_2025_PRELOAD` documented MED/LOW (historical, not factory-confirmed)  
4. Pixel 9a uses `tegu` + cited A16 FP/ID/INCREMENTAL (SECURITY_PATCH omitted; no tehua residue)  
5. Default first-open device = Pixel XL (`DeviceProps.defaultDeviceName`)  
6. `staticScope=false`; `scope.list` = `com.google.android.apps.photos` only (Photos-only)  
7. Non-Photos scoped packages receive full Device+Feature spoof (via `ScopePolicy.shouldSpoof` + global prefs) unless denylisted  
8. Denylist packages (exact set) skip spoof (soft skip with warning log; module load continues)  
9. All unit tests green (126 total, 0 failures across `DevicePropsTest`, `ScopePolicyTest`, `FeatureSpoofLogicTest`)  
10. Docs recommend Photos scope, multi-app risk, experimental Fold/10a honesty (README, translations, CHANGELOG Unreleased, SUPPORT, AGENTS)  
11. No signing or `applicationId` / namespace changes

## Evidence
- DeviceProps.kt: exact pins, experimental carve-out, 13 feature levels, default Pixel XL, no tehua  
- ScopePolicy.kt + PixelifyModule.kt: dual-path `shouldSpoof` + denylist exact match  
- ScopePolicyTest / DevicePropsTest: all contracts + counts pass  
- FeatureSpoofLogicTest: default names, Pixel 2025 flags, override logic  
- module.prop / scope.list / distribution/SCOPE: staticScope=false, Photos-only  
- README / CHANGELOG / translations: recommended + risk language  
- `./gradlew testDebugUnitTest` and `assembleDebug`: BUILD SUCCESSFUL  
- Static checks + python contract script: ALL_CONTRACTS PASS

## Remaining nits (non-blocking)
- `PHASE4_BUILDER_REPORT.md` not present in repo (process gap; parent builder expected to create before PR)  
- `ActivityMain.kt:118` overridePendingTransition deprecated  
- Site hero/landing still thin Photos-primary (marketing, not product rule)  
- Minor zh-TW wording alignment on risk text (already softer in r2)

## Blocking items
None.

## Next
Ready for commit + PR. Phase 4 complete.