# Contributing to Pixelify Infinity

Thank you for helping improve Pixelify Infinity. This repository is an independently maintained derivative project; changes must preserve its independent package and signing identity.

## Before opening an issue

- Use the latest available release.
- Confirm the module is enabled and scoped only to Google Photos.
- Search existing issues and release notes.
- Remove account identifiers, device serials, tokens, and other personal data from logs or exported configuration.
- Follow [SECURITY.md](SECURITY.md) for vulnerabilities instead of opening a public issue.

## Development environment

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0 or a compatible version
- The repository Gradle wrapper

Set `ANDROID_HOME` or create an ignored `local.properties` with a valid local `sdk.dir`.

## Build and test

```bash
./gradlew --no-daemon --no-configuration-cache \
  test lintDebug lintRelease assembleDebug assembleRelease bundleRelease
```

Unsigned release artifacts are sufficient for ordinary pull requests. Permanent release signing material is maintainer-only and must never be added to a contribution, issue, workflow log, or test fixture. The maintainer-only `:app:verifiedRelease` task fails unless the fixed signing certificate is configured.

Outputs are written below `app/build/outputs/` and are ignored by Git.

## Source layout

- `app/src/main/java/io/github/samson910022/pixelifyphotos/` — application and module code
- `app/src/main/resources/META-INF/xposed/` — libxposed module metadata
- `app/src/main/res/` — Android resources and UI translations
- `app/src/test/` — host-side unit tests
- `docs/` — user translations and maintainer documentation
- `certificates/` — public release certificate only

## Automated review comments

Pull requests and issues may receive advisory comments from the repository AI review bot when `OPENCODE_API_KEY` is configured for GitHub Actions.

- PR open/update: multi-agent review comment
- Issue open: triage comment
- Slash commands in comments: `/review`, `/triage`, `/explain`

The bot is advisory only. CI and maintainer review remain required. Setup details live in [docs/AI_REVIEW_BOT.md](docs/AI_REVIEW_BOT.md).

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
