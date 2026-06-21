# Pixelify Google Photos (Modern API)

基於 **libxposed Modern API** 全面改寫的 Pixelify Google Photos Xposed 模組。

> ⚠️ 此版本使用 libxposed Modern API（api v101+），**僅支援 LSPosed**，不支援舊版 Xposed Framework 或 EdXposed。

## 功能

- **裝置偽造** — 將非 Pixel 裝置偽裝為 Google Pixel，解鎖 Google Photos 的 Pixel 限定功能
- **Feature Flags 偽造** — 偽造 12 組 Pixel 專屬 feature levels，涵蓋 Pixel 2016~2024
- **20 款 Pixel 裝置** — 從 Pixel XL 到 Pixel 9 Pro XL / Pixel 9a 完整支援
- **Android 版本偽造** — 可自訂偽造的 Android 版本（支援 Android 7.1~16）
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

### 前置需求

- [Android Studio](https://developer.android.com/studio)（或 JDK 17+ + Android SDK）
- Android SDK 35+

### 快速開始

```bash
git clone https://github.com/samson910022/pixelify-google-photos-modern.git
cd pixelify-google-photos-modern
./gradlew assembleDebug
```

### 簽署 Release APK

若要建置簽署用於正式發佈的 APK，需要先準備簽署金鑰：

#### 首次：產生金鑰庫

```bash
# 使用 keytool（隨 Android Studio / JDK 提供）
keytool -genkey -v -keystore pixelify.jks \
        -alias pixelify \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass <your-store-pass> \
        -keypass <your-key-pass> \
        -dname "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown"
```

#### 建立簽署設定

在專案根目錄建立 `key.properties`（已加入 `.gitignore`，不會被 commit）：

```properties
storePassword=<your-store-pass>
keyPassword=<your-key-pass>
keyAlias=pixelify
storeFile=pixelify.jks
```

#### 建置簽署 APK

```bash
./gradlew assembleRelease
```

APK 產出在 `app/build/outputs/apk/release/`

> ⚠️ **金鑰安全提醒**
> - `key.properties` 含密碼，已列入 `.gitignore`
> - `pixelify.jks` 請務必備份到安全處
> - 金鑰遺失後**無法更新**已上架的 APK

### 跑單元測試

```bash
./gradlew test --tests "balti.xposed.pixelifygooglephotos.*"
```

## 測試

```bash
# 執行單元測試
./gradlew test --tests "balti.xposed.pixelifygooglephotos.*"
```

專案包含 **100 個單元測試**，涵蓋：
- `DevicePropsTest` (50 tests) — 裝置資料完整性、查詢邏輯、Android 17 資料
- `DeviceSpooferTest` (6 tests) — Reflection exception safety、catch(Throwable) 驗證
- `FeatureSpoofLogicTest` (17 tests) — Feature flag 決策邏輯
- `UtilsConfigTest` (19 tests) — 設定檔匯出/匯入往返
- `ConstantsTest` (8 tests) — 常數完整性

## 專案品質

本專案經歷了完整的 **4 階段程式碼審查與修補**：

### 安全性
- `forceStopPackage()` 加入 `require()` 驗證 + `ProcessBuilder`，防止命令注入
- Config Import/Export 使用安全 URI 訪問，消除 NPE 崩潰風險
- Config Import 加入 device name / Android version 驗證
- FileProvider 路徑限定於子目錄
- 敏感日誌（裝置名稱）限 verbose mode 才輸出

### Android 17 Crash 修補 (v5.2)
- **SIGSEGV 防護** — Android 17 reflection ban 造成 SIGSEGV，加入 early-return guard（SDK >= 37 完全跳過 Build spoofing）
- **catch(Throwable)** — 所有 catch 區塊從 `Exception` 全面升級為 `Throwable`，防止 Error 子類漏接
- **null-safe reflection** — `accessFlagsField` 可為 null，不再因找不到 Dalvik-specific Field 中斷

### 程式碼品質
- `FeatureSpoofer` 使用 `Set` 取代 `List`，查詢從 O(n) 優化為 O(1)
- 提取共用 `PrefUtils.getPrefs()` 消除三處重複
- 淘汰已棄用的 `AsyncTask`，改用 `Executors`
- 修復 `OutputStream` 資源洩漏
- `restartActivity()` 消除視覺閃爍
- 移除無效的 `PREF_STRICTLY_CHECK_GOOGLE_PHOTOS` 死代碼

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
