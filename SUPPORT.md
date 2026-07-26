# Support

## Before requesting help

1. Install the latest official release and verify its signer as described in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).
2. Confirm the module is enabled in a compatible modern-libxposed environment.
3. Scope the module only to Google Photos (`com.google.android.apps.photos`).
4. Force-stop and reopen Google Photos; reboot if required by the Xposed manager.
5. Search existing issues and release notes.

Pixelify Infinity is a separate application from the legacy `balti.xposed.pixelifygooglephotos` package. Enabling or configuring the legacy package does not configure this one.

## Useful diagnostic information

A reproducible report should include:

- Pixelify Infinity version and version code;
- Android version, device/ROM, and architecture;
- Xposed manager/framework and API version (for example LSPosed variant);
- Google Photos version (exact build string when possible);
- exact reproduction steps and expected/actual behavior;
- whether a VERIFY toast/notification appeared after force-stopping and reopening Google Photos; and
- the smallest relevant, sanitized log excerpt (prefer logcat lines for tag `Pixelify`).

### Load / VERIFY signals

After enable + correct scope + force-stop/reopen Photos:

- **No toast and no notification** often means the module did not load or did not reach VERIFY.
- **Toast or notification about VERIFY / device spoof failed** means the module loaded, but Build spoof VERIFY failed (common on some Android 17+ ROMs; multi-strategy writes are attempted, success is not guaranteed on every ROM).
- **No visible failure and Photos still shows the real device model** still needs sanitized `Pixelify` logcat lines to distinguish load failure from VERIFY failure.

Remove account identifiers, device serials, file-system paths, tokens, exported preferences, and unrelated application data before sharing diagnostics. Enable verbose logging only while reproducing a problem, then disable it.

## Where to report

Use GitHub Issues for reproducible bugs and feature requests. Use the structured issue forms when available. Do not use public issues for vulnerabilities; follow [SECURITY.md](SECURITY.md).

Opened issues (and `/triage` / `/review` comments on issues) may receive an **advisory issue investigation** comment that scores report completeness, lists missing evidence, and proposes root-cause hypotheses. That bot is advisory only and does not replace maintainer triage. Details: [docs/AI_REVIEW_BOT.md](docs/AI_REVIEW_BOT.md).

Feature availability may depend on Google Photos, server-side configuration, account, region, device, ROM, or Android version. Build-property spoofing is attempted on all supported Android versions, including API 37+, but success is not guaranteed on every ROM.
