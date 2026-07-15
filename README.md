# Pixelify Photos

[English](README.md) · [繁體中文](docs/README.zh-TW.md) · [简体中文](docs/README.zh-CN.md) · [日本語](docs/README.ja.md)

Pixelify Photos is an independently maintained Xposed module that spoofs selected Google Pixel device properties and system feature flags for Google Photos. It uses the modern libxposed API and has its own package name, release history, and signing identity.

> [!IMPORTANT]
> This project is not affiliated with or endorsed by Google, Google Photos, Pixel, LSPosed, or the original upstream maintainers. Feature availability can change with Google Photos, server-side configuration, account, region, device, or Android updates. Use the module at your own risk.

## Features

- Spoof selected Google Pixel device profiles.
- Spoof Pixel-related system feature flags.
- Choose from device profiles spanning Pixel XL through newer Pixel generations.
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

**Android 17+ compatibility:** build-property spoofing is intentionally disabled on Android API 37 and later because modifying those fields is unsafe. Feature-flag spoofing may still work, so support on those Android versions is partial and depends on the device, ROM, framework, and Google Photos version.

## Installation

1. Download the APK from this repository's [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) page.
2. Install the APK.
3. Enable **Pixelify Photos** in your Xposed module manager.
4. Set the module scope to **Google Photos** only.
5. Force-stop and reopen Google Photos. Reboot the device if the module manager requires it.

Only install releases obtained from the repository above or its future official Xposed Modules Repository mirror. See [Release verification](#release-verification) before installing a downloaded APK.

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

Pixelify Photos does not include analytics or advertising SDKs. The app uses network access to check the configured GitHub/Xposed release metadata for updates and to open project links. Module settings and exported configuration files remain under the user's control.

See [PRIVACY.md](PRIVACY.md) for details.

## Troubleshooting and support

Before reporting a problem:

1. Confirm that the module is enabled and scoped only to Google Photos.
2. Force-stop and reopen Google Photos.
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
