# Pixelify Infinity（無限解鎖）

[English](../README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

![Pixelify Infinity banner](../branding/banner.png)

> 本文件是繁體中文翻譯；若內容有差異，以[英文 README](../README.md)為準。

Pixelify Infinity 是獨立維護的 Xposed 模組，可針對 Google 相簿模擬特定 Google Pixel 裝置屬性及系統功能旗標。本專案使用現代 libxposed API，並有獨立的套件名稱、版本歷史與簽署身分。

> [!IMPORTANT]
> 本專案與 Google、Google 相簿、Pixel、LSPosed 或原始上游維護者沒有隸屬或背書關係。功能可能隨 Google 相簿、伺服器端設定、帳戶、地區、裝置或 Android 更新而改變。使用風險由使用者自行承擔。

## 功能

- 模擬特定 Google Pixel 裝置設定檔。
- 模擬與 Pixel 相關的系統功能旗標。
- 提供從 Pixel XL 到較新 Pixel 世代的多種裝置設定檔。
- 可選擇模擬相容的 Android 版本。
- 覆寫 ROM 內建的 Pixel feature level。
- 透過進階設定個別選擇功能旗標。
- 匯入、匯出及分享模組設定。

## 系統需求

- Android 8.0（API 26）以上。
- Root 權限。
- 支援現代 libxposed API 101 的 Xposed 環境，例如相容的 LSPosed 設定。
- Google 相簿（`com.google.android.apps.photos`）。

此現代 API 版本不支援舊式 XposedBridge／EdXposed 環境。

**Android 17 以上相容性：**所有支援的 Android 版本（含 API 37+）都會嘗試 Build 屬性模擬。部分 Android 17 版本上，ART 會對 `public static final` 的 `Build` 欄位拒絕 `Field.set`（`IllegalAccessException`）；這是 ART 限制，與 libxposed API 101 無關。模組會使用多策略寫入（可清除 reflected `final` 後的反射 `Field.set`，再嘗試多變體 `Unsafe` static put，最後 JNI `libpixelify_build` 後備）、攔截 `SystemProperties` 讀取作為次要路徑、在套件載入早期套用（並在 ready 時再套用），且會回讀驗證。並非保證在所有 Android 17 ROM 上皆成功；VERIFY 仍失敗時會以 Toast 與通知提示，而非靜默失敗。功能旗標與裝置設定檔仍取決於裝置、ROM、框架及 Google 相簿版本。

## 安裝

1. 從本 repository 的 [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) 頁面下載 APK。
2. 安裝 APK。
3. 在 Xposed 模組管理器啟用 **Pixelify Infinity**（無限解鎖）。
4. 僅將模組作用範圍設為 **Google 相簿**。
5. 強制停止並重新開啟 Google 相簿；若模組管理器要求，請重新啟動裝置。

請只安裝來自本 repository 或官方 Xposed Modules Repository 鏡像的版本：

- 原始碼倉庫 Releases：https://github.com/samson910022/pixelify-google-photos-modern/releases
- Xposed 鏡像 Releases：https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases
- 官方網站列表：https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos

安裝前請參閱[版本驗證](#版本驗證)。

## 從舊版專案轉移

本專案使用獨立 application ID：

```text
io.github.samson910022.pixelifyphotos
```

它不是 `balti.xposed.pixelifygooglephotos` 的原地升級，兩者可以共存。轉移時必須重新啟用新模組並設定作用範圍，設定資料不會自動移轉。

完整維護及歸屬說明請見 [FORK_NOTICE.md](../FORK_NOTICE.md)。

## 版本驗證

正式版本使用固定簽署憑證。安裝 APK 前，請確認簽署者 SHA-256 與 [docs/RELEASE_SIGNING.md](RELEASE_SIGNING.md) 公布的 fingerprint 相同。Release 頁面也應提供各下載檔案的 checksum。

公開憑證位於 [`certificates/pixelifyphotos-release-cert.pem`](../certificates/pixelifyphotos-release-cert.pem)。私密簽署金鑰不會放入此 repository。

## 隱私與網路存取

Pixelify Infinity 不包含分析或廣告 SDK。App 使用網路權限向設定的 GitHub／Xposed 發佈來源檢查更新及開啟專案連結。模組設定及匯出的設定檔由使用者自行控制。

詳細資訊請見 [PRIVACY.md](../PRIVACY.md)。

## 疑難排解與支援

回報問題前：

1. 確認模組已啟用，且作用範圍僅包含 Google 相簿。
2. 強制停止並重新開啟 Google 相簿。
3. 只有在需要診斷時才啟用詳細記錄並重現問題。
4. 分享記錄前移除帳戶識別資訊及其他個人資料。
5. 搜尋既有 [issues](https://github.com/samson910022/pixelify-google-photos-modern/issues)。

可透過 GitHub Issues 回報可重現的錯誤或提出功能建議。安全漏洞請依照 [SECURITY.md](../SECURITY.md) 回報，不要建立公開 issue。

## 開發

編譯說明、貢獻規範、測試命令及維護者發佈說明位於 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 授權與歸屬

本專案使用 [MIT License](../LICENSE)。相依套件聲明請見 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

本專案衍生自 [BaltiApps/Pixelify-Google-Photos](https://github.com/BaltiApps/Pixelify-Google-Photos)，並使用 [libxposed/api](https://github.com/libxposed/api) 與 [LSPosed](https://github.com/LSPosed/LSPosed) 生態系。

Google 相簿、Google Pixel、Android 及相關名稱為其各自權利人的商標。
