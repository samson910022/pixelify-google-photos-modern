# Changelog

## [5.2] - 2026-06-21

### 關鍵修復
- **Android 17 SIGSEGV Crash 修補** — `DeviceSpoofer.hook()` 加入 Android 17 early-return guard（SDK >= 37 完全跳過 reflection），消除 Android 17 static final field reflection ban 造成的 SIGSEGV
- **`catch(t: Throwable)` 全面遷移** — `setStaticField` 與 `forEach` 迴圈的 `catch(e: Exception)` 改為 `catch(t: Throwable)`，防止 Error 子類（如 `NoSuchFieldError`、`VerifyError`）漏接
- **`accessFlagsField` null-safety** — `accessFlagsField` lazy initializer 找不到 `Field.accessFlags` 時不 throw，改為 nullable，`setStaticField` 使用 safe-call
- **Activity lifecycle 防護** — `restartActivity()` 加入 `finish()` 後 `return`；背景更新執行緒加入 `isFinishing/isDestroyed` 檢查

### 相依性
- **Android 17 entry** — `DeviceProps` 加入 Android 17（SDK 37）至版本清單
- **JSON 測試相依性** — 加入 `org.json:json:20231013` 測試用 dependency

### 測試覆蓋
- **DeviceSpooferTest** — 新增 7 個單元測試（Android 17 常數、setStaticField exception safety、catch(Throwable) vs catch(Exception)）
- **DevicePropsTest** — 新增 3 個 Android 17 測試（存在性、getAsMap、getAndroidVersionFromLabel）
- **總測試數：** 100 tests（原本 88 + 新 12），全部 PASS

### 程式碼品質
- **PixelifyModule 縮排修正** — 外層 try 區塊內容正確縮排
- **code review** — 通過雙審（Code Reviewer ✅ APPROVED + Test Engineer ✅ PASS）

## [5.1.1] - 2026-06-21

### 簽署支援
- **新增 APK 簽署設定** — 加入 `signingConfigs` release 組態，讀取 `key.properties`
- **簽署文件** — README 新增完整簽署流程說明
- **`.gitignore` 更新** — 啟用 keystore 過濾，追加 `key.properties`

### 依賴修復
- **libxposed API 升級** — 從失聯的 `api.xposed.info` 遷移至 Maven Central，版本 v3.0 → v101.0.0
- **libxposed Service 修正** — 獨立版本引用 v101.0.0（原與 API 共用 v3.0 不存在）
- **`pluginManagement` 補全** — 解決 AGP 8.7.3 外掛解析失敗
- **新增 `jitpack.io` 倉庫** — 確保依賴解析完整性

### 相容性修復
- **`PixelifyModule.kt`** — 方法簽名從 `XposedInterface.*` 更正為 `XposedModuleInterface.*`
- **`App.kt`** — 補上 `onServiceDied(service)` 實作
- **`BuildConfig` 啟用** — 加入 `buildConfig = true` 修復 VERSION_CODE 引用
- **`AdvancedOptionsActivity.kt`** — 修復 delegated property 造成的 smart cast 編譯錯誤
- **ProGuard 規則** — 補上 libxposed 模組 keep rules 與 `-dontwarn`
- **Gradle Wrapper 補全** — 補上 `gradlew.bat` 與 `gradle-wrapper.jar`

## [5.1] - 2026-06-15

### 安全性修補
- **命令注入修補** — `forceStopPackage()` 加入 `require()` 驗證 + `ProcessBuilder` 陣列參數，防止 shell injection
- **NPE 崩潰修補** — Config import/export callback 使用安全 URI 訪問 (`it.data?.data ?: return`)
- **Config Import 驗證** — 匯入時驗證 device name 與 Android version 有效性，避免靜默套用無效值
- **FileProvider 路徑限定** — 限定於 `config_exports/` 子目錄，防止路徑遍歷
- **Verbose Log 防護** — 裝置名稱等敏感資訊僅在啟用 verbose logging 時輸出

### 程式碼品質
- **效能優化** — FeatureSpoofer flag 查詢從 `List` (O(n)) 改為 `Set` (O(1))
- **消除重複** — 提取共用 `PrefUtils.getPrefs()`，三處 Activity 改為委派呼叫
- **淘汰 AsyncTask** — 改用 `Executors.newSingleThreadExecutor()`
- **資源洩漏修補** — `writeConfigFile()` OutputStream 使用 `use {}` 確保關閉
- **死代碼移除** — 刪除無效的 `PREF_STRICTLY_CHECK_GOOGLE_PHOTOS` 偏好設定
- **UI 體驗** — `restartActivity()` 加入 `FLAG_ACTIVITY_NO_ANIMATION` + `overridePendingTransition(0, 0)` 消除閃爍
- **Spinner 防護** — `setSelection(-1)` guard 避免 AdvancedOptions 空選取

### 測試覆蓋
- **88 個單元測試**，無 Android 依賴，可在主機 JVM 執行
- `DevicePropsTest` (47 tests) — 裝置資料完整性、查詢邏輯、指紋格式驗證
- `FeatureSpoofLogicTest` (17 tests) — TRUE/FALSE/PASS_THROUGH 決策邏輯
- `UtilsConfigTest` (16 tests) — JSON 匯出/匯入往返、畸形 JSON 處理
- `ConstantsTest` (8 tests) — 偏好設定鍵唯一性、URL 格式驗證

## [5.0] - 2026

- 基於 libxposed Modern API 全面改寫
- 支援 Pixel 9 Pro / Pixel 9 Pro XL / Pixel 9a
- 移除舊版 Xposed Bridge API 支援
- 新增 Android 13~16 版本偽造
- 支援 Android 8.0+ (API 26+)
