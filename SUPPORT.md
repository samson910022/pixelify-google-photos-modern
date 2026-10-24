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
- whether a VERIFY toast/notification appeared after force-stopping and reopening Google Photos (absence alone is inconclusive);
- the copied report from the in-app **Diagnostics** screen (see below); and
- only if the Diagnostics report is insufficient, the smallest relevant, sanitized verbose-logging excerpt (tag `Pixelify`). The Diagnostics report is normally enough on its own — no logcat required.

### In-app diagnostics screen

The module app has a **Diagnostics** screen (module app → Diagnostics) that shows, without logcat:

- whether the app is connected to the Xposed manager service and which packages are in the LSPosed scope (service connection is not evidence that hooks ran);
- lifecycle callbacks recorded in a scoped process (module loaded, package loaded, package-ready); only when the telemetry store is readable and the run is current does a missing package-ready record mean **not observed yet**—unavailable, prior-run, or unknown-currentness states are not evidence of absence;
- individual registration results for the three feature hooks and both `SystemProperties.get` overloads, summarized as none/partial/all/unknown, with reports older than 24 hours marked stale;
- whether a feature or `SystemProperties` interceptor was entered at least once; unknown identity is distinct from not observed, and neither status identifies Google Photos as the caller or proves a spoofed value was returned;
- whether the reported package origin was bound to the OS-observed IPC sender; token-only broadcast fallback is explicitly unverified, and its PID is unknown. Provider PID is captured from Binder, never from payload data;
- the last device-spoof VERIFY outcome: Build-field readback OK/FAIL, failed fields, device, package, time, native writer (JNI) availability, and a legacy indicator for whether both `SystemProperties` overloads registered; and
- real `Build` values of this device vs the spoof target of the selected profile.

**Copy diagnostics report** copies a sanitized report for pasting into an issue. The report includes the module scope list and device Build values, but no account data.

Since 1.4.0, the hooked process attempts an explicit broadcast fallback when a direct ContentProvider write fails, including when Android 11+ package visibility (AppsFilter) blocks it. On API 34+, the fallback shares OS sender identity so the receiver can verify the exact package; if identity cannot be sent or verified, the report is rejected and is not downgraded to token-only. Before API 34, a token-authenticated broadcast may persist as explicitly **unverified-origin telemetry** because the receiver cannot bind its claimed package/process origin to OS identity. This telemetry, including any VERIFY outcome, must not be treated as verified Google Photos activity or as Photos success. Even when `sendBroadcast` returns, that confirms dispatch only—not receiver acceptance or persistence. The module app provisions the token in its private preferences and syncs it to Xposed remote preferences when the service binds. If token creation or synchronization fails, new reports may be rejected or may fail to persist; that does not by itself mean the target cannot read a previously synchronized token. Reopen the module app and target app to retry synchronization and telemetry. Run ordering uses elapsed-time generations, not wall-clock ordering. A hashed OS boot UUID epoch establishes whether a run is current; the numeric OS boot counter is optional metadata, and its absence does not invalidate a usable epoch. If no usable boot epoch is available, currentness is unknown. Unverified package-specific results remain unknown. If the Diagnostics screen shows blank status on an older release, update to 1.4.0 or later before troubleshooting further.

Interpretation: when the telemetry store is readable and the run is current, missing lifecycle or registration records mean no trusted/persisted callback was recorded; they do **not** prove that no callback reached the app. If the store is unavailable/unreadable or run currentness is prior/unknown, missing records are not evidence of absence. Before API 34, token-only fallback telemetry may persist, but the app labels its origin unverified and treats its VERIFY result and package-specific evidence as unknown—not as verified Google Photos activity or success. API 34 identity-send or identity-verification failures are rejected. Check LSPosed enable + scope, then reopen the target app. A missing package-load or package-ready record is bounded to “not recorded for this current run” / “not observed yet” only when the store is readable and currentness is known; it does not establish whether a callback occurred, that the package never became ready, or that the framework is incompatible. Missing or partial registrations describe registration outcomes only. Future timestamps are retained and labeled as clock anomalies/stale, not treated as current success. A passing VERIFY means only that the checked `Build` fields read back as expected in the hooked process; it does not verify feature interception or Google Photos behavior. Treat registration and invocation results as separate evidence, not a single overall success state.

