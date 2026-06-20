# F-Droid Preparation

The metadata in `metadata/io.github.rufid.yml` is the upstream copy for the active F-Droid submission.

- Upstream repository: `https://github.com/GPLaider/rufid`
- Active merge request: `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40885`

Rufid's F-Droid path is a payload release path. The recipe stages every current payload inside the F-Droid build:

- FreeDOS LiteUSB from the official FreeDOS 1.4 archive with package-source audit
- UEFI:NTFS from pinned upstream source
- wimlib Android shared libraries from pinned upstream source
- 7-Zip-JBinding Android shared libraries from pinned upstream source with RAR/unRAR native sources excluded

FreeDOS is staged from the official FreeDOS 1.4 LiteUSB archive with SHA-256 verification and package-source audit notes in `PAYLOAD_SUPPLY_CHAIN.md`. It is not extracted from any APK.

Before submission:

1. Keep `commit: v0.1.0` unless F-Droid review requests an exact full commit hash.
2. Confirm the buildserver package list in `metadata/io.github.rufid.yml`.
3. Keep the FreeDOS package-source audit notes current if the upstream FreeDOS distribution changes.
4. Keep the phone screenshots and Fastlane text aligned with the current UI.

Local payload smoke check:

```bash
export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
bash ./scripts/fdroid/stage-source-payloads.sh
../../work/tools/gradle-8.9/bin/gradle :app:assembleRelease
```
