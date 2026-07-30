# Changelog

## [Unreleased]

## [1.1.0] - 2026-07-30

### Added

- Pixel **2025** feature level and **Pixel 10 series** device profiles (10 / 10 Pro / 10 Pro XL) with cited Android 16 launch fingerprints (`BD3A.250721.001`).
- `PIXEL_2025_PRELOAD` is included at **MED/LOW** confidence (historical PRELOAD pairing; not factory-confirmed) and may be a no-op; experience flags remain the high-confidence path.
- Experimental **Pixel 10 Pro Fold (experimental)** (`rango`) and **Pixel 10a (experimental)** (`stallion`) identity-only spinner entries (codenames high-confidence; fingerprints omitted until cited — no invented props). Build `MODEL` stays the bare marketing name.
- Multi-app LSPosed support: `staticScope=false` with Photos still the recommended `scope.list` entry; soft denylist skips spoof for Play Services / Play Store / selected system packages even if scoped.
- **Force-stop scoped apps** button stops Google Photos plus other packages from the LSPosed module scope (`XposedService.getScope`), after denylist/name filters.
- Build helper `scripts/with-android-env.sh` (+ optional gitignored `scripts/env.local.sh`) so JDK 17 / Android SDK paths load reliably for local and agent shells.
- APK/AAB base name `PixelifyInfinity-<versionName>-<buildType>` (for example `PixelifyInfinity-1.1.0-release.apk`).

### Changed

- Bumped version to `1.1.0` (`versionCode 6`).
- First-open default device spoof is **Pixel XL** (was Pixel 5). Already-saved preferences are not migrated.
- Fixed Pixel 9a codename **`tehua` → `tegu`** with a cited Android 16 fingerprint; security patch omitted for that build (no cited date). Most full device profiles still spoof `SECURITY_PATCH` when a cited value is present.
- Module hooks any non-denylisted first package LSPosed injects (not hard-gated to Photos only).
- AI review bot `1.3.0`: optionally apply allowlisted `SUGGESTED_LABELS` after issue investigation (additive only).

### Documentation

- Documented recommended (not exclusive) Photos scope, multi-app risk, Pixel 10 series, default Pixel XL, and experimental Fold/10a honesty across README/translations/SUPPORT/CONTRIBUTING/AGENTS.
- Documented `JAVA_HOME` vs `local.properties` `sdk.dir` and the env helper in `CONTRIBUTING.md` / `AGENTS.md`.
- Added a multilingual static product landing under `site/` (EN / zh-TW / zh-CN / ja) and a prepared GitHub Pages deploy workflow (`.github/workflows/pages.yml`). Pages is not enabled in repository settings until a separate maintainer step.
- Linked the future project website from `README.md`, translated READMEs, and `SUPPORT.md`. The landing links to official download channels only and does not host APK files.
- Documented `site/` layout and Pages workflow notes in `CONTRIBUTING.md` and `AGENTS.md`; updated `docs/PUBLICATION_CHECKLIST.md` for least-privilege Pages deploy.
- Document maintainer installation checklist for the GitHub AI bot (secret, workflow permissions, distinct triage labels, label allowlist).
- Documented the advisory GitHub AI bot for **issue investigation** vs **PR code review**, including fail-closed quality gates, thread-aware triage, slash commands, OCR, and maintainer dry-run/test instructions (`docs/AI_REVIEW_BOT.md`, `github_bot/README.md`, `CONTRIBUTING.md`, `SUPPORT.md`, `AGENTS.md`).

## [1.0.4] - 2026-07-17

### Fixed

