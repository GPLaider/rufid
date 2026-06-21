# Rufid Implementation Status

Date: 2026-06-21

Target scope: clean implementation of Android boot-media writer workflows, with a full audited payload path for F-Droid.

## Built Artifacts

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Debug SHA-256: `863373d6542248ef6f6449eb86b9e32e1c3351baea6384f5d93735eb8020270d`
- Debug size: `12311462` bytes
- F-Droid payload release candidate: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release SHA-256: `780247f4e6da6efb42b3892a0e97138d6226960cd2658a47896ac804241b111d`
- Release size: `11882897` bytes

Build commands verified:

```powershell
gradle --no-daemon :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

```bash
shellcheck -x -S warning scripts/payloads/*.sh scripts/fdroid/*.sh scripts/wsl/*.sh
```

Build result:

- `BUILD SUCCESSFUL`
- `:app:assembleDebug` passed with staged payloads by default
- `:app:assembleRelease` passed for the unsigned F-Droid payload candidate
- `:app:testDebugUnitTest` passed: 22 tests, 0 failures
- `:app:lintDebug` passed: `No issues found.`
- Debug/release APK content check: staged payload/native entries `18`; `META-INF/version-control-info.textproto` absent.
- WSL Arch `shellcheck` passed for payload, F-Droid, and WSL scripts

## Completed

- Added public documentation set modelled after the completed Ventoid release workflow: README gallery, contributing guide, security policy, F-Droid notes, F-Droid submission tracker, marketing notes, asset provenance, and release notes.
- Added real Samsung Z Flip screenshots to `docs/assets/` and Fastlane phone screenshots.
- Made full payload packaging the default Gradle behavior; `-Prufid.includePayloads=false` is now only a source-only developer opt-out.
- Reworked the UI into a Rufus-like flow: `Drive properties`, `Format options`, `Status`, and `Write`.
- Collapsed secondary actions into a `TOOLS` dialog so the main screen is no longer a button pile.
- Added visible boot source mode selection: `ISO / IMG`, `URL`, and `FreeDOS`.
- Added URL mode with `or select current ISO` below the direct URL field.
- Added seven official OS entries: Windows, Ubuntu, Fedora, Debian, Linux Mint, Arch Linux, and openSUSE.
- Added packaged source-built FreeDOS write path using `assets/payloads/dos/freedos.img`.
- Added read-only boot media inspection for MBR/FAT/FreeDOS evidence.
- Added guarded USB recovery/reinitialize flow: quick metadata wipe, one MBR FAT32 or exFAT partition, format, and read-back metadata verification.
- Added recovery plan confirmation requiring the user to type `R`, with legacy `REINITIALIZE` still accepted.
- Fixed Android 12+ USB permission result delivery by using a mutable permission `PendingIntent`.
- Improved USB inspection output with VID/PID, Android USB device name, raw capacity bytes, partition end LBA/size, FAT32/exFAT filesystem detection, and recovery layout detection.
- Added USB mass-storage scan, chooser, permission request, diagnostics, benchmark, backup, verification, and read-only capacity probe.
- Added direct URL-to-USB streaming writer and `INTERNET` permission.
- Added ZIP extraction to a selected SAF folder.
- Added FAT32 and exFAT layout/formatter cores, MBR/GPT foundations, and Windows ISO helper planning.
- Added local last-error reporting and safe UI action wrappers without external crash SDKs.
- Added deterministic artwork pipeline and Android/Fastlane/repository assets.
- Added F-Droid metadata and source-staged payload scripts for FreeDOS, UEFI:NTFS, wimlib, and 7-Zip-JBinding.
- Added FreeDOS package-source audit and source-build path: the build verifies `65/65` FreeDOS package ZIPs contain `SOURCE/` entries, expands `62` nested `SOURCES.ZIP` archives, builds FreeDOS kernel/FreeCOM/SYS/boot artifacts, and assembles the packaged FAT16 image from those outputs.
- Excluded RAR/unRAR native source files from the 7-Zip-JBinding Android CMake input.

## Payload Status

The current release candidate includes:

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
- `lib/<abi>/libwimutils.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
- `lib/<abi>/lib7-Zip-JBinding.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`

The FreeDOS build also writes non-APK audit files under `payloads/out/source-provenance/freedos/`:

- `FREEDOS_SOURCE_AUDIT.txt`
- `FREEDOS_SOURCE_BUILD.txt`
- `packages-source-manifest.tsv`
- extracted package `SOURCE/` tree

Current staged payload hashes:

- `freedos.img`: `88ea18d67f25214428179530a1c2aa8709a3a02cb7ef26912909781620f02f3d`
- `freedos.7z`: `b51b5f4b462f895e3eb23bc906a18d42cdca9ee76de163ebdee6ef9f8c724c84`
- `uefi-ntfs.img`: `90ef88628ab417801472b1f563aab939afafb74e495f0787511068634f87713e`

## Device Write Test

Wireless-ADB test target:

- Samsung Z Flip `SM-F766N`
- Android `16`, API `36`

Observed USB:

- App detected `USB SanDisk 3.2Gen1`
- VID:PID `1921:21929`
- Capacity `57.3 GiB`
- Block size `512 B`

Read-only boot media inspection result:

- MBR signature: `55 AA`
- Partition 1: `0x04`, bootable, start LBA `63`, `65457` sectors
- Boot sector LBA: `63`
- Boot signature: `55 AA`
- OEM: `FRDOS5.1`
- Volume label: `FD14-LITE`
- File system: `FAT16`
- FreeDOS: likely

Destructive FreeDOS write result:

- Target: `USB SanDisk 3.2Gen1`
- Action: packaged FreeDOS image write from Rufid
- PC-side result: previous USB contents/partition information were replaced
- PC-side result: the drive presented as FreeDOS media
- Rufid read-only reinspection after the write found FAT16/FreeDOS evidence listed above

Real-device exFAT recovery/reinitialize result:

- Target: `USB SanDisk 3.2Gen1`
- Action: quick metadata wipe, one MBR exFAT partition. The 0.1.0 test build derived label `SANDISK 32G` from `USB SanDisk 3.2Gen1`; 0.1.1 fixes this label derivation.
- Post-write verification: MBR, exFAT main boot sector, checksum sector, and backup boot sector matched
- Rufid read-only inspection: partition type `0x07`, start LBA `2048`, OEM `EXFAT`, volume label `SANDISK 32G` on the 0.1.0 test build, file system `exFAT`

No FAT32 recovery format or fake-capacity destructive test was run on the real USB device.

USB recovery/reinitialize status:

- Unit-tested against in-memory block devices.
- Quick wipe clears beginning/end partition and boot metadata only.
- Creates a single MBR FAT32 or exFAT partition starting at LBA `2048`.
- Formats FAT32 or exFAT using pure Kotlin formatters.
- Verifies FAT32 by reading back MBR, boot sector, and FSInfo sector.
- Verifies exFAT by reading back MBR, main boot sector, checksum sector, and backup boot sector.
- Not secure erase and not a full-disk wipe.

Captured documentation screenshots:

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

## Remaining Engineering Steps

- Add Java/native runtime integration for wimlib WIM split.
- Add Java/native runtime integration for 7-Zip-JBinding archive extraction beyond ZIP.
- Run and document real-device FAT32 recovery and additional exFAT recovery/reinitialize on multiple USB drives.
- Add a safe USB filesystem target abstraction so copy/extract can write directly to a formatted USB volume.
- Add checksum verification for direct downloads before/after writing.
- Decide separately whether to enable the Buildroot rescue Linux profile as a bundled workflow.
