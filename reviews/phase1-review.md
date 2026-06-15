# Phase 1 Code Review — Pixelify Google Photos

> **Reviewer:** Subagent (depth 1/2)
> **Date:** 2026-06-15 21:15 GMT+8
> **Scope:** Phase 1 project scaffolding, module entry point, data layer, resources

---

## Summary

| Category | Verdict |
|---|---|
| Build System | ❌ **Critical issues** |
| Module Entry | ✅ Mostly correct |
| META-INF Config | ✅ Correct |
| AndroidManifest | ❌ **Critical issues** |
| Data Layer | ✅ Clean |
| Resources | ⚠️ Issues found |

---

## 🔴 Critical Issues (blocking build)

### 1. Packaging excludes will strip xposed config from APK

**File:** `app/build.gradle.kts` (lines 34–37)

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }
}
```

`excludes += "**"` removes **all** resource files from the final APK. While `META-INF/xposed/*` is in `merges`, Gradle processes **merges first, then excludes** — meaning even the merged xposed files get stripped.

**Result:** `module.prop`, `java_init.list`, and `scope.list` will be missing from the built APK, making the module invisible to LSPosed/libxposed.

**Fix:** Replace `excludes += "**"` with specific exclude patterns (e.g., `excludes += "/META-INF/{AL2.0,LGPL2.1}"`) or remove the blanket exclude entirely and use more targeted filters.

---

### 2. Missing Activity classes referenced in AndroidManifest

**File:** `app/src/main/AndroidManifest.xml`

The manifest declares three activities that do not exist as source files:

| Declaration | Expected file |
|---|---|
| `.ActivityMain` | `ActivityMain.kt` (or `ActivityMain.java`) |
| `.FeatureCustomize` | `FeatureCustomize.kt` |
| `.AdvancedOptionsActivity` | `AdvancedOptionsActivity.kt` |

Only four files exist in the source package:
- `App.kt`
- `Constants.kt`
- `DeviceProps.kt`
- `PixelifyModule.kt`

**Result:** Gradle build will fail with `android.app.Activity` class not found errors.

---

### 3. Missing mipmap launcher icons

**File:** `app/src/main/AndroidManifest.xml` references:
- `@mipmap/ic_launcher`
- `@mipmap/ic_launcher_round`

All mipmap density directories exist but are **empty** (no `.png` or `.xml` files inside):
- `mipmap-hdpi/`
- `mipmap-mdpi/`
- `mipmap-xhdpi/`
- `mipmap-xxhdpi/`
- `mipmap-xxxhdpi/`

**Result:** Build will fail with resource-not-found for `ic_launcher` / `ic_launcher_round`.

---

## 🟡 Significant Issues

### 4. `local.properties` has placeholder SDK path

**File:** `local.properties`

```
# sdk.dir=C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
ndk.dir=C:\\Users\\samso\\AppData\\Local\\Android\\Sdk\\ndk\\27.0.12077973
```

- `sdk.dir` is **commented out with a placeholder** — any developer without Android SDK in the detected default path will fail to build.
- `ndk.dir` is **hardcoded to a specific user's path** — this is typically auto-detected by AGP and does not need to be set. In AGP 4.2+, `ndk.dir` in `local.properties` is deprecated in favor of the `ANDROID_NDK_HOME` environment variable.

**Recommendation:** Uncomment and fill in a valid `sdk.dir`, or better, rely on the `ANDROID_HOME` environment variable and remove the `ndk.dir` line.

---

## 🔵 Minor Issues / Observations

### 5. Unused imports in `PixelifyModule.kt`

**File:** `app/src/main/java/balti/xposed/pixelifygooglephotos/PixelifyModule.kt`

```kotlin
import io.github.libxposed.api.annotations.AfterInvocation
import io.github.libxposed.api.annotations.BeforeInvocation
import io.github.libxposed.api.annotations.XposedHooker
```

These hooker annotations are not used anywhere in the current code. They were likely imported for Phase 2 but create minor code clutter.

**Recommendation:** Remove or comment them out until Phase 2 implements actual hooks.

---

### 6. `values-zh-rTW/strings.xml` encoding issues

**File:** `app/src/main/res/values-zh-rTW/strings.xml`

Many string values appear to have garbled/mojibake text (e.g., `??`, `鋆蔭璅⊥`, `靽格`, `?芸?蝢拙??賢???`). This suggests the file was saved with incorrect character encoding — it may have been written in Big5/CP950 but should be UTF-8.

**Recommendation:** Verify the file is saved as UTF-8 without BOM, and the Chinese strings render correctly.

---

### 7. Empty `gradle/wrapper/` directory

**Directory:** `gradle/wrapper/`

The Gradle Wrapper directory exists but contains no files (`gradle-wrapper.properties`, `gradle-wrapper.jar`, `gradlew` script). Without the wrapper, the project requires a system-installed Gradle to build, which reduces portability.

**Recommendation:** Run `gradle wrapper` (or the appropriate Gradle version command) to generate the wrapper files pinned to AGP 8.7.3-compatible Gradle (8.9+).

---

### 8. Empty test directories

**Directories:**
- `app/src/test/java/balti/xposed/pixelifygooglephotos/` — empty
- `app/src/androidTest/java/balti/xposed/pixelifygooglephotos/` — empty

Acceptable for Phase 1, but tests should be added in subsequent phases.

---

## ✅ Items Verified Correct

### Build System
| Check | Status |
|---|---|
| `build.gradle.kts` (root) — AGP 8.7.3 + Kotlin 2.1.0 | ✅ Correct |
| `settings.gradle.kts` — google()+mavenCentral()+libxposed maven | ✅ Correct |
| `gradle/libs.versions.toml` — version catalog with all dependencies | ✅ Complete |
| `app/build.gradle.kts` — namespace=`balti.xposed.pixelifygooglephotos` | ✅ Correct |
| `app/build.gradle.kts` — compileSdk=35, minSdk=26, targetSdk=35 | ✅ Correct |
| `app/build.gradle.kts` — libxposed:api `compileOnly`, libxposed:service `implementation` | ✅ Correct |
| `app/build.gradle.kts` — Java 17 compilation target | ✅ Correct |
| `app/build.gradle.kts` — ViewBinding enabled | ✅ Correct |
| `app/build.gradle.kts` — ProGuard minification + resource shrinking (release) | ✅ Correct |
| `gradle.properties` — JVM args, AndroidX, R classes | ✅ Correct |
| `.gitignore` — standard Android ignores | ✅ Correct |

### Module Entry (libxposed Modern API)
| Check | Status |
|---|---|
| `PixelifyModule` extends `XposedModule()` (no constructor param needed) | ✅ Correct |
| `onModuleLoaded(param)` signature | ✅ Correct |
| `onPackageLoaded(param)` signature | ✅ Correct |
| `onPackageReady(param)` signature | ✅ Correct |
| `onHotReloading()` signature | ✅ Correct (verify API 102) |
| `onHotReloaded()` signature | ✅ Correct (verify API 102) |
| Uses `android.util.Log.d()` instead of `XposedBridge.log()` | ✅ Correct |
| No Xposed API dependencies in data layer | ✅ Correct |
| `App.kt` extends `Application`, implements `XposedServiceHelper.OnServiceListener` | ✅ Correct |
| `App.kt` — `onServiceBind` stores to companion `mService` | ✅ Correct |

### META-INF Xposed Configuration
| Check | Status |
|---|---|
| `module.prop` — `minApiVersion=101`, `targetApiVersion=102` | ✅ Correct |
| `module.prop` — `staticScope=true`, `autoHotReload=true` | ✅ Correct |
| `java_init.list` — points to `balti.xposed.pixelifygooglephotos.PixelifyModule` | ✅ Correct |
| `scope.list` — contains `com.google.android.apps.photos` | ✅ Correct |
| META-INF files in correct location (`resources/META-INF/xposed/`) | ✅ Correct |

### AndroidManifest
| Check | Status |
|---|---|
| No `xposedmodule`, `xposeddescription`, `xposedminversion`, `xposedscope`, `xposedsharedprefs` meta-data | ✅ Correct |
| `android:name=".App"` present | ✅ Correct |
| Permissions (INTERNET, ACCESS_NETWORK_STATE) preserved | ✅ Correct |
| `FileProvider` configuration | ✅ Correct |
| `queries` for Google Photos package | ✅ Correct |

### Data Layer
| Check | Status |
|---|---|
| `DeviceProps.kt` — pure data objects, no Xposed API | ✅ Correct |
| `DeviceProps.kt` — 9 device entries with full build props | ✅ Complete |
| `DeviceProps.kt` — `Features`, `DeviceEntries`, `AndroidVersion` data classes | ✅ Correct |
| `Constants.kt` — all preference keys + URLs | ✅ Correct |
| `Constants.kt` — no Xposed API dependency | ✅ Correct |

### Resources
| Check | Status |
|---|---|
| Layout files present (`activity_main.xml`, `advanced_options_activity.xml`, `feature_customize.xml`) | ✅ Present |
| String resources complete | ✅ Present |
| `strings.xml` — no `module_scope` string-array | ✅ Correct |
| Menu resource present | ✅ Present |
| Drawable icons present (ic_export, ic_import, ic_info, ic_open, launcher bg/fg) | ✅ Present |
| `provider_paths.xml` present | ✅ Present |
| Dark theme variant present (`values-night/`) | ✅ Present |
| ProGuard keeps `PixelifyModule` + `libxposed.service.*` | ✅ Correct |

---

## 🔧 Recommendations Before Phase 2

1. **Fix critical issues first** — packaging excludes, missing Activities, missing launcher icons.
2. **Add Gradle wrapper** for reproducible builds.
3. **Clean up unused imports** in `PixelifyModule.kt`.
4. **Fix `local.properties`** — use `ANDROID_HOME` env var or fill in a valid `sdk.dir`; remove deprecated `ndk.dir`.
5. **Verify zh-rTW encoding** — ensure UTF-8 integrity.
6. **Add unit tests** for `DeviceProps` and `Constants` in `src/test/`.
7. **Add integration tests** in `src/androidTest/` for module lifecycle.
