# Pixelify Infinity

[English](README.md) · [繁體中文](docs/README.zh-TW.md) · [简体中文](docs/README.zh-CN.md) · [日本語](docs/README.ja.md)

**Project website (GitHub Pages, once enabled):** [https://samson910022.github.io/pixelify-google-photos-modern/](https://samson910022.github.io/pixelify-google-photos-modern/)

![Pixelify Infinity banner](branding/banner.png)

Pixelify Infinity is an independently maintained Xposed module that spoofs selected Google Pixel device properties and system feature flags. **Google Photos is the recommended LSPosed scope.** Extra scoped apps are advanced and unsupported. It uses the modern libxposed API and has its own package name, release history, and signing identity.

> [!IMPORTANT]
> This project is not affiliated with or endorsed by Google, Google Photos, Pixel, LSPosed, or the original upstream maintainers. Feature availability can change with Google Photos, server-side configuration, account, region, device, or Android updates. Use the module at your own risk.

## Features

- Spoof selected Google Pixel device profiles.
- Spoof Pixel-related system feature flags.
- Choose from device profiles spanning Pixel XL through the Pixel 10 series (including spinner labels **Pixel 10 Pro Fold (experimental)** / **Pixel 10a (experimental)** identity-only entries).
- First open defaults to **Pixel XL** (existing saved preferences are not migrated).
- Pixel 2025 feature spoof includes high-confidence experience flags; `PIXEL_2025_PRELOAD` is **MED/LOW** confidence (historical pairing, not factory-confirmed) and may be a no-op.
- Optionally spoof a compatible Android version.
- Override ROM-provided Pixel feature levels.
- Select individual feature flags through an advanced configuration screen.
- Import, export, and share module configuration.

## Requirements

- Android 8.0 (API 26) or later.
- Root access.
- An Xposed environment with modern libxposed API 101 support, such as a compatible LSPosed setup.
- Google Photos (`com.google.android.apps.photos`).

Legacy XposedBridge/EdXposed environments are not supported by this modern-API build.

**Android 17+ compatibility:** Build-property spoofing is attempted on all supported Android versions, including API 37+. On some Android 17 builds, ART blocks `Field.set` on `public static final` `Build` fields (`IllegalAccessException`); that is an ART restriction, not a libxposed API 101 limitation. The module uses multi-strategy writes (reflection `Field.set` after clearing reflected `final` where possible, multi-variant `Unsafe` static puts, then a JNI `libpixelify_build` fallback), hooks `SystemProperties` reads as a secondary path, applies spoofing early on package load (and again when the package is ready), and re-reads fields for verification. Success is not guaranteed on every Android 17 ROM; persistent VERIFY failures raise a Toast and notification instead of failing silently. Feature-flag spoofing and device profiles still depend on the device, ROM, framework, and Google Photos version.

## Supported and tested versions

Build-property spoofing hooks framework-level classes (`android.os.Build` fields and `SystemProperties` reads), not Google Photos internals, so it is not tied to a specific Google Photos build. Feature-flag spoofing and unlimited-upload behavior are still affected by Google Photos versions and server-side configuration.

Verified combinations (maintainer-tested):

| Android version | Google Photos version | Device | Status |
| --- | --- | --- | --- |
| Android 15 | 7.84.0.949657053 | two Android 15 devices | Working |
| Android 17 | 7.84.0.949657053 | Pixel 6 Pro (`CP2A.260705.006`) | Working |
| Android 16 | — | — | Not verified by the maintainer; reports welcome |

If a Google Photos update changes behavior, the in-app **Diagnostics** screen (module app → Diagnostics) shows hook milestones and the last device-spoof VERIFY result; include the copied report when reporting an issue.

## Installation

1. Download the APK from this repository's [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) page.
2. Install the APK.
3. Enable **Pixelify Infinity** in your Xposed module manager.
4. Keep **Google Photos** in the module scope (**recommended**). The module metadata allows multi-app scope, but extra apps are advanced/unsupported and carry risk.
5. Do **not** scope Play Services, Play Store, system UI/settings, or banking/payment apps (the module soft-denylists several of these even if selected).
6. Force-stop and reopen Google Photos (and any other scoped apps). The in-app **Force-stop scoped apps** button uses the LSPosed module scope list. Reboot the device if the module manager requires it.

Only install releases obtained from this repository or the official Xposed Modules Repository mirror:

- Source releases: https://github.com/samson910022/pixelify-google-photos-modern/releases
- Xposed mirror releases: https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos/releases
- Website listing: https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos
- Project landing (GitHub Pages, once enabled): https://samson910022.github.io/pixelify-google-photos-modern/

The project landing is a multilingual product page under `site/`. It links to official download channels only and does **not** host APK files.

See [Release verification](#release-verification) before installing a downloaded APK.

## Upgrading from the legacy project

This project uses the independent application ID:

```text
io.github.samson910022.pixelifyphotos
```

It is a separate application, not an in-place upgrade of `balti.xposed.pixelifygooglephotos`. The two applications may coexist. When migrating, enable and scope the new module separately; settings are not migrated automatically.

See [FORK_NOTICE.md](FORK_NOTICE.md) for the complete maintenance and attribution notice.

## Release verification

Official releases use a stable signing certificate. Before installing an APK, verify that its signer SHA-256 matches the fingerprint published in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md). Release pages should also provide a checksum for each downloadable artifact.

The public certificate is available at [`certificates/pixelifyphotos-release-cert.pem`](certificates/pixelifyphotos-release-cert.pem). Private signing keys are never distributed in this repository.

## Privacy and network access

Pixelify Infinity does not include analytics or advertising SDKs. The app uses network access to check the configured GitHub/Xposed release metadata for updates and to open project links. Module settings and exported configuration files remain under the user's control.

See [PRIVACY.md](PRIVACY.md) for details.

## Troubleshooting and support

Before reporting a problem:

1. Confirm that the module is enabled and that Google Photos is in scope (recommended).
2. Force-stop and reopen Google Photos (and any other scoped apps).
3. Reproduce the issue with verbose logging enabled only when diagnostics are needed.
4. Remove account identifiers and other personal information from logs.
5. Search existing [issues](https://github.com/samson910022/pixelify-google-photos-modern/issues).

Use GitHub Issues for reproducible bugs and feature requests. Security vulnerabilities should be reported according to [SECURITY.md](SECURITY.md), not through a public issue.

## Development

Build instructions, contribution rules, test commands, and release-maintainer notes are kept in [CONTRIBUTING.md](CONTRIBUTING.md).

## License and attribution

Licensed under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency notices.

This project is derived from [BaltiApps/Pixelify-Google-Photos](https://github.com/BaltiApps/Pixelify-Google-Photos). Additional acknowledgements:

- [libxposed/api](https://github.com/libxposed/api)
- [LSPosed](https://github.com/LSPosed/LSPosed)

Google Photos, Google Pixel, Android, and related names are trademarks of their respective owners.
