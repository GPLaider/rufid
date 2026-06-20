# Submit To F-Droid

Rufid has an active F-Droid submission path.

## Current Status

- App ID: `io.github.rufid`
- Upstream repository: `https://github.com/GPLaider/rufid`
- Upstream tag: `v0.1.0`
- F-Droid metadata in this repo: `metadata/io.github.rufid.yml`
- Active merge request: `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40885`
- Review notes: `FDROID.md`
- Payload provenance: `PAYLOAD_SUPPLY_CHAIN.md`
- Asset provenance: `ASSET_PROVENANCE.md`

## Prepared

- Fastlane metadata under `fastlane/metadata/android/en-US/`.
- Phone screenshots under `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- F-Droid metadata under `metadata/io.github.rufid.yml`.
- Payload staging scripts under `scripts/fdroid/` and `scripts/payloads/`.
- NDK r29 and Linux SDK setup helpers under `scripts/wsl/`.
- Local build/audit report in `RUFID_AUDIT_REPORT.md`.

## Before Reviewer Handoff

1. Keep `commit: v0.1.0` unless F-Droid asks for an exact commit hash.
2. Rebase the fdroiddata branch if GitLab marks the MR as needing rebase.
3. Verify the F-Droid buildserver package list when payload script dependencies change.
4. Keep FreeDOS package-source audit, UEFI:NTFS, wimlib, and 7-Zip-JBinding RAR/unRAR-exclusion provenance current.
5. Leave MR comments only for meaningful updates or reviewer requests.

## Local Preflight

```bash
export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
bash ./scripts/fdroid/stage-source-payloads.sh
../../work/tools/gradle-8.9/bin/gradle :app:assembleRelease
shellcheck -x -P scripts/payloads scripts/payloads/*.sh scripts/fdroid/*.sh scripts/wsl/*.sh
```

Android verification:

```powershell
..\..\work\tools\gradle-8.9\bin\gradle.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

## Useful Official References

- Inclusion checklist: https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_How-To
- Inclusion policy: https://fdroid.gitlab.io/jekyll-fdroid/docs/Inclusion_Policy/
- Metadata reference: https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/
