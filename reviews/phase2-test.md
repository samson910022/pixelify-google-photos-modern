# Phase 2 — Test Report

**Date:** 2026-06-15  
**Tester:** Subagent (Phase 2 Test Engineer)  
**Scope:** Signature compatibility, old-API audit, compilation readiness, preference access consistency, source inventory

---

## Source File Inventory

All 6 Kotlin source files reside under `app/src/main/java/balti/xposed/pixelifygooglephotos/`:

| # | File | Size | Role |
|---|------|------|------|
| 1 | `App.kt` | 554 B | Android Application class + XposedService helper |
| 2 | `Constants.kt` | 1,351 B | Shared preference keys, URLs, constants |
| 3 | `DeviceProps.kt` | 11,814 B | Device property definitions, feature flags |
| 4 | `DeviceSpoofer.kt` | 4,407 B | Spoofs `android.os.Build` static fields via reflection |
| 5 | `FeatureSpoofer.kt` | 8,654 B | Hooks `hasSystemFeature` via libxposed interceptors |
| 6 | `PixelifyModule.kt` | 1,582 B | libxposed `XposedModule` entry point |

No orphan or stray source files found. All 6 files form a coherent, self-contained module.

---

## 1. Signature Compatibility — ✅ PASS

### 1a. `DeviceSpoofer.hook(params.classLoader)`

| Aspect | Caller (PixelifyModule.kt) | Callee (DeviceSpoofer.kt) |
|--------|---------------------------|---------------------------|
| **Declaration** | `DeviceSpoofer.hook(params.classLoader)` | `object DeviceSpoofer { fun hook(classLoader: ClassLoader) }` |
| **Arguments** | 1 (`ClassLoader` from `PackageLoadedParam`) | 1 (`classLoader: ClassLoader`) |
| **Object** | `DeviceSpoofer` (companionless object access) | `object DeviceSpoofer` |
| **Verdict** | ✅ Exact match | — |

### 1b. `FeatureSpoofer.hook(this, params.classLoader)`

| Aspect | Caller (PixelifyModule.kt) | Callee (FeatureSpoofer.kt) |
|--------|---------------------------|---------------------------|
| **Declaration** | `FeatureSpoofer.hook(this, params.classLoader)` | `object FeatureSpoofer { fun hook(module: XposedModule, classLoader: ClassLoader) }` |
| **Arguments** | 2 (`this` = PixelifyModule → XposedModule, `ClassLoader`) | 2 (`module: XposedModule`, `classLoader: ClassLoader`) |
| **Object** | `FeatureSpoofer` (companionless object access) | `object FeatureSpoofer` |
| **Verdict** | ✅ Exact match | — |

**Result: Both call signatures match their callees exactly. No mismatch.**

---

## 2. Old-API Audit — ✅ PASS

### 2a. DeviceSpoofer.kt — old API check

| Prohibited Symbol | Found? | Notes |
|-------------------|--------|-------|
| `XposedHelpers` | ❌ Not found | Uses pure Java Reflection (`Field`, `Modifier`) |
| `IXposedHookLoadPackage` | ❌ Not found | Hooks triggered via `PixelifyModule.onPackageLoaded` |
| `XSharedPreferences` | ❌ Not found | Uses `App.mService?.getRemotePreferences()` |
| `XC_MethodHook` | ❌ Not found | No XC_MethodHook anywhere |
| `findAndHookMethod` | ❌ Not found | — |
| `XposedBridge` | ❌ Not found | — |
| `de.robv.android.xposed.*` | ❌ Not found | — |

**Verdict: Clean. Uses only `java.lang.reflect.Field`, `java.lang.reflect.Modifier`, `android.os.Build`, and `android.util.Log`.**

### 2b. FeatureSpoofer.kt — old-API check

| Prohibited Symbol | Found? | Notes |
|-------------------|--------|-------|
| `XC_MethodHook` | ❌ Not found | Uses libxposed `module.hook(method).intercept { ... }` SAM |
| `HookMethodParam` | ❌ Not found | Uses `XposedInterface.Chain` |
| `XposedHelpers` | ❌ Not found | — |
| `XSharedPreferences` | ❌ Not found | Uses `module.getRemotePreferences()` |
| `de.robv.android.xposed.*` | ❌ Not found | All imports are `io.github.libxposed.*` |

