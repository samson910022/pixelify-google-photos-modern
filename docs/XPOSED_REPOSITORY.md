# Xposed Modules Repository Publication

The canonical source repository and an `Xposed-Modules-Repo` distribution mirror serve different purposes. Do not convert this source repository into the mirror and do not publish the template automatically.

## Prepared mirror metadata

The files under [`distribution/xposed-repository/`](../distribution/xposed-repository/) are templates for a future repository named:

```text
Xposed-Modules-Repo/io.github.samson910022.pixelifyphotos
```

Before submitting or updating that mirror:

1. Confirm the application ID and Xposed package remain `io.github.samson910022.pixelifyphotos`.
2. Copy the prepared `README.md`, `SUMMARY`, `SOURCE_URL`, `SCOPE`, and `update_info.json` into the mirror root.
3. Keep `SCOPE` limited to `com.google.android.apps.photos` unless a reviewed source change requires otherwise.
4. Build with `verifiedRelease` and verify the stable signer fingerprint.
5. Publish the APK using the repository's required release naming convention. For version code `1` and version name `1.0.0`, the expected tag is `1-1.0.0`.
6. Verify the mirror release asset after upload before announcing it.

Submission, mirror creation, release creation, and repository-visibility changes require explicit maintainer approval.

## Update metadata

[`update_info.json`](../update_info.json) is a small application-specific update feed. The app reads only `latest_version_code`; it is not a substitute for Xposed Modules Repository metadata. Keep its value synchronized with `defaultConfig.versionCode` and the prepared mirror copy at `distribution/xposed-repository/update_info.json`.
