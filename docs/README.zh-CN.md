# Pixelify Infinity（无限解锁）

[English](../README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md)

**项目网站（GitHub Pages，启用后）：** [https://samson910022.github.io/pixelify-google-photos-modern/zh-CN/](https://samson910022.github.io/pixelify-google-photos-modern/zh-CN/)

![Pixelify Infinity banner](../branding/banner.png)

> 本文档是简体中文翻译；如有差异，以[英文 README](../README.md)为准。

Pixelify Infinity 是一个独立维护的 Xposed 模块，可模拟部分 Google Pixel 设备属性和系统功能标志。**Google 相册是推荐的 LSPosed 作用域。** 其他已纳入作用域的 App 属于高级且不支持。本项目使用现代 libxposed API，并拥有独立的软件包名称、版本历史和签名身份。

> [!IMPORTANT]
> 本项目与 Google、Google 相册、Pixel、LSPosed 或原上游维护者没有隶属或背书关系。功能可能随 Google 相册、服务端配置、账号、地区、设备或 Android 更新而变化。使用风险由用户自行承担。

## 功能

- 模拟部分 Google Pixel 设备配置。
- 模拟与 Pixel 相关的系统功能标志。
- 提供从 Pixel XL 到 Pixel 10 系列的多种设备配置（含 Spinner 标签 **Pixel 10 Pro Fold (experimental)**／**Pixel 10a (experimental)** 仅身份条目）。
- 首次打开默认 **Pixel XL**（已保存的偏好设置不会自动迁移）。
- Pixel 2025 功能模拟含高置信 experience 标志；`PIXEL_2025_PRELOAD` 为 **MED/LOW** 置信（历史 PRELOAD 配对、非工厂确认），可能无效。
- 可选模拟兼容的 Android 版本。
- 覆盖 ROM 提供的 Pixel feature level。
- 在高级设置中单独选择功能标志。
- 导入、导出和分享模块配置。
- 现代 Material 3 界面，支持动态取色与可选的经典主题。
- 应用内「**诊断**」界面：无需 logcat 即可查看模块启用状态、Hook 里程碑与上次设备模拟 VERIFY 结果，并可复制脱敏报告（不含账户数据）用于问题反馈。

## 设备配置与备份权益说明

根据 Google 的相册云存储政策，不同世代的 Pixel 机型享有不同的相册备份权益与功能层级：

| 配置层级 | 适用机型 | Google 相册备份权益 | 所需相册备份画质设置 | 解锁功能层级 |
| --- | --- | --- | --- | --- |
| **无限制原始画质** | **Pixel XL** *(默认)* | **永久免费无限制备份（原始画质与省空间画质均不占空间）** | **原始画质** 或 **省空间画质** | Pixel 2016 基础层级（最稳定享有无限空间；无 Tensor AI 编辑工具） |
| **仅限无限制省空间画质** | **Pixel 2**, **Pixel 3 XL**, **Pixel 3a XL**, **Pixel 4 XL**, **Pixel 4a**, **Pixel 5**, **Pixel 5a** | **仅限「省空间画质（高画质）」享有免费无限制备份** | **必须设置为「省空间画质」**<br>*(若设为原始画质会扣除账号空间！)* | 中阶 Pixel 功能标志 |
| **仅解锁相片编辑功能** *(无免费备份)* | **Pixel 6 / 6 Pro / 6a**, **Pixel 7 / 7 Pro / 7a**, **Pixel Fold**, **Pixel Tablet**, **Pixel 8 / 8 Pro / 8a**, **Pixel 9 / 9 Pro / XL / Fold / 9a**, **Pixel 10 系列** | **无免费无限制备份**（所有上传均正常扣除 Google 账号空间） | 无论设置何种画质均会扣除云端空间 | 最新 Pixel 相机与 AI 相片编辑功能（如魔术橡皮擦、Ultra HDR、肖像光影、照片清晰等） |

> [!WARNING]
> 若您的主要目的是**获得免费无限制的相册备份空间**，请务必选择 **Pixel XL**（原始画质）或 **Pixel 2 至 Pixel 5a**（备份画质设为**省空间画质**）。选择 Pixel 6 或更新的机型将**不会**享有免费相册空间。

## 如何确认备份是否成功

您可以通过以下两种方法确认设备模拟与免费备份是否正常生效：

### 方法一：检查 Google 相册备份设置横幅

1. 打开 **Google 相册**。
2. 点击右上角个人头像 > **备份**。
3. 点击右上角**齿轮图标**进入备份设置。
4. 检查存储空间区域是否显示确认横幅：<br>
   *“这台 Pixel 可免费备份无限量的照片和视频”*

### 方法二：实际上传照片并检查详细信息

1. 在 Google 相册中拍摄或实际上传备份一张照片／视频。
2. 打开该照片并**向上滑动**查看详细信息（或在电脑浏览器打开 `photos.google.com` 查看照片详细信息）。
3. 确认存储空间信息显示：<br>
   *“此内容不占用您的账号存储空间”*（或显示 **使用 0 字节**）。

### 疑难排解与诊断

若照片上传后仍显示扣除 Google 账号空间：
1. 进入 Pixelify Infinity 的「**诊断**」界面（模块应用 → 诊断），确认模块处于启用状态且上次模拟结果为 **VERIFY 成功**。
2. 确认 Google 相册实际设置的备份画质符合该机型的免费条件（例如 Pixel 2–5 必须设置为**省空间画质**）。
3. 若 Google 相册缓存了旧机型数据，请点击「**强制停止作用域 App**」或前往系统应用设置清除 Google 相册缓存后重新打开。
4. 确认您没有误选为 Pixel 6 或更新的机型（新机型无免费备份权益）。

## 系统要求

- Android 8.0（API 26）或更高版本。
- Root 权限。
- 支持现代 libxposed API 101 的 Xposed 环境，例如兼容的 LSPosed 配置。
- Google 相册（`com.google.android.apps.photos`）。

此现代 API 版本不支持旧式 XposedBridge／EdXposed 环境。

**Android 17 及更高版本兼容性：**所有受支持的 Android 版本（含 API 37+）都会尝试 Build 属性模拟。在部分 Android 17 版本上，ART 会拒绝 `public static final` 的 `Build` 字段的 `Field.set`（`IllegalAccessException`）；这是 ART 限制，与 libxposed API 101 无关。模块使用多策略写入（在可清除 reflected `final` 后的反射 `Field.set`，再尝试多变体 `Unsafe` static put，最后 JNI `libpixelify_build` 后备）、拦截 `SystemProperties` 读取作为次要路径、在包加载早期应用（并在 ready 时再次应用），且会回读校验。不保证在所有 Android 17 ROM 上均成功；VERIFY 仍失败时会通过 Toast 与通知提示，而不是静默失败。功能标志与设备配置仍取决于设备、ROM、框架和 Google 相册版本。

## 支持与已测试版本

Build 属性模拟挂钩的是框架层级类（`android.os.Build` 字段与 `SystemProperties` 读取），并非 Google 相册内部，因此不限定特定 Google 相册版本。功能标志模拟与无限原始画质上传仍会受 Google 相册版本及服务器端配置影响。

已验证组合（维护者实测）：

| Android 版本 | Google 相册版本 | 设备 | 状态 |
| --- | --- | --- | --- |
| Android 15 | 7.84.0.949657053 | 两台 Android 15 设备 | 正常 |
| Android 17 | 7.84.0.949657053 | Pixel 6 Pro（`CP2A.260705.006`） | 正常 |
| Android 16 | — | — | 维护者尚未验证；欢迎反馈 |

若 Google 相册更新后行为改变，请使用应用内「**诊断**」界面（模块应用 → 诊断）查看 Hook 里程碑与上次设备模拟 VERIFY 结果，报告问题时请附上复制的报告。

## 安装

1. 从本仓库的 [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) 页面下载 APK。
2. 安装 APK。
3. 在 Xposed 模块管理器中启用 **Pixelify Infinity**（无限解锁）。
4. 将 **Google 相册**纳入模块作用域（**推荐**）。模块允许多 App 作用域，但其他 App 属于高级且不支持，并有风险。
5. **请勿**将 Play 服务、Play 商店、系统 UI／设置或银行／支付 App 纳入作用域（即使勾选，模块也会 soft-denylist 其中若干项）。
6. 强制停止并重新打开 Google 相册（以及任何其他已纳入作用域的 App）。应用内「强制停止作用范围 App」会依据 LSPosed 模块作用域列表处理；如果模块管理器要求，请重启设备。

请只安装来自本仓库或官方 Xposed Modules Repository 镜像的版本：

- 源码仓库 Releases：https://github.com/samson910022/pixelify-google-photos-modern/releases
- Xposed 镜像 Releases：https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases
- 官方网站列表：https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos
- 项目落地页（GitHub Pages，启用后）：https://samson910022.github.io/pixelify-google-photos-modern/zh-CN/

落地页为多语言产品介绍（源码在 `site/`），只链接到官方下载渠道，**不**托管 APK。

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

1. 确认模块已启用，并且 Google 相册在作用域内（推荐）。
2. 强制停止并重新打开 Google 相册（以及任何其他已纳入作用域的 App）。
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