**Verdict: Clean. Uses libxposed Modern API (`io.github.libxposed.api.*`).**

### 2c. PixelifyModule.kt — old-API check

| Prohibited Symbol | Found? |
|-------------------|--------|
| `IXposedHookLoadPackage` | ❌ Not found |
| `XposedBridge` | ❌ Not found |
| `de.robv.android.xposed.*` | ❌ Not found |
| `android.app.Application` | ❌ Not found (irrelevant here) |

**Verdict: Clean. Only imports `android.util.Log`, `io.github.libxposed.api.XposedInterface`, `io.github.libxposed.api.XposedModule`.**

---

## 3. Compilation Readiness — ✅ PASS

### 3a. PixelifyModule.kt — libxposed import correctness

| Import | Usage | Correct? |
|--------|-------|----------|
| `io.github.libxposed.api.XposedModule` | `class PixelifyModule : XposedModule()` | ✅ Yes — base class |
| `io.github.libxposed.api.XposedInterface` | `XposedInterface.ModuleLoadedParam`, `.PackageLoadedParam`, `.PackageReadyParam` | ✅ Yes — parameter types |
| `android.util.Log` | `Log.d()`, `Log.e()` | ✅ Yes — standard Android API |

### 3b. FeatureSpoofer.kt — libxposed class availability

| libxposed Class | Usage | Scope | Correct? |
|-----------------|-------|-------|----------|
| `XposedModule` | `hook(module: XposedModule)` | Parameter type | ✅ Available as compileOnly |
| `XposedInterface` | `XposedInterface.Chain`, `.HookBuilder` | Method return types | ✅ Available as compileOnly |
| `XposedInterface.Chain` | `chain.proceed()`, `chain.getArg(0)` | Interceptor argument | ✅ Correct SAM interface |
| `XposedInterface.HookBuilder` | `module.hook(method).intercept { ... }` | Chained builder | ✅ Correct API surface |
| `module.getRemotePreferences()` | `XposedModule.getRemotePreferences()` | Method call | ✅ Available on XposedModule |

All libxposed types referenced are part of the `io.github.libxposed.api` package and are provided via `compileOnly` dependency. No runtime dependencies on old Xposed API.

### 3c. DeviceSpoofer.kt — standard class imports

| Import | Usage | Correct? |
|--------|-------|----------|
| `java.lang.reflect.Field` | `clazz.getDeclaredField(fieldName)`, `Field::class.java.getDeclaredField("accessFlags")` | ✅ Available in every JVM |
| `java.lang.reflect.Modifier` | `Modifier.FINAL.inv()` | ✅ Standard JVM API |
| `android.os.Build` | `Build::class.java`, `Build.VERSION::class.java` | ✅ Android framework |
| `android.util.Log` | `Log.d()`, `Log.w()`, `Log.e()` | ✅ Android framework |

**Verdict: All imports resolve to standard Java/Kotlin or Android SDK classes. No compile-time issues expected.**

---

## 4. Preference Access Consistency — ✅ PASS (with note)

### 4a. DeviceSpoofer preference access

```kotlin
// DeviceSpoofer uses XposedService path:
val prefs = App.mService?.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
```

Uses keys: `PREF_ENABLE_VERBOSE_LOGS`, `PREF_DEVICE_TO_SPOOF`, `PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE`, `PREF_SPOOF_ANDROID_VERSION_MANUAL`

### 4b. FeatureSpoofer preference access

```kotlin
// FeatureSpoofer uses XposedModule path:
val prefs = module.getRemotePreferences(Constants.SHARED_PREF_FILE_NAME)
```

Uses keys: `PREF_ENABLE_VERBOSE_LOGS`, `PREF_OVERRIDE_ROM_FEATURE_LEVELS`, `PREF_SPOOF_FEATURES_LIST`

### 4c. Preference key consistency with Constants.kt

