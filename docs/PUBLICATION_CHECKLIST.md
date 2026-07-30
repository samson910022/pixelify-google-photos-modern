# Maintainer Publication Checklist

This checklist is for a future, separately authorized public launch. Completing repository preparation does not authorize changing repository visibility, creating a release, or submitting to an external repository.

## 1. Resolve repository-history disclosure

- Review every commit's author/committer identity, email metadata, removed files, and messages as information that will become public.
- Review deleted review-harness artifacts and any legacy operational notes still reachable from Git history.
- Inspect all fields of the committed public X.509 certificate and explicitly accept every identity or location field that would become public; changing those fields requires an authorized key rotation, not PEM editing.
- If history rewriting is necessary, obtain explicit approval first, coordinate the new branch and tag references, and verify all rewritten refs before any force-push.
- Repeat secret scanning against the complete rewritten history. Do not use an allowlist to hide a real credential.

## 2. Re-run the technical gate

- Run `./scripts/check-publication-readiness.py`.
- Run the full unsigned contributor gate documented in `CONTRIBUTING.md`.
- Run the maintainer-only `:app:verifiedRelease` task with release signing information supplied outside Git.
- Verify the APK and AAB signatures against the fixed certificate in `docs/RELEASE_SIGNING.md`.
- If the build workspace exposes permissive or emulated file modes, copy the final artifacts to an owner-only directory on a trusted local filesystem and repeat signature and checksum verification there before upload.
- Record SHA-256 checksums for the exact files that will be uploaded, then verify the downloaded assets again after upload.
- Confirm the APK contains the expected Xposed entry point, `staticScope=false`, and recommended `scope.list` still listing Google Photos only.

## 3. Complete Android developer verification

Use the Android Developer Console path intended for apps distributed outside Google Play. Requirements and rollout details can change, so confirm the current official guidance immediately before submission:

- [Android developer verification](https://developer.android.com/developer-verification)
- [Register on Android Developer Console](https://developer.android.com/developer-verification/guides/android-developer-console)
- [Registering Android package names](https://support.google.com/android-developer-console/answer/16640821)

For this project:

- Register package name `io.github.samson910022.pixelifyphotos`.
- Register the fixed SHA-256 signing-certificate fingerprint documented in `docs/RELEASE_SIGNING.md`.
- If the console requests proof of key ownership, generate the challenge APK only in the trusted release environment and sign it with the fixed release key.
- Upload only the requested signed APK or public certificate data. Never upload the keystore, private key, passwords, recovery material, or signing-properties file.
- Keep identity-verification records, account-recovery information, and console receipts outside Git in access-controlled storage.
- Confirm the package status is registered before relying on verification for distribution.

## 4. Harden the GitHub repository

- Confirm the default branch and protect it with required CI and review rules appropriate for the maintainer model.
- Enable private vulnerability reporting and review issue/discussion settings.
- Review repository description, topics, social preview, funding links, contact links, and license detection.
- Confirm Actions permissions are read-only by default and that workflows do not receive a permanent release key.
- Review Dependabot and security-alert settings in the GitHub UI.
- Confirm package publication, webhook, deploy key, environment secret, or installed GitHub App access is no broader than required.
- For GitHub Pages (optional public landing):
  - Source tree is `site/` only; workflow is `.github/workflows/pages.yml`.
  - Confirm the workflow uses least privilege (`contents: read`, `pages: write`, `id-token: write`), path filters, and **no** release-signing secrets.
  - Confirm `site/` does not contain APK/AAB files, private keys, or analytics trackers.
  - Enabling Settings → Pages → GitHub Actions is a deliberate step; until then the `github.io` URL may 404 even if the workflow file is merged.
  - After enabling, run a deploy and inspect the live landing as a logged-out visitor (language switcher, download CTAs, 404 under the project base path).

## 5. Prepare GitHub and Xposed distribution

- Finalize `CHANGELOG.md`; replace `Unreleased` with the actual release date only when publishing.
- Build and verify the GitHub release from the exact public commit and tag.
- Publish checksums and the signer fingerprint with release notes.
- Follow `docs/XPOSED_REPOSITORY.md` for the separate mirror and re-verify its downloaded APK.
- Confirm support, privacy, security, translated README, and project-website links render correctly on the public default branch.
- Confirm the project landing (when Pages is enabled) still points only at official download channels and does not host APKs.

## 6. Separate visibility approval

Changing the source repository from private to public is the last step and requires explicit maintainer approval after every item above is complete. Immediately after the change, inspect the repository as a logged-out visitor and repeat the remote visibility, release, Actions, and secret-exposure checks.

Last reviewed against official Android developer-verification guidance: 2026-07-16.
