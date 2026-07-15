# Release Signing and Verification

## Stable release identity

All official GitHub and Xposed Modules Repository releases must use the same release signing certificate. Losing the private key or its credentials prevents compatible direct APK updates, so signing operations are restricted to the maintainer's trusted release environment.

- Application ID: `io.github.samson910022.pixelifyphotos`
- Public certificate: [`certificates/pixelifyphotos-release-cert.pem`](../certificates/pixelifyphotos-release-cert.pem)
- Certificate SHA-256: recorded in [Public certificate fingerprint](#public-certificate-fingerprint)

## Repository safety rules

Never commit or upload:

- a private keystore or private-key container (`*.p12`, `*.jks`, `*.keystore`, and similar files);
- signing passwords, signing property files, environment dumps, or workflow logs containing credentials; or
- recovery material or private backup records.

The public certificate, its fingerprint, and verification instructions are safe to publish. A public certificate cannot be used to sign a release.

## Maintainer signing configuration

Ordinary contributor and pull-request builds may produce unsigned release artifacts. Official publication must use the repository's fail-closed `verifiedRelease` task and a complete signing configuration supplied outside Git. Run it from the repository root with `./gradlew --no-daemon --no-configuration-cache :app:verifiedRelease`.

The preferred method is an external properties file with owner-only permissions, referenced by `RELEASE_SIGNING_PROPERTIES_FILE`. Direct Gradle properties or environment variables are also supported through:

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Do not combine the external properties-file method with direct properties or environment variables. The ignored repository-root `key.properties` fallback is retained only for compatibility and should not be used for permanent release operations.

## Private key handling and incidents

The private signing key, its credentials, backups, recovery procedures, and incident records are not stored or described in Git. This repository publishes only the public certificate, its fingerprint, and verification policy. If compromise is suspected, publication stops until the maintainer completes a private incident assessment and documents any user-facing signer migration that becomes necessary.

## Release verification procedure

For every official release, the maintainer must:

1. Build through the fail-closed verified release task.
2. Run `./scripts/check-publication-readiness.py --require-artifacts`.
3. Run `./scripts/verify-release-artifacts.py` and confirm both signers use the fingerprint below.
4. Generate artifact SHA-256 checksums and publish them with the release.
5. Download the uploaded artifacts and repeat signer and checksum verification.

Users should reject an APK whose signer fingerprint does not match this document. Artifact checksums identify individual files and change for every release; the certificate fingerprint identifies the stable signer.

## Public certificate fingerprint

<!-- RELEASE_CERT_SHA256_START -->
`37:18:6E:5C:26:94:E5:53:E5:FA:B1:F7:78:7C:04:DB:CD:43:84:AB:84:96:3E:60:BE:9C:3C:CB:6B:A9:07:B1`
<!-- RELEASE_CERT_SHA256_END -->
