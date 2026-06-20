# Rufid Clean Implementation Scope

## Target

Rufid targets the practical feature surface of Android boot-media writer apps, implemented cleanly and without copying decompiled third-party code, bundled third-party assets, ads, analytics, billing, coins, or closed native helpers.

The goal is not "Rufus parity" as an abstract desktop benchmark. The goal is: every user-facing workflow in the reference unofficial Android APK should have a clean Rufid implementation path, with completed code where feasible and explicit engine boundaries where a reviewed GPL-compatible dependency or native implementation is required.

## Reference-App Observed Workflows

- Bootable USB from image: ISO, IMG, DMG, Raspberry Pi images, Linux images, Windows images
- MS-DOS / FreeDOS bootable USB
- USB format: FAT16, FAT32, exFAT, NTFS, Ext2, Ext3, Ext4
- Partition management: MBR/GPT selection and partition operations
- Windows ISO handling: FAT32 4GB limit, `install.wim` split helper, UEFI constraints
- UEFI:NTFS-style helper path for NTFS/exFAT boot
- Copy files to USB
- Extract archive to USB or external storage
- Backup/restore whole USB device
- Download file directly to USB
- USB benchmark
- USB device info and diagnostics
- Bad/fake drive warning/test behavior

## Implemented Foundation

- Android app shell with explicit non-affiliation notice
- USB host discovery for Bulk-Only Mass Storage devices
- USB target chooser for multi-device cases
- SCSI READ CAPACITY(10/16), READ(10/16), WRITE(10/16), SYNCHRONIZE CACHE(10/16)
- SCSI REQUEST SENSE error reporting and Bulk-Only reset recovery
- raw block-device writer
- raw block-device backup reader
- image-size versus device-size safety validation
- post-write/manual verifier UI and block verifier foundation
- read benchmark foundation
- direct download-to-USB stream writer
- ZIP extraction to a user-selected SAF folder
- pure Kotlin FAT32 and exFAT layout/formatter cores
- read-only first/middle/last capacity sampling probe
- cancellation model for long-running operations
- feature catalog matching the observed Android boot-media writer workflows
- no ads, no analytics, no billing

## Active Next

- partition-table writer with safe dry-run preview
- guarded on-device FAT32/exFAT format actions
- NTFS/ext format engines with explicit license/source plan
- Windows WIM split engine with reviewed source distribution
- 7z/WIM archive extraction with reviewed source distribution; RAR/unRAR support is excluded
- filesystem writer abstraction for copy/extract workflows
- destructive bad/fake drive capacity probe with explicit consent and restoration plan
- optional checksum policy for direct downloads

## Out Of Scope For Clean Start

- copying third-party decompiled code
- bundling third-party app assets
- bundling Rufus `uefi-ntfs.img` without source/license plan
- bundling FreeDOS archives without source/notice plan
- ads, reward ads, coins, subscriptions, or pro gates
- closed-source native filesystem helpers
- using third-party function names, decompiled control flow, UI text, or proprietary assets as implementation material