| Key | Constants.kt | DeviceSpoofer | FeatureSpoofer |
|-----|-------------|---------------|----------------|
| `SHARED_PREF_FILE_NAME` | ✅ Defined (`"prefs"`) | ✅ Uses `Constants.SHARED_PREF_FILE_NAME` | ✅ Uses `Constants.SHARED_PREF_FILE_NAME` |
| `PREF_ENABLE_VERBOSE_LOGS` | ✅ Defined | ✅ Used | ✅ Used |
| `PREF_DEVICE_TO_SPOOF` | ✅ Defined | ✅ Used | N/A |
| `PREF_SPOOF_ANDROID_VERSION_FOLLOW_DEVICE` | ✅ Defined | ✅ Used | N/A |
| `PREF_SPOOF_ANDROID_VERSION_MANUAL` | ✅ Defined | ✅ Used | N/A |
| `PREF_OVERRIDE_ROM_FEATURE_LEVELS` | ✅ Defined | N/A | ✅ Used |
| `PREF_SPOOF_FEATURES_LIST` | ✅ Defined | N/A | ✅ Used |
| `PREF_STRICTLY_CHECK_GOOGLE_PHOTOS` | ✅ Defined | N/A | N/A |
| `PREF_LAST_VERSION` | ✅ Defined | N/A | N/A |

**All used keys match Constants.kt definitions. No reference to undefined keys.**

### ⚠️ Note: Dual preference access paths

DeviceSpoofer accesses prefs via `App.mService?.getRemotePreferences()` while FeatureSpoofer uses `module.getRemotePreferences()`. Both ultimately read from the file `"prefs"`, so they should return identical data. However:

- `App.mService` is a nullable global (`var mService: XposedService?`). If `onServiceBind()` hasn't fired yet, prefs will be `null` and DeviceSpoofer falls back to defaults.
- `module.getRemotePreferences()` is a direct `XposedModule` method and always available once the module is loaded.

**Recommendation:** This is acceptable and defensive — DeviceSpoofer's null-safe access handles the edge case gracefully. If desired, both could use `module.getRemotePreferences()` for uniformity, but this is a style choice, not a bug.

---

## 5. Call Flow Trace

```
PixelifyModule.onPackageLoaded(params)
  │
  ├─ FeatureSpoofer.hook(this, params.classLoader)
  │    ├─ module.getRemotePreferences("prefs")
  │    ├─ initFromPrefs(prefs) → builds feature lists
  │    ├─ classLoader.loadClass("android.app.ApplicationPackageManager")
  │    ├─ module.hook(method).intercept { decideSpoof(chain) }
  │    └─ ✅ All done
  │
  └─ DeviceSpoofer.hook(params.classLoader)
       ├─ App.mService?.getRemotePreferences("prefs") → reads device name
       ├─ DeviceProps.getDeviceProps(deviceName)
       ├─ setStaticField(Build, key, value) for each prop
       ├─ If follow-device: use deviceEntries.androidVersion
       │  else: read manual version from prefs
       ├─ setStaticField(Build.VERSION, key, value) for each android version field
       └─ ✅ All done
```

Flow is linear and well-structured. No circular dependencies or dead-end paths.

---

## Summary

| Test Item | Result |
|-----------|--------|
| 1. Signature compatibility | ✅ PASS |
| 2. Old-API audit (all 3 files) | ✅ PASS — zero old-API references |
| 3. Compilation readiness | ✅ PASS |
| 4. Preference access consistency | ✅ PASS (minor note on dual path) |
| 5. Source file inventory | ✅ 6 files, all accounted for |

**Overall Phase 2 Test Verdict: ✅ PASS**

No blocking issues found. The three tested files are:
- Correctly wired together with matching call signatures
- Fully migrated from old Xposed API to libxposed Modern API v5
- Ready for compilation (all imports resolve to standard SDK or compileOnly dependencies)
- Consistent in their preference key usage with `Constants.kt`

### One Observational Note (not blocking)

**Dual preference access path** — DeviceSpoofer goes through `App.mService` (nullable gateway), FeatureSpoofer goes through `module.getRemotePreferences()` (direct module method). Both read the same file, but the service path introduces a null-safety branch that the module path does not need. If ever the service is not bound when `DeviceSpoofer.hook()` is called, defaults take effect silently. This is acceptable for the current architecture but may be worth unifying in a future refactor.
