# Contributing to Pixelify Infinity

Thank you for helping improve Pixelify Infinity. This repository is an independently maintained derivative project; changes must preserve its independent package and signing identity.

## Before opening an issue

- Use the latest available release.
- Confirm the module is enabled with Google Photos in scope (recommended; extra apps are advanced/unsupported).
- Search existing issues and release notes.
- Remove account identifiers, device serials, tokens, and other personal data from logs or exported configuration.
- Follow [SECURITY.md](SECURITY.md) for vulnerabilities instead of opening a public issue.

## Development environment

- JDK 17 (CI pins Temurin 17.0.21+9; local development should use a current JDK 17 with `javac` and `keytool`, and `JAVA_HOME` must point at a real JDK, not only an Android SDK)
- Android SDK Platform 36
- Android Build Tools 36.0.0 or a compatible version
- The repository Gradle wrapper

### OpenJDK eMudhra advisory (CVE-2026-91403)

This advisory concerns compromised eMudhra intermediate CA keys and Java's
standalone JSSE trust store. OpenJDK tracks the corresponding root-removal work
as JDK-8395690 / CVE-2026-114820. This policy differs from that removal list: it
retains G1/G2 but also covers the TLS/CS G3 roots that anchor the compromised
intermediates; see the G1/G2 note below. None of this changes the Android app's
runtime trust store, its platform-default HTTPS update check, or the release
signing identity.

CI pins Temurin 17.0.21+9 and additionally hardens a private copy of that JDK's
`lib/security/cacerts` before Gradle runs. The wrapper
`scripts/harden-jdk-truststore.sh` copies `cacerts` to `$RUNNER_TEMP`, removes
every certificate whose public-key SPKI SHA-256 matches
`scripts/jdk-truststore/pins.txt`, adds a public canary anchor, and configures
`JAVA_TOOL_OPTIONS` to use the copy. The runner's JDK is never modified.
`--verify-only` proves the hardened copy is the effective JSSE store by finding
the canary through the default `TrustManagerFactory`; `--probe-tls` also checks
that normal public TLS still works.

The 12 unique public SPKI pins cover the 20-certificate fixture corpus: 4
incident trust anchors and 16 intermediate CA certificates for 8 compromised
intermediate keys (some keys appear in more than one certificate).
`scripts/jdk-truststore/pins.txt` also documents the TLS G3 cross-certificate
SHA-256 covered by the same SPKI pin.
`emSign Root CA - G1` and `G2` are intentionally retained: OpenJDK's
`CheckCerts` source labels them as unrelated to the compromised intermediate
keys, and Mozilla retains G1. Removing them would be over-removal and could
break unrelated chains. Oracle's sample `CheckCerts` gate also lists G1/G2 as
bad roots and exits non-zero when they are present, while its root list does
not include the incident TLS/CS roots used by this policy. CI therefore does
not use `CheckCerts` as its gate: on Temurin 17.0.21+9, `CheckCerts` exits
non-zero on the retained G1 root even though the incident pins are absent. The
versioned SPKI pin list, the public-chain fixtures, and the canary are the
authoritative gate.

`AAA Certificate Services` is included because it anchors several of the
compromised cross-signed intermediate chains. Removing it can also break
unrelated chains that terminate at that root; the public TLS probe is the
compatibility canary. If a dependency repository requires an AAA-rooted chain,
report it instead of weakening the pin list.

For local Java/Gradle TLS:

```bash
# Inspect the selected JDK's cacerts read-only (fails if incident anchors remain).
./scripts/with-android-env.sh ./scripts/harden-jdk-truststore.sh --check-only

# Print an eval-safe export that uses a private hardened copy.
truststore_env="$(./scripts/with-android-env.sh ./scripts/harden-jdk-truststore.sh --print-env)" \
  && eval "$truststore_env"
```

`--check-only` exits non-zero when the selected JDK still contains incident
anchors (for example Temurin 17.0.20+8); that is expected and tells you to
harden a copy. `--print-env` creates a private `mktemp` out-store directory and
keeps it so the export remains usable; remove that directory after the shell
session if you do not want it retained.

