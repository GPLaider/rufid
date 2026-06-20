# Security Policy

Rufid writes directly to selected USB mass-storage devices. Security reports should focus on behavior that could affect user drives, data, build trust, or published APK integrity.

## Supported Versions

Only the latest released version is actively maintained.

| Version | Supported |
| --- | --- |
| 0.1.0 | Yes |
| Older releases | No |

F-Droid builds are produced from source and signed by F-Droid. GitHub release artifacts may be signed separately by this project.

## Reporting a Vulnerability

Do not post exploit details, private files, tokens, signing material, or destructive proof-of-concept steps in a public issue.

Use GitHub private vulnerability reporting or the security advisory flow if it is available on this repository. If that flow is unavailable, open a minimal public issue saying a security report is available and include only:

- affected Rufid version
- install source, such as F-Droid, GitHub release, or local build
- Android version and device model
- short impact summary without exploit details

The maintainer will respond with the safest next contact path.

## In Scope

- Unintended writes to a drive other than the selected USB target.
- Bypasses of user confirmation before destructive USB writes.
- USB permission handling bugs that grant access to the wrong device.
- Payload integrity, provenance, or source-build mismatch reports.
- Release-signing mismatch reports.
- Crashes that expose private data in local error reports.

## Out Of Scope

- Data loss after intentionally selecting and confirming a destructive write target.
- Unsupported Android ROM or kernel USB host behavior without a Rufid-side bug.
- Requests to add closed-source binaries, proprietary formatters, ad SDKs, telemetry, or remote crash uploaders.
- Vulnerabilities in upstream operating system ISO images downloaded by the user.
