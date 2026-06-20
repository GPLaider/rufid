# Contributing to Rufid

Thanks for helping improve Rufid.

Rufid is a small Android utility with a strict goal: write bootable USB media from Android while remaining source-buildable, F-Droid friendly, and clean of copied proprietary app code or opaque payloads.

## Before Opening an Issue

For bugs, include:

- Rufid version and install source.
- Android device model and Android version.
- USB drive model, capacity, and adapter or hub model if known.
- Whether external power was used.
- Boot source used: ISO/IMG, URL, or FreeDOS.
- Exact error text, last error report, or screenshot.
- Steps to reproduce.
- Whether the action was read-only, backup, verify, write, or format.

For compatibility reports, mention whether the written drive booted on a PC and whether BIOS, UEFI, Secure Boot, or Windows install media was involved.

## Pull Requests

Good pull requests are small, focused, and easy to test. Please include:

- what changed
- why it changed
- how it was tested
- any F-Droid or source-build impact
- any payload provenance or license impact

Avoid adding ads, telemetry, account systems, closed-source binaries, prebuilt native tools without source, or network crash reporters. If a feature needs a bundled tool, native library, image, archive, or generated binary asset, explain how it is built from source or staged from a verified upstream distribution.

## Local Checks

Run the normal Android verification before sending a pull request:

```powershell
..\..\work\tools\gradle-8.9\bin\gradle.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

For payload and F-Droid work, run the staging script in a Linux/WSL build host:

```bash
export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
bash ./scripts/fdroid/stage-source-payloads.sh
../../work/tools/gradle-8.9/bin/gradle :app:assembleRelease
```

If `shellcheck` is available:

```bash
shellcheck -x -P scripts/payloads scripts/payloads/*.sh scripts/fdroid/*.sh scripts/wsl/*.sh
```

## Project Constraints

- License: GPL-3.0-or-later.
- No ads, analytics, billing SDKs, or external crash SDKs.
- Payloads must come from pinned source builds or verified upstream distributions.
- F-Droid compatibility is a hard requirement.
- USB write behavior must stay explicit and conservative.
- Clean-room boundaries matter: do not copy decompiled third-party app code, Rufus assets, or APK-extracted binary payloads.
