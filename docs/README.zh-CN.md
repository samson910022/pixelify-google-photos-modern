# Pixelify Infinity（无限解锁）

[English](../README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

![Pixelify Infinity banner](../branding/banner.png)

> 本文档是简体中文翻译；如有差异，以[英文 README](../README.md)为准。

Pixelify Infinity 是一个独立维护的 Xposed 模块，可为 Google 相册模拟部分 Google Pixel 设备属性和系统功能标志。本项目使用现代 libxposed API，并拥有独立的软件包名称、版本历史和签名身份。

> [!IMPORTANT]
> 本项目与 Google、Google 相册、Pixel、LSPosed 或原上游维护者没有隶属或背书关系。功能可能随 Google 相册、服务端配置、账号、地区、设备或 Android 更新而变化。使用风险由用户自行承担。

## 功能

- 模拟部分 Google Pixel 设备配置。
- 模拟与 Pixel 相关的系统功能标志。
- 提供从 Pixel XL 到较新 Pixel 世代的多种设备配置。
- 可选模拟兼容的 Android 版本。
- 覆盖 ROM 提供的 Pixel feature level。
- 在高级设置中单独选择功能标志。
- 导入、导出和分享模块配置。

## 系统要求

- Android 8.0（API 26）或更高版本。
- Root 权限。
- 支持现代 libxposed API 101 的 Xposed 环境，例如兼容的 LSPosed 配置。
- Google 相册（`com.google.android.apps.photos`）。

此现代 API 版本不支持旧式 XposedBridge／EdXposed 环境。

**Android 17 及更高版本兼容性：**所有受支持的 Android 版本（含 API 37+）都会尝试 Build 属性模拟。在部分 Android 17 版本上，ART 会拒绝 `public static final` 的 `Build` 字段的 `Field.set`（`IllegalAccessException`）；这是 ART 限制，与 libxposed API 101 无关。模块使用多策略写入（在可清除 reflected `final` 后的反射 `Field.set`，再尝试多变体 `Unsafe` static put，最后 JNI `libpixelify_build` 后备）、拦截 `SystemProperties` 读取作为次要路径、在包加载早期应用（并在 ready 时再次应用），且会回读校验。不保证在所有 Android 17 ROM 上均成功；VERIFY 仍失败时会通过 Toast 与通知提示，而不是静默失败。功能标志与设备配置仍取决于设备、ROM、框架和 Google 相册版本。

## 安装

1. 从本仓库的 [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) 页面下载 APK。
2. 安装 APK。
3. 在 Xposed 模块管理器中启用 **Pixelify Infinity**（无限解锁）。
4. 仅将模块作用域设置为 **Google 相册**。
5. 强制停止并重新打开 Google 相册；如果模块管理器要求，请重启设备。

请只安装来自本仓库或官方 Xposed Modules Repository 镜像的版本：

- 源码仓库 Releases：https://github.com/samson910022/pixelify-google-photos-modern/releases
- Xposed 镜像 Releases：https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases
- 官方网站列表：https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos

安装前请阅读[版本验证](#版本验证)。

## 从旧版项目迁移

本项目使用独立 application ID：

```text
io.github.samson910022.pixelifyphotos
```

它不是 `balti.xposed.pixelifygooglephotos` 的原位升级，两者可以共存。迁移时必须重新启用新模块并设置作用域，配置不会自动迁移。

完整维护和归属说明请参阅 [FORK_NOTICE.md](../FORK_NOTICE.md)。

## 版本验证

正式版本使用固定签名证书。安装 APK 前，请确认签名者 SHA-256 与 [docs/RELEASE_SIGNING.md](RELEASE_SIGNING.md) 公布的 fingerprint 一致。Release 页面也应提供每个下载文件的 checksum。

公开证书位于 [`certificates/pixelifyphotos-release-cert.pem`](../certificates/pixelifyphotos-release-cert.pem)。私有签名密钥不会存放在此仓库中。

## 隐私和网络访问

Pixelify Infinity 不包含分析或广告 SDK。应用使用网络权限检查所配置的 GitHub／Xposed 发布源是否有更新，并打开项目链接。模块设置和导出的配置文件由用户自行控制。

详细信息请参阅 [PRIVACY.md](../PRIVACY.md)。

## 故障排除和支持

报告问题前：

1. 确认模块已启用，并且作用域只包含 Google 相册。
2. 强制停止并重新打开 Google 相册。
3. 仅在诊断时启用详细日志并重现问题。
4. 分享日志前删除账号标识和其他个人信息。
5. 搜索现有 [issues](https://github.com/samson910022/pixelify-google-photos-modern/issues)。

可通过 GitHub Issues 报告可重现的问题或提出功能建议。安全漏洞请按 [SECURITY.md](../SECURITY.md) 报告，不要创建公开 issue。

## 开发

构建说明、贡献规则、测试命令和维护者发布说明位于 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 许可与归属

本项目采用 [MIT License](../LICENSE)。依赖项声明请参阅 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

本项目派生自 [BaltiApps/Pixelify-Google-Photos](https://github.com/BaltiApps/Pixelify-Google-Photos)，并使用 [libxposed/api](https://github.com/libxposed/api) 和 [LSPosed](https://github.com/LSPosed/LSPosed) 生态。

Google 相册、Google Pixel、Android 及相关名称均为其各自权利人的商标。
