# Rufid 0.1.0

Initial public source release.

## Highlights

- Android USB host mass-storage scan, chooser, permission request, diagnostics, and benchmark.
- Raw ISO/IMG writing to a selected USB target.
- Manual verification against the selected image.
- Direct URL-to-USB streaming.
- Packaged FreeDOS LiteUSB write mode.
- Official ISO picker entries for Windows, Ubuntu, Fedora, Debian, Linux Mint, Arch Linux, and openSUSE.
- Whole-device USB backup to an Android document.
- ZIP extraction to a selected Android folder.
- Read-only boot media inspection for MBR, FAT, FreeDOS, FAT32, and exFAT evidence.
- Guarded USB recovery/reinitialize flow with explicit `R` confirmation.
- FAT32 and exFAT MBR recovery formatters with post-write metadata verification.
- Device-derived FAT/exFAT volume labels, with `USB DRIVE` fallback.
- Payload status screen for packaged FreeDOS, UEFI:NTFS, wimlib, and 7-Zip-JBinding status.
- FreeDOS package-source audit and 7-Zip-JBinding RAR/unRAR native source exclusion in the payload staging path.
- Local last-error report with no network crash upload.
- Light/dark UI with a Rufus-like main workflow.

## Verification

Local Android verification passed:

- `:app:assembleDebug`
- `:app:assembleRelease`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

The unit suite passed 20 tests with 0 failures.

## Device Notes

A packaged FreeDOS LiteUSB write was run from a Samsung Z Flip test device to a `USB SanDisk 3.2Gen1` drive. A PC-side check confirmed the previous USB contents and partition information were replaced and the drive presented as FreeDOS LiteUSB media.

The same SanDisk drive was recovered from FreeDOS media into one MBR exFAT volume from Rufid on the Z Flip. Rufid verified the MBR, exFAT main boot sector, checksum sector, and backup boot sector, then read-only inspection reported `OEM: EXFAT`, volume label `SANDISK 32G`, and file system `exFAT`.

No secure erase or full-disk wipe claim is made.
