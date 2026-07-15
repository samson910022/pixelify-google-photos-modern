# Privacy Policy

Pixelify Photos is an independently maintained, open-source Xposed module. This policy describes the module application's own behavior; it does not replace the privacy policies of Google Photos, GitHub, an Xposed framework, or the Android system.

## Data collection

Pixelify Photos does not include advertising, analytics, crash-reporting, or telemetry SDKs. The project maintainer does not operate a backend service for the app, and the app does not send module preferences or exported configuration files to the maintainer.

## Network access

The app requests Internet access for its update check and for opening project-related links. An update check downloads public release metadata from the configured GitHub or Xposed Modules Repository location. Those services may receive ordinary connection information, such as the device IP address and request metadata, under their own privacy policies.

Opening a project, support, or release link sends that URL to the browser or other application selected by the user.

## Local data

Module preferences are stored in the application's local storage. Configuration files are created, imported, exported, or shared only after a user action. A receiving application or storage provider selected by the user controls any exported copy.

The module runs inside the scoped Google Photos process through the user's Xposed environment. Google Photos and the Xposed environment may process data independently of Pixelify Photos and remain governed by their respective policies.

## Logs

Diagnostic logs may contain device or runtime details. Review logs before sharing them and remove account identifiers, file paths, or other personal information. Do not post sensitive logs publicly.

## Data removal

Uninstalling Pixelify Photos, or clearing its application storage, removes its locally stored preferences from the normal Android application data area. Exported files and copies shared to other applications must be deleted separately by the user.

## Changes and questions

Material changes to this policy will be recorded in the repository. For privacy questions that do not contain sensitive information, use the repository's GitHub Issues page. Report security-sensitive matters according to [SECURITY.md](SECURITY.md).