Diagnostics currently use one package origin per process run. If a callback for a second scoped package arrives in a process whose run was started by another package, its package-specific telemetry (including VERIFY) is intentionally omitted rather than attributed to the run origin; hook attempts still proceed. That telemetry case is unsupported, and a missing record is not evidence that the callback did not occur.

### Load / VERIFY signals

After enable + recommended Photos scope + force-stop/reopen Photos:

- **No toast and no notification is inconclusive**: silence alone cannot determine whether the module loaded or reached VERIFY. A successful VERIFY intentionally has no failure UI.
- **A toast or notification reporting VERIFY / device-spoof failure** indicates a failed Build readback check for that report; it does not establish feature-interception behavior (multi-strategy writes are attempted, and success is not guaranteed on every ROM).
- **No visible failure and Photos still shows the real device model**: open the in-app **Diagnostics** screen — check readable/current lifecycle milestones, each registration outcome, runtime interceptor observations, and the separate Build readback VERIFY. State only what the readable, current telemetry indicates; unavailable, stale, or unknown-currentness data is inconclusive. A connected manager service or package-ready callback alone does not establish hook success. Sanitized logcat is only needed for deeper debugging.

### Verifying backup success and troubleshooting quota issues

To confirm whether unlimited Google Photos backup is active and not consuming cloud quota:

1. **Method 1 (Backup Settings banner):** Open Google Photos > tap profile avatar > **Backup** > tap the gear icon (**Backup settings**) > verify the banner states: *"This Pixel can back up unlimited photos & videos at no charge."*
2. **Method 2 (Uploaded photo details):** Back up a photo in Google Photos > swipe up on the photo details (or check on `photos.google.com`) > verify that it states: *"This item doesn't take up space in your account storage"* (0 bytes used).

**Troubleshooting quota consumption:**
- **Selected device model entitlement:**
  - **Pixel XL:** Unlimited Original Quality and Storage Saver (0 quota used).
  - **Pixel 2 – Pixel 5a:** Unlimited Storage Saver ONLY. Google Photos backup quality **MUST** be set to **Storage saver**; setting Original Quality will consume cloud storage quota.
  - **Pixel 6 and newer:** No free unlimited backup. Uploads consume Google Account quota regardless of quality setting.
- **Module state:** Open the in-app **Diagnostics** screen and inspect registration and runtime observations alongside the last device-spoof **Build readback VERIFY**. A service connection or VERIFY OK alone does not establish that Google Photos intercepted the relevant API.
- **Stale cache:** Force-stop Google Photos (using the in-app **Force-stop scoped apps** button) or clear Google Photos app cache and reopen.

Remove account identifiers, device serials, file-system paths, tokens, exported preferences, and unrelated application data before sharing diagnostics. Enable verbose logging only while reproducing a problem, then disable it.

## Where to report

Use GitHub Issues for reproducible bugs and feature requests. Use the structured issue forms when available. Do not use public issues for vulnerabilities; follow [SECURITY.md](SECURITY.md).

Opened issues (and `/triage` / `/review` comments on issues) may receive an **advisory issue investigation** comment that scores report completeness, lists missing evidence, and proposes root-cause hypotheses. That bot is advisory only and does not replace maintainer triage. Details: [docs/AI_REVIEW_BOT.md](docs/AI_REVIEW_BOT.md).

Feature availability may depend on Google Photos, server-side configuration, account, region, device, ROM, or Android version. Build-property spoofing is attempted on all supported Android versions, including API 37+, but success is not guaranteed on every ROM.

Build-property spoofing hooks framework-level classes (`android.os.Build` fields and `SystemProperties` reads), not Google Photos internals, so it is not tied to a specific Google Photos build. The tested-version matrix (maintainer-verified Android × Google Photos combinations) is documented in the [README](README.md#supported-and-tested-versions).
