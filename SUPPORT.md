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
- Xposed manager/framework and API version;
- Google Photos version;
- exact reproduction steps and expected/actual behavior; and
- the smallest relevant, sanitized log excerpt.

Remove account identifiers, device serials, file-system paths, tokens, exported preferences, and unrelated application data before sharing diagnostics. Enable verbose logging only while reproducing a problem, then disable it.

## Where to report

Use GitHub Issues for reproducible bugs and feature requests. Use the structured issue forms when available. Do not use public issues for vulnerabilities; follow [SECURITY.md](SECURITY.md).

Feature availability may depend on Google Photos, server-side configuration, account, region, device, ROM, or Android version. Build-property spoofing is intentionally limited on Android versions where modifying those fields is unsafe.
