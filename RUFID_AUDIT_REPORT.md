# Rufid Audit Report

Date: 2026-06-20

Scope: local/static/build audit plus Samsung Z Flip wireless-ADB testing, including one real FreeDOS write and one real exFAT USB recovery/reinitialize run on a SanDisk USB drive. No FAT32 real-device recovery format or fake-capacity destructive test was run.

## Executive Result

Rufid builds, unit-tests, passes Android lint, passes WSL shellcheck, and now uses a Rufus-like main workflow instead of a scattered action stack. Payload packaging is enabled by default, and the F-Droid release candidate includes a source-built FreeDOS image, UEFI:NTFS, wimlib, and 7-Zip-JBinding from documented upstream-pinned inputs.

No ad SDK, billing SDK, analytics SDK, Firebase SDK, external crash-reporting SDK, copied third-party package marker, copied Rufus asset, or opaque APK-extracted payload marker was found in the inspected APK/runtime dependency scan.

The public documentation set now includes README screenshots, Fastlane phone screenshots, F-Droid notes, submission tracker, security policy, contributing guide, marketing notes, asset provenance, and release notes.

## Verification Commands

```powershell
gradle --no-daemon :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

```bash
shellcheck -x -S warning scripts/payloads/*.sh scripts/fdroid/*.sh scripts/wsl/*.sh
```

## Build Artifacts

- Package: `io.github.rufid`
- Version: `0.1.1` / `versionCode=2`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Debug size: `12311462` bytes
- Debug SHA-256: `863373d6542248ef6f6449eb86b9e32e1c3351baea6384f5d93735eb8020270d`
- F-Droid payload release candidate: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release size: `11882897` bytes
- Release SHA-256: `780247f4e6da6efb42b3892a0e97138d6226960cd2658a47896ac804241b111d`

The release candidate is unsigned, as expected for local/F-Droid handoff.

## Build And Script Results

- `:app:assembleDebug`: passed
- `:app:assembleRelease`: passed
- `:app:testDebugUnitTest`: passed, 22 tests, 0 failures
- `:app:lintDebug`: passed, `No issues found.`
- Debug/release APK content check: staged payload/native entries `18`; `META-INF/version-control-info.textproto` absent.
- WSL Arch `shellcheck`: passed for payload, F-Droid, and WSL scripts

## Source Inventory

- Main Kotlin files: `40`
- Main Kotlin lines: `4507`
- Test Kotlin files: `9`
- Test Kotlin lines: `515`
- Main source packages: `archive`, `core`, `download`, `format`, `partition`, `payload`, `storage`, `usb`, `windows`, and `MainActivity`

## Runtime Dependency Audit

Runtime dependencies remain minimal:

- Kotlin standard library
- JetBrains annotations through Kotlin metadata

No AndroidX, ad SDK, billing SDK, Firebase SDK, analytics SDK, remote crash-reporting SDK, native wrapper dependency, or closed binary helper dependency is present in the app runtime classpath.

## APK Content Audit

The default debug APK and release candidate both contain 18 expected staged payload/native entries. The release candidate contains:

- `assets/payloads/dos/freedos.img`
- `assets/payloads/dos/freedos.img.sha256`
- `assets/payloads/dos/freedos.img.size`
- `assets/payloads/dos/freedos.img.source.txt`
- `assets/payloads/dos/freedos.7z`
- `assets/payloads/dos/freedos.7z.sha256`
- `assets/payloads/dos/freedos.7z.source.txt`
- `assets/payloads/uefi/uefi-ntfs.img`
- `assets/payloads/uefi/uefi-ntfs.img.sha256`
- `assets/payloads/uefi/uefi-ntfs.img.source.txt`
- `lib/arm64-v8a/libwimutils.so`
- `lib/arm64-v8a/lib7-Zip-JBinding.so`
- `lib/armeabi-v7a/libwimutils.so`
- `lib/armeabi-v7a/lib7-Zip-JBinding.so`
- `lib/x86/libwimutils.so`
- `lib/x86/lib7-Zip-JBinding.so`
- `lib/x86_64/libwimutils.so`
- `lib/x86_64/lib7-Zip-JBinding.so`

APK forbidden-string scan result: `NO_HITS` for ad, billing, analytics, Firebase, Crashlytics, and reference-APK package markers.

Source-code scan found no runtime code hits for those categories. Project docs mention ads, analytics, and billing only to document the clean-room boundary.

## Manifest And Permission Audit

Declared permission:

- `android.permission.INTERNET`

Declared required feature:

- `android.hardware.usb.host`

Launcher resources:

- `android:icon="@mipmap/ic_launcher"`
- `android:roundIcon="@mipmap/ic_launcher_round"`

Backup/data transfer:

- `android:allowBackup="false"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`
- `android:fullBackupContent="@xml/backup_rules"`

Debug merged manifest has `android:debuggable="true"` as expected. Release merged manifest has no app-level debuggable flag.

## UI Audit

The main screen is now ordered around the actual write flow:

- `Drive properties`
- `Format options`
- `Status`
- `Write`

The boot source choice is explicit:

- `ISO / IMG`
- `URL`
- `FreeDOS`

Secondary operations are collapsed behind `TOOLS`: backup, USB inspection, FAT32/exFAT USB recovery/reinitialize, benchmark, diagnostics, capacity probe, format preview, Windows ISO helper preview, archive plan, ZIP extraction, payload status, and last error report.

The `TOOLS` flow now includes `Reinitialize USB / FAT32` and `Reinitialize USB / exFAT`. Each opens a clear destructive plan screen with target name, VID/PID, capacity, block size, quick-wipe scope, selected MBR filesystem plan, and post-write verification steps. The user must type `R` before the operation starts; legacy `REINITIALIZE` input is still accepted.

The URL mode shows the direct URL field and an `or select current ISO` button. The official picker has seven entries: Windows, Ubuntu, Fedora, Debian, Linux Mint, Arch Linux, and openSUSE. The checked links returned HTTP 200 on 2026-06-20.

Dark mode is handled by runtime palette selection from `Configuration.UI_MODE_NIGHT_MASK`, night resource themes, and status/navigation bar styling.

Real-device screenshots captured on the Samsung Z Flip test device:

- `docs/assets/rufid-main.png`
- `docs/assets/rufid-url-mode.png`
- `docs/assets/rufid-official-isos.png`
- `docs/assets/rufid-freedos.png`
- `docs/assets/rufid-write-tools.png`
- `docs/assets/rufid-tools-menu.png`
- `docs/assets/rufid-payload-status.png`
- `docs/assets/rufid-wireless-01-updated.png`
- `docs/assets/rufid-wireless-03-permission-granted.png`
- `docs/assets/rufid-wireless-05-exfat-plan.png`
- `docs/assets/rufid-wireless-06-exfat-running.png`
- `docs/assets/rufid-wireless-07-exfat-inspect.png`
- `docs/assets/rufid-wireless-08-type-r-plan.png`
- `docs/assets/rufid-label-plan.png`
- `docs/assets/rufid-label-format-result.png`
- `docs/assets/rufid-label-inspect.png`

## Payload Build Audit

Staged payload hashes:

- `freedos.img`: `88ea18d67f25214428179530a1c2aa8709a3a02cb7ef26912909781620f02f3d`
- `freedos.7z`: `b51b5f4b462f895e3eb23bc906a18d42cdca9ee76de163ebdee6ef9f8c724c84`
- `uefi-ntfs.img`: `90ef88628ab417801472b1f563aab939afafb74e495f0787511068634f87713e`
- `arm64-v8a/libwimutils.so`: `17b42b507a277c7f077ba6abb86c100438ca35778e2ccb3c926407f6bacf3759`
- `armeabi-v7a/libwimutils.so`: `cd0c1fb014f79aef4d56790d48d862d180b05e14013d8df297e785aa1dde7275`
- `x86/libwimutils.so`: `da4bbe1e02dba10ee2cea57c6ad151b8443b88149ac17366ccff7c09d7a74433`
- `x86_64/libwimutils.so`: `41b73da667012739a7e2bc99ee7843ce7f85c5412b89ddca58d06c82ab1aaddb`
- `arm64-v8a/lib7-Zip-JBinding.so`: `2280b9be611546af17fd65dea4e0ac144edaf73ed39d7c5007f536103bd0d406`
- `armeabi-v7a/lib7-Zip-JBinding.so`: `4655970f797364b3f68c20e579a1815eaf6ea1d4a8220d3376fdf3d43a8ece7a`
- `x86/lib7-Zip-JBinding.so`: `d754541ed462c7d30ae8e557ee0a61e53094ab9fbd8e14531ff58e10ac7b992d`
- `x86_64/lib7-Zip-JBinding.so`: `853db8ac8d1d3e008bd985b35b889fa55f0e7533464ce48dcadb1899425fc528`

FreeDOS uses the official FreeDOS 1.4 LiteUSB archive as the verified package/source input. The build extracts FreeDOS package ZIPs from the image and verifies that all `65` packages contain corresponding `SOURCE/` entries; `62` nested `SOURCES.ZIP` archives are recorded in `payloads/out/source-provenance/freedos/packages-source-manifest.tsv`. The staged FAT16 image is assembled from source-built FreeDOS kernel, FreeCOM, SYS, and boot sector artifacts, with a pinned OpenWatcom v2 toolchain recorded in `payloads/out/source-provenance/toolchains/openwatcom/OPENWATCOM_TOOLCHAIN_USED.txt`. UEFI:NTFS is built from pinned upstream source. wimlib and 7-Zip-JBinding are staged as Android NDK r29 native libraries from pinned upstream build paths. 7-Zip-JBinding excludes RAR/unRAR native sources before compilation. No payload is taken from any opaque APK.

## Device Write Test

Wireless-ADB test on Samsung Z Flip `SM-F766N`, Android `16`/API `36`:

- App install and launch: passed
- Dark-mode screenshot capture: passed
- USB scan: detected `USB SanDisk 3.2Gen1`
- USB permission: granted after retry
- Destructive packaged FreeDOS write to the SanDisk drive: passed
- PC-side check after write: previous USB contents/partition information were replaced
- PC-side check after write: drive presented as FreeDOS media
- Read-only boot media inspection:
  - MBR signature `55 AA`
  - Partition 1 `0x04`, bootable, start LBA `63`, `65457` sectors
  - Boot sector LBA `63`
  - Boot signature `55 AA`
  - OEM `FRDOS5.1`
  - Volume label `FD14-LITE`
  - File system `FAT16`
  - FreeDOS `likely`

Wireless-ADB exFAT recovery/reinitialize test on the same SanDisk drive:

- App update to the current local build: passed
- USB permission after Android 12+ mutable permission `PendingIntent` fix: passed
- Destructive recovery plan target: `USB SanDisk 3.2Gen1`, VID:PID `0x0781:0x55A9`, capacity `57.3 GiB`
- Plan: quick metadata wipe, one MBR exFAT partition. The 0.1.0 run derived label `SANDISK 32G` from `USB SanDisk 3.2Gen1`; 0.1.1 fixes this so USB generation strings are not folded into capacity-like label text.
- Post-write verification: MBR, exFAT main boot sector, checksum sector, and backup boot sector matched
- Read-only inspection after recovery:
  - Partition 1 `0x07`, bootable, start LBA `2048`, end LBA `120126719`, `57.3 GiB`
  - Boot sector LBA `2048`
  - Boot signature `55 AA`
  - OEM `EXFAT`
  - Volume label `SANDISK 32G` on the 0.1.0 test build; 0.1.1 label derivation now resolves this device name as `SANDISK`
  - File system `exFAT`
  - Recovery layout `MBR exFAT media detected`

No FAT32 real-device recovery format or fake-capacity destructive test was run.

## USB Recovery/Reinitialize Audit

Implemented scope:

- Quick wipe of partition/boot metadata at the beginning and end of the selected USB device.
- Single MBR partition table with one FAT32 or exFAT partition starting at LBA `2048`.
- Pure Kotlin FAT32 and exFAT metadata initialization.
- exFAT metadata writer covers main and backup boot regions, boot checksum sector, first FAT sector, allocation bitmap, up-case table, and root directory volume label.
- Automatic read-back verification for FAT32: MBR, boot sector, and FSInfo sector.
- Automatic read-back verification for exFAT: MBR, main boot sector, checksum sector, and backup boot sector.
- Guarded plan screen requiring `R` before any destructive operation, with legacy `REINITIALIZE` still accepted.

Explicitly not implemented:

- secure erase
- full-disk wipe
- destructive fake-capacity write test
- Windows ISO helper execution
- new native payloads

Unit test coverage:

- `UsbRecoveryFormatterTest.reinitializesUsbAsSingleFat32MbrPartition`
- `UsbRecoveryFormatterTest.reinitializesUsbAsSingleExFatMbrPartition`
- `UsbRecoveryFormatterTest.rejectsUnknownCapacity`
- `BootMediaInspectorTest.detectsExFatRecoveryMedia`
- `ReinitializeConfirmationTest.acceptsSingleLetterR`
- `ReinitializeConfirmationTest.keepsLegacyFullConfirmationWorking`
- `ReinitializeConfirmationTest.rejectsAmbiguousInput`

## Remaining Risks

- USB permission behavior across more OEM Android variants
- Bulk-Only Transport stall/reset recovery on more real drives
- WRITE(16) behavior on large media
- FAT32 recovery correctness on real USB hardware and exFAT mount checks on Android/Windows/Linux
- Java/native runtime integration for wimlib and 7-Zip-JBinding
- Direct download checksum enforcement