Follow the [OpenJDK eMudhra guidance](https://openjdk.org/emudhra), Oracle's [mitigation instructions](https://ops.java/emudhra), and the [Adoptium Temurin assessment](https://adoptium.net/news/2026/10/temurin-october-2026-emudhra/) for
vendor-specific updates. Run the tool's tests with:

```bash
./scripts/with-android-env.sh python3 -m unittest tests.test_jdk_truststore -v
```

The tests validate the public incident chains in
`tests/fixtures/emudhra_public_chains.json` with an independent fixed-date PKIX
validator, and include a synthetic normal chain as a positive control.

### Why builds fail with “JAVA_HOME is not set”

Gradle needs a JDK on `PATH` / `JAVA_HOME`. The Android SDK alone is not enough:

- Ignored `local.properties` may set `sdk.dir=...` for the **Android SDK**.
- That does **not** set `JAVA_HOME`. Fresh shells, automation agents, and non-interactive terminals often have neither variable.

Typical fix on a maintainer machine that keeps tools under `$HOME/Android/`:

```bash
export JAVA_HOME="$HOME/Android/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"
```

Or use the repo helper (preferred for agents and one-shot commands):

```bash
# optional once: copy and edit machine paths (gitignored)
cp scripts/env.local.sh.example scripts/env.local.sh

# run any command with env loaded
./scripts/with-android-env.sh ./gradlew --no-daemon --no-configuration-cache test

# or export into the current shell
source scripts/with-android-env.sh
```

Also set `ANDROID_HOME` **or** create an ignored `local.properties` with a valid local `sdk.dir`. Do not commit absolute machine paths, `env.local.sh`, or signing material.

## Build and test

```bash
./scripts/with-android-env.sh ./gradlew --no-daemon --no-configuration-cache \
  test lintDebug lintRelease assembleDebug assembleRelease bundleRelease
```

Unsigned release artifacts are sufficient for ordinary pull requests. Permanent release signing material is maintainer-only and must never be added to a contribution, issue, workflow log, or test fixture. The maintainer-only `:app:verifiedRelease` task fails unless the fixed signing certificate is configured.

Outputs are written below `app/build/outputs/` and are ignored by Git.

### Test infrastructure notes

Host-side unit tests assume **JDK 17**. `app/src/test/.../TestStatics.kt` writes static fields reflectively: plain `Field.set` for non-final companion vars (with fail-fast finality/readback checks), and sun.misc.Unsafe for static-final Kotlin object singletons, which JDK 9+ forbids through `Field.set`. CI pins Temurin 17; if you raise the test toolchain, revisit that helper first. The Unsafe memory-access methods it reflects on are terminally deprecated as of JDK 23 (JEP 471) and trigger default runtime warnings from JDK 24 (JEP 498); once they are removed in a future release, the reflective `getMethod("putObject", ...)` lookup will fail outright, and the supported replacements (`VarHandle`, the Foreign Function & Memory API) require a redesign rather than a drop-in rename.

The build adds the required JPMS opens (`--add-opens java.base/java.lang.reflect=ALL-UNNAMED`, plus defensive `sun.misc` opens — see `app/build.gradle.kts`) to every `Test` task, so local runs and CI need no manual JVM flags.

## Source layout

- `app/src/main/java/io/github/samson910022/pixelifyphotos/` — application and module code
- `app/src/main/resources/META-INF/xposed/` — libxposed module metadata
- `app/src/main/res/` — Android resources and UI translations
- `app/src/test/` — host-side unit tests
- `docs/` — user translations and maintainer documentation
- `site/` — static multilingual product landing for GitHub Pages (no APK hosting)
- `certificates/` — public release certificate only

## Project website (`site/`)

The product landing under `site/` is plain static HTML/CSS (EN, zh-TW, zh-CN, ja). Keep it installer-facing: strong non-affiliation disclaimer, **Google Photos as recommended scope** (not an exclusive hard rule), multi-app risk honesty, and official download links only.

- Do **not** host APK/AAB files under `site/`.
- Do **not** add analytics or third-party trackers.
- Prefer relative asset paths so project Pages base path `/pixelify-google-photos-modern/` works.
- English meaning is authoritative; locale pages should stay aligned with `docs/README.*.md` translations.
- Deploy workflow: `.github/workflows/pages.yml` (prepared for GitHub Actions Pages). Enabling Pages in repository Settings is a separate maintainer step and is not implied by merging the workflow file.
- Local preview tip: serve the repo so the site is mounted at `/pixelify-google-photos-modern/` if you need to validate `404.html` absolute paths.

## Automated review comments

Pull requests and issues may receive advisory comments from the repository AI review bot when `OPENCODE_API_KEY` is configured for GitHub Actions.

| Surface | Trigger | Result |
| --- | --- | --- |
| Pull request | open / reopened / synchronize / ready for review, or `/review` | Multi-agent **code review** (role verdicts + findings + `FINAL_VERDICT`) |
| Pull request | `/explain` | Plain-language PR summary |
| Issue | opened, or `/triage` / `/review` / `/explain` | **Issue investigation** (quality score, missing info, root-cause hypotheses) |

Important distinctions:

- Issue investigation is **not** PR review. It does not emit merge-style verdicts.
- Incomplete or truncated bot drafts are fail-closed into a structured stub instead of a partial comment.
- Issue triage is thread-aware: it reads up to a bounded set of issue comments (currently oldest-first) and avoids re-asking questions already asked.
- Public bot comments do **not** disclose model names or provider routing.
- Screenshots and other media may be OCR'd before analysis.

The bot is advisory only. CI and maintainer review remain required. Maintainer setup, model routing, dry-run, and tests live in [docs/AI_REVIEW_BOT.md](docs/AI_REVIEW_BOT.md). Package-local notes: [github_bot/README.md](github_bot/README.md).

## Pull request expectations

1. Keep changes focused and explain the user-visible effect.
2. Add or update tests for behavior changes.
3. Run the complete build-and-test command above.
4. Keep `applicationId`, namespace, Kotlin package, Xposed entry point, and provider authorities consistent.
5. Update English user-facing text first, then update applicable translations.
6. Do not add generated APK/AAB files, local SDK paths, credentials, signing keys, recovery material, or private infrastructure details.
7. Preserve the upstream MIT copyright and permission notice, plus applicable third-party notices.

## Version and release metadata

When preparing a release, maintainers must update all applicable version surfaces together:

- `app/build.gradle.kts`
- `update_info.json`
- `CHANGELOG.md`
- user-visible release notes in Android string resources, when used

The app's stable signing identity and maintainer-only release procedure are documented in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md). Contributors do not need the release key. Xposed mirror preparation is documented separately in [docs/XPOSED_REPOSITORY.md](docs/XPOSED_REPOSITORY.md). A future public launch must also complete the separate [maintainer publication checklist](docs/PUBLICATION_CHECKLIST.md).

## Code style

- Use Kotlin style consistent with the existing sources.
- Prefer small, reviewable changes over unrelated refactors.
- Treat all data imported from files, intents, network responses, and hooked applications as untrusted.
- Avoid logging personal data or full imported configurations.
- Keep network access limited to documented project functions.

## License

By contributing, you agree that your contribution is licensed under the repository's [MIT License](LICENSE).
