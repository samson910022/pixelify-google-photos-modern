# Security Policy

## Supported versions

Security fixes are provided for the latest published release. Users should reproduce an issue on the latest release before reporting it when doing so is safe.

## Reporting a vulnerability

Do **not** disclose a suspected vulnerability, exploit, private log, credential, or signing-related incident in a public GitHub issue or discussion.

Use GitHub's private vulnerability reporting entry under the repository's **Security** tab when it is available. If that private form is unavailable, contact the maintainer through a private contact method listed on the maintainer's GitHub profile. If no private channel is available, open a public issue containing only a request for private contact and no vulnerability details.

Include, when applicable:

- the affected version and Android/Xposed environment;
- a concise description of the impact;
- reproducible steps or a minimal proof of concept;
- whether the issue is already public or is being actively exploited; and
- suggested mitigations, without including unrelated personal information.

The maintainer will acknowledge a private report when practicable, validate its impact, coordinate a fix and disclosure timeline, and credit the reporter if requested. Please allow a reasonable remediation period before public disclosure.

## Release authenticity

Official APK releases must be signed by the stable certificate documented in [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md). A mismatched signer fingerprint or suspicious release asset should be treated as a security issue and reported privately.

Private signing keys, keystores, passwords, recovery material, and local signing configuration must never be committed to this repository or attached to an issue.
