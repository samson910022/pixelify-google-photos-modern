# Support

## Project website

A multilingual product landing lives under `site/` and is intended for GitHub Pages once enabled:

- English: https://samson910022.github.io/pixelify-google-photos-modern/
- 繁體中文: https://samson910022.github.io/pixelify-google-photos-modern/zh-TW/
- 简体中文: https://samson910022.github.io/pixelify-google-photos-modern/zh-CN/
- 日本語: https://samson910022.github.io/pixelify-google-photos-modern/ja/

The landing summarizes install requirements and links to official GitHub / Xposed download channels only. It does **not** host APK files. Prefer the repository [Releases](https://github.com/samson910022/pixelify-google-photos-modern/releases) page for downloads and verification details in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Before requesting help

1. Install the latest official release and verify its signer as described in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
2. Confirm the module is enabled in a compatible modern-libxposed environment.
3. Keep Google Photos (`com.google.android.apps.photos`) in scope (**recommended**). Extra apps are advanced/unsupported; never scope Play Services, Play Store, system UI, or banking/payment apps.
4. Force-stop and reopen Google Photos (and any other scoped apps); reboot if required by the Xposed manager.
5. Search existing issues and release notes.

Pixelify Infinity is a separate application from the legacy `balti.xposed.pixelifygooglephotos` package. Enabling or configuring the legacy package does not configure this one.

## Useful diagnostic information

A reproducible report should include:

- Pixelify Infinity version and version code;
- Android version, device/ROM, and architecture;
- Xposed manager/framework and API version (for example LSPosed variant);
- Google Photos version (exact build string when possible);
- exact reproduction steps and expected/actual behavior;
- whether a VERIFY toast/notification appeared after force-stopping and reopening Google Photos;
- the copied report from the in-app **Diagnostics** screen (see below); and
- only if the Diagnostics report is insufficient, the smallest relevant, sanitized verbose-logging excerpt (tag `Pixelify`). The Diagnostics report is normally enough on its own — no logcat required.

### In-app diagnostics screen

The module app has a **Diagnostics** screen (module app → Diagnostics) that shows, without logcat:

- whether the module is active and which packages are in the LSPosed scope;
- hook milestones recorded by the module in the Google Photos process: module loaded, package loaded (early device spoof), package ready (hooks re-applied);
- the last device-spoof VERIFY outcome: OK/FAIL, failed fields, device, package, time, native writer (JNI) availability, and whether `SystemProperties` hooks are registered; and
- real `Build` values of this device vs the spoof target of the selected profile.

**Copy diagnostics report** copies a sanitized report for pasting into an issue. The report includes the module scope list and device Build values, but no account data.

Since 1.4.0, this telemetry is delivered even when Android 11+ package visibility (AppsFilter) blocks direct ContentProvider IPC: the hooked process falls back to an authenticated explicit broadcast (per-install token, fail-closed authorization). If the Diagnostics screen shows blank status on an older release, update to 1.4.0 or later before troubleshooting further.

Interpretation: no hook milestones at all usually means the module never loaded into Google Photos (check LSPosed enable + scope, then reboot); "package loaded but never ready" points to a framework/API compatibility problem in the Xposed variant; a failed VERIFY means the module ran but could not write `Build` fields.

### Load / VERIFY signals

After enable + recommended Photos scope + force-stop/reopen Photos:

- **No toast and no notification** often means the module did not load or did not reach VERIFY.
- **Toast or notification about VERIFY / device spoof failed** means the module loaded, but Build spoof VERIFY failed (common on some Android 17+ ROMs; multi-strategy writes are attempted, success is not guaranteed on every ROM).
- **No visible failure and Photos still shows the real device model**: open the in-app **Diagnostics** screen — an empty/absent milestone section points to a load failure, while a recorded VERIFY FAIL points to a spoof write failure. Sanitized logcat is only needed for deeper debugging.

Remove account identifiers, device serials, file-system paths, tokens, exported preferences, and unrelated application data before sharing diagnostics. Enable verbose logging only while reproducing a problem, then disable it.

## Where to report

Use GitHub Issues for reproducible bugs and feature requests. Use the structured issue forms when available. Do not use public issues for vulnerabilities; follow [SECURITY.md](SECURITY.md).

Opened issues (and `/triage` / `/review` comments on issues) may receive an **advisory issue investigation** comment that scores report completeness, lists missing evidence, and proposes root-cause hypotheses. That bot is advisory only and does not replace maintainer triage. Details: [docs/AI_REVIEW_BOT.md](docs/AI_REVIEW_BOT.md).

Feature availability may depend on Google Photos, server-side configuration, account, region, device, ROM, or Android version. Build-property spoofing is attempted on all supported Android versions, including API 37+, but success is not guaranteed on every ROM.

Build-property spoofing hooks framework-level classes (`android.os.Build` fields and `SystemProperties` reads), not Google Photos internals, so it is not tied to a specific Google Photos build. The tested-version matrix (maintainer-verified Android × Google Photos combinations) is documented in the [README](README.md#supported-and-tested-versions).
