# Rufid Implementation Status

Date: 2026-07-20

Target scope: clean implementation of Android boot-media writer workflows, with a full audited payload path for F-Droid.

## Built Artifacts

- Signed GitHub release candidate: `Rufid-v0.2.0.apk`
- Release SHA-256: `78e1a0c3892d5e75c203f035ce180ee633bc8e9cf831ac571c4622bbe4d40ee6`
- Release size: `42070820` bytes
- Signing certificate SHA-256: `45529ff4c5b7bc1ece7fe754b81e3bd360de080511ea073e2e7f22c99825ead2`
- APK Signature Scheme v2/v3 verification: passed

Build commands verified:

```powershell
gradle --no-daemon :app:testDebugUnitTest :app:assembleRelease --rerun-tasks
```

```bash
shellcheck -x -S warning scripts/payloads/*.sh scripts/fdroid/*.sh scripts/wsl/*.sh
```

Build result:

- `BUILD SUCCESSFUL`
- `:app:assembleRelease` passed with staged payloads by default
- `:app:testDebugUnitTest` passed: 112 tests, 0 failures
- Release APK installed on the `RufidQA` Android x86_64 AVD and cold-launched successfully
- FAT32, NTFS MBR, and NTFS GPT mode selection was observed in the signed APK
- APK version readback: versionCode `4`, versionName `0.2.0`
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
- `lib/<abi>/libwimutils.so` and `lib/<abi>/librufidwim.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
- `lib/<abi>/lib7-Zip-JBinding.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`

The FreeDOS build also writes non-APK audit files under `payloads/out/source-provenance/freedos/`:

- `FREEDOS_SOURCE_AUDIT.txt`
- `FREEDOS_SOURCE_BUILD.txt`
- `packages-source-manifest.tsv`
- extracted package `SOURCE/` tree

Current staged payload hashes:

- `freedos.img`: `88ea18d67f25214428179530a1c2aa8709a3a02cb7ef26912909781620f02f3d`
- `freedos.7z`: `b51b5f4b462f895e3eb23bc906a18d42cdca9ee76de163ebdee6ef9f8c724c84`
- `uefi-ntfs.img`: `aad87c173b59656e9689b8f30f4302ae5da4500efa541b1b2921d11e7173d53f`

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

- Exercise FAT32/WIM split and NTFS MBR/GPT Windows writers on additional physical Android/USB combinations.
- Validate the signed UEFI:NTFS and Windows boot chain across physical PC Secure Boot firmware before claiming broad compatibility.
- Run and document real-device FAT32 recovery and additional exFAT recovery/reinitialize on multiple USB drives.
- Add a safe USB filesystem target abstraction so copy/extract can write directly to a formatted USB volume.
- Add checksum verification for direct downloads before/after writing.
- Decide separately whether to enable the Buildroot rescue Linux profile as a bundled workflow.

## 0.2.0 Virtual Windows Boot Gate

- Generated FAT32, NTFS MBR, and NTFS GPT disk images inside an Android x86_64 AVD through `WindowsIsoBackendWriter`; no host-side replacement writer was used.
- Used one 8,177,616,896-byte Windows 11 ISO with a 7,252,676,087-byte `install.wim`.
- Verified FAT32 `install.swm`/`install2.swm` splitting and complete ISO tree placement.
- Verified NTFS MBR/GPT full file path, size, and SHA-256 equality, plus GPT primary/backup headers and CRCs.
- Booted all three AVD-produced images to the Windows Setup language screen with QEMU 11.0.2 and OVMF.
- Verified Secure Boot enforcement with an unsigned negative control and a signed UEFI:NTFS/Windows positive control.
- This closes the virtual backend/firmware gate only. Physical Android USB Host/BOT and real PC firmware compatibility remain community test gates.
