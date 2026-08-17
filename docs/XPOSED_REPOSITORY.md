# Xposed Modules Repository Publication

The canonical source repository and the `Xposed-Modules-Repo` distribution mirror serve different purposes. Do not convert this source repository into the mirror and do not publish the template automatically.

## Live mirror

| Item | Value |
| --- | --- |
| Mirror repository | https://github.com/Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos |
| Official website page | https://modules.lsposed.org/module/io.github.samson910022.pixelifyphotos |
| Package / repo name | `io.github.samson910022.pixelifyphotos` |
| Module display name | `Pixelify Infinity` |

Prefer the `modules.lsposed.org` URL above. The alternate host form `https://xposed.app/modules/<package>` is not the canonical path used by the published site generator (`siteUrl` is `https://modules.lsposed.org`, and pages are under `/module/<package>`).

## Prepared mirror metadata

The files under [`distribution/xposed-repository/`](../distribution/xposed-repository/) are templates for the mirror repository root:

- `README.md` — full module description rendered on the website
- `SUMMARY` — short plain-text summary for list cards
- `SOURCE_URL` — source repository URL
- `SCOPE` — recommended scope JSON array
- `update_info.json` — app-specific update feed consumed by the module UI (not an official Xposed meta file, but required by this project)
- `banner.png` — image referenced by the mirror README

Before submitting or updating that mirror:

1. Confirm the application ID and Xposed package remain `io.github.samson910022.pixelifyphotos`.
2. Copy the prepared mirror files into the mirror repository root.
3. Keep `SCOPE` limited to `com.google.android.apps.photos` unless a reviewed source change requires otherwise.
4. Set GitHub repository **About** fields on the mirror:
   - **Description** = module display name (`Pixelify Infinity`)
   - **Website / homepage** = support URL (`https://github.com/samson910022/pixelify-google-photos-modern/issues`)
5. Build with `verifiedRelease` and verify the stable signer fingerprint.
6. Publish a GitHub Release with:
    - **Tag**: `{versionCode}-{versionName}` (example: `7-1.2.0`)
    - **Title**: version name (example: `1.2.0`)

   - **Body**: changelog
   - **Asset**: signed APK (`content-type` must be an Android package archive)
7. Verify the mirror release asset after upload before announcing it.

## Automated CI Synchronization

The repository includes an automated workflow (`.github/workflows/sync-xposed-repo.yml`) that automatically synchronizes metadata and publishes releases to `Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos` whenever a new release is published on the canonical source repository.

### How it works:
1. Maintainer compiles and signs the release locally using `:app:verifiedRelease`.
2. Maintainer publishes the release (with signed APK asset) on the main repository (`samson910022/pixelify-google-photos-modern`).
3. GitHub Actions workflow triggers automatically on release publication:
   - Downloads the signed release APK from the release.
   - Verifies the APK certificate fingerprint matches the pinned release certificate.
   - Formats the mirror tag as `{versionCode}-{versionName}` (e.g. `7-1.2.0`).
   - Synchronizes metadata files from `distribution/xposed-repository/` to the mirror's `main` branch.
   - Creates the matching release and uploads the signed APK to `Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos`.

### Required Secret:
- Set `XPOSED_REPO_TOKEN` in canonical repository Settings → Secrets and variables → Actions (a Personal Access Token with `repo` write permissions for `Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos`).

Submission, mirror creation, release creation, and repository-visibility changes require explicit maintainer approval.

## Why a module may not appear on the website

The official site generator marks a repository as a published module only when all of the following are true:

1. Repository name contains a `.` (package-style name)
2. Repository **Description** is non-empty
3. At least one non-draft release has:
   - tag matching `^[0-9]+-.+$`
   - at least one APK asset
4. Repository is public and not hidden by a `HIDE` file
5. Name is not the example repository

Incomplete repositories will not be shown. After a valid metadata or release update, the organization build usually refreshes the public index within about five minutes.

## Update metadata

[`update_info.json`](../update_info.json) is a small application-specific update feed. The app reads only `latest_version_code`; it is not a substitute for Xposed Modules Repository metadata. Keep its value synchronized with `defaultConfig.versionCode` and the prepared mirror copy at `distribution/xposed-repository/update_info.json`.
