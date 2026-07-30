# Phase 2 Builder Report — Multi-app scope Option B (B0 + denylist)

**Branch:** `feat/pixel10-multi-scope`  
**Date:** 2026-07-30  
**Status:** READY FOR REVIEW

## Changes
1. `app/src/main/resources/META-INF/xposed/module.prop`: `staticScope=false`
2. `scope.list` + `distribution/xposed-repository/SCOPE`: still Photos-only recommended
3. New `ScopePolicy.kt` (pure JVM) with DENY-1 exact packages
4. New `ScopePolicyTest.kt`
5. `PixelifyModule.kt`: no Photos-only equality; both loaded/ready paths use `ScopePolicy.shouldSpoof`; keep `isFirstPackage`; Device+Feature for allowed apps
6. EN + zh-TW strings: recommended scope + multi-app risk; force-stop mentions other scoped apps

## DENY-1 set
- com.google.android.gms
- com.android.vending
- com.google.android.gsf
- com.google.android.gsf.login
- com.google.android.packageinstaller
- com.google.android.permissioncontroller
- com.android.settings
- com.android.systemui
- com.android.phone

## Out of this phase
- getScope UI / strict allowlist / requestScope / per-package profiles
- expanding recommended scope.list
- README/CHANGELOG (Phase 3)

## Verification
```
./gradlew :app:testDebugUnitTest --tests DevicePropsTest --tests ScopePolicyTest
→ BUILD SUCCESSFUL
```