- Hardened Android 17+ device spoofing after field writes still failed on some devices with 1.0.3.
- On Android 17 (API 37+), ART can block `Field.set` on `public static final` `android.os.Build` / `Build.VERSION` fields with `IllegalAccessException`. That restriction is an ART field-write limitation; it is **not** caused by libxposed API 101.
- Multi-strategy Build static writes (success = post-write readback match):
  - clear only the Java `FINAL` bit on the real reflected access-flags int (preserve ART/hidden-API high bits), then reflection `Field.set`
  - multi-variant `Unsafe` static puts (`putObject` / `putReference` / volatile forms, alternate static bases)
  - JNI fallback via `libpixelify_build` using `SetStatic*Field` + readback (**no** heuristic ArtField memory patching, which could corrupt ART metadata)
  - best-effort hidden-API exemption before reflective access
- Secondary path: intercept `android.os.SystemProperties.get` for `ro.product.*` / fingerprint-related keys when Xposed hooks are available.
- Apply device spoof early on `onPackageLoaded` and re-apply on `onPackageReady`.
- VERIFY failures continue to surface via Toast and a high-importance notification (once per process) instead of silent log-only failure; optional Android-version spoof keys are included in VERIFY.

### Notes

- This release does **not** claim universal success on every Android 17 build/ROM. Some firmwares may still leave `Build` fields unchanged after all write strategies; check logcat tag `Pixelify` for `Loaded libpixelify_build` / `via Field.set` / `via Unsafe` / `via JNI` / `VERIFY FAIL` and the on-device VERIFY alert if the Google Photos model string does not change.
- The native library is loaded from the **module** `nativeLibraryDir` first, then extracted into the **host** process `codeCacheDir` when needed, because under LSPosed the code runs inside Google Photos and bare `System.loadLibrary` usually fails.

### Changed

- Bumped version to `1.0.4` (`versionCode 5`).

## [1.0.3] - 2026-07-17

### Fixed

- Android 17+ Build spoofing: when ART rejects `Field.set` on `public static final` `Build` fields (`IllegalAccessException`), fall back to `Unsafe` static field writes so model spoofing can actually take effect.
- Device spoof VERIFY failures now surface to the user via Toast and a high-importance notification (once per process), instead of only silent logcat errors.

### Changed

- `DeviceSpoofer.setStaticField` returns success based on post-write readback and logs which write strategy succeeded.
- Bumped version to `1.0.3` (`versionCode 4`).

## [1.0.2] - 2026-07-16

### Changed

- Renamed the user-facing display name to **Pixelify Infinity** (Traditional Chinese launcher label: **Pixelify 無限解鎖**) to better distinguish this module from the legacy Pixelify Google Photos package and to match the infinity/unlocker branding.
- Replaced the launcher icon with a new adaptive icon (infinity + keyhole, four-color geometry).
- Added a project banner image for the README landing page.
- Bumped version to `1.0.2` (`versionCode 3`).

## [1.0.1] - 2026-07-16

### Fixed

- Restored `Build` property spoofing on Android API 37+; the previous hard skip was not required by libxposed API 101 and prevented model-name spoofing (for example the Google Photos UI still showed the real device model such as Pixel 6 Pro).
- Selecting device **None** (or an empty feature list) now fully pass-through for feature flags, matching module-off behaviour instead of forcing all Pixel flags to `false`.

### Changed

- Bumped version to `1.0.1` (`versionCode 2`).
- Documented that Android 17+ Build spoofing is attempted with logged failures rather than disabled for safety.
- Added post-write verification logs for spoofed `Build` fields to aid device-side diagnosis.

## [1.0.0] - 2026-07-16

### Changed

- Adopted the independent application ID `io.github.samson910022.pixelifyphotos`.
- Reset independent versioning to `1.0.0` (`versionCode 1`).
- Migrated to the modern libxposed API 101 module format.
- Established a stable release certificate and public fingerprint.
- Moved update, support, and release links to independently maintained locations.
- Added fail-closed signer verification for official publication builds.
- Documented partial Android API 37+ support where Build-property spoofing is disabled for safety.

Earlier development history from the legacy package line is archived in [docs/LEGACY_CHANGELOG.md](docs/LEGACY_CHANGELOG.md).
