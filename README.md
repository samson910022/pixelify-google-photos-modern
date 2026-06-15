# Pixelify Google Photos (Modern API)

基於 **libxposed Modern API** 全面改寫的 Pixelify Google Photos Xposed 模組。

> ⚠️ 此版本使用 libxposed Modern API（api v101+），**僅支援 LSPosed**，不支援舊版 Xposed Framework 或 EdXposed。

## 功能

- **裝置偽造** — 將非 Pixel 裝置偽裝為 Google Pixel，解鎖 Google Photos 的 Pixel 限定功能
- **Feature Flags 偽造** — 偽造 17 組 Pixel 專屬 system features，涵蓋 Pixel 2016~2024
- **17 款 Pixel 裝置** — 從 Nexus 6P 到 Pixel 9 Pro XL 完整支援
- **Android 版本偽造** — 可自訂偽造的 Android 版本（支援 Android 9~16）
- **ROM Feature Level 覆寫** — 覆蓋自訂 ROM 內建的 Pixel feature flag 設定
- **自訂 Feature List** — 精細選擇要偽造的特定 feature flags
- **Config Import/Export** — 匯出/匯入設定檔

## 需求

- Android 8.0（API 26）或更高版本
- **LSPosed** 模組管理器
- Google Photos（建議最新版本）

## 安裝

1. 從 [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) 下載最新 APK
2. 安裝 APK 到裝置上
3. 開啟 LSPosed，啟用本模組
4. 作用範圍選擇 `com.google.android.apps.photos`
5. 重新啟動 Google Photos

## 編譯

```bash
git clone https://github.com/samson910022/pixelify-google-photos-modern.git
cd pixelify-google-photos-modern
./gradlew assembleRelease
```

APK 產出在 `app/build/outputs/apk/release/`

## 技術架構

```
PixelifyModule (XposedModule)
  ├── onPackageLoaded() → 偵測 Google Photos
  ├── FeatureSpoofer: hook().intercept() → 偽造 hasSystemFeature()
  └── DeviceSpoofer: Java Reflection → 偽造 Build 屬性

App (XposedServiceHelper)
  └── mService → Remote Preferences 讀寫
```

### 與舊版差異

| 項目 | 舊版 (XposedBridge 82) | 新版 (libxposed Modern API) |
|------|-----------------------|-----------------------------|
| 模組入口 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| Hook API | `XposedHelpers.findAndHookMethod` | `hook(method).intercept{ chain -> }` |
| 偏好儲存 | `XSharedPreferences` + `MODE_WORLD_READABLE` | `XposedService.getRemotePreferences()` |
| 模組宣告 | AndroidManifest meta-data | `module.prop` / `scope.list` |
| minSdk | 21 | 26（Android 8.0） |
| Build 工具 | Groovy + AGP 7.1 | Kotlin DSL + AGP 8.7 |

## 授權

[Apache License 2.0](LICENSE)

## 致謝

- [BaltiApps/Pixelify-Google-Photos](https://github.com/BaltiApps/Pixelify-Google-Photos) — 原始專案
- [libxposed/api](https://github.com/libxposed/api) — Modern Xposed API
- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed Framework
