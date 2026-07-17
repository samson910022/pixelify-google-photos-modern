# Changelog

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
