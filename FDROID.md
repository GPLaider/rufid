# F-Droid

Rufid is prepared for F-Droid review.

## App Summary

- App ID: `io.github.rufid`
- Module: `app`
- Minimum SDK: 24
- Target SDK: 35
- Build system: Gradle / Kotlin
- Source license: GPL-3.0-or-later
- Upstream repository: `https://github.com/GPLaider/rufid`
- Active merge request: `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40885`

## Privacy And Network

- No analytics SDK.
- No ad SDK.
- No billing SDK.
- No account system.
- No cloud backend.
- No Firebase or remote crash-reporting SDK.
- `android.permission.INTERNET` is used for direct URL-to-USB streaming and official ISO link workflows.
- Last error reporting is local-only and stored in the app-specific files directory.

## Sensitive Behavior

Rufid writes directly to attached USB mass-storage devices. This is the app's primary purpose.

The app:

- enumerates USB mass-storage hardware through Android USB host APIs
- requests access through Android's standard USB permission flow
- writes raw image bytes to the selected USB target
- can reinitialize a selected USB drive as one FAT32 or exFAT MBR volume after an explicit confirmation plan
- can back up a selected USB device to an Android document
- can inspect and benchmark USB media using read-only operations

A real packaged FreeDOS LiteUSB write was run during the current local audit on a `USB SanDisk 3.2Gen1` drive. After the write, a PC-side check confirmed that previous USB contents/partition information were replaced and the drive presented as FreeDOS LiteUSB media. The same drive was then recovered from FreeDOS media into one MBR exFAT volume with device-derived label `SANDISK 32G` from Rufid on the Samsung Z Flip. No FAT32 real-device format or fake-capacity destructive test was run.

The USB recovery/reinitialize flow is described as metadata quick wipe plus FAT32 or exFAT format. It is not secure erase and not a full-disk wipe.

## Payloads

Rufid's F-Droid path is a full payload build path. It stages the current payload set during the F-Droid `build:` phase instead of publishing a deliberately incomplete APK.

Current F-Droid payload set:

- FreeDOS LiteUSB image and archive from the official FreeDOS 1.4 distribution archive with SHA-256 verification and package-source audit.
- UEFI:NTFS from pinned upstream source.
- wimlib Android native libraries from pinned upstream source.
- 7-Zip-JBinding Android native libraries from pinned upstream source with RAR/unRAR native sources excluded.

Payload binaries are generated under `payloads/out/` and then packaged by Gradle. They are not committed to the source repository and are not extracted from any opaque APK.

The FreeDOS staging script audits the official LiteUSB image before packaging it. It extracts the FreeDOS package ZIPs from the release image, verifies that every package ZIP contains corresponding `SOURCE/` entries, and writes `payloads/out/source-provenance/freedos/FREEDOS_SOURCE_AUDIT.txt` plus `packages-source-manifest.tsv`. The current audit checks `65` package ZIPs, all with `SOURCE/` entries, including `62` nested `SOURCES.ZIP` archives.

See [PAYLOAD_SUPPLY_CHAIN.md](PAYLOAD_SUPPLY_CHAIN.md), [payloads/README.md](payloads/README.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Review Notes

- The metadata file is `metadata/io.github.rufid.yml`.
- The current upstream tag is `v0.1.0`.
- The GitLab MR is currently source-submitted; fork pipeline behavior may differ from the final F-Droid buildserver run.
- F-Droid reviewers should verify the staged payload scripts, payload hashes, FreeDOS package-source audit, 7-Zip-JBinding RAR/unRAR exclusion, and absence of opaque APK-derived payloads.

## Local F-Droid Smoke Check

```bash
export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
export ANDROID_API=24
bash ./scripts/fdroid/stage-source-payloads.sh
../../work/tools/gradle-8.9/bin/gradle :app:assembleRelease
```
