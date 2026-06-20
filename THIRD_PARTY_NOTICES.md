# Third Party Notices

Rufid currently contains no copied third-party application source code.

Current build dependencies:

- Android SDK USB Host APIs
- Kotlin standard library, packaged in the debug APK
- JetBrains annotations, packaged through Kotlin runtime metadata
- Android Gradle Plugin
- JUnit 4.13.2, test-only and not packaged in the app APK
- Python Pillow, build-host only for deterministic artwork resizing

Project artwork:

- `artwork/source/rufid-usb-android.png`: user-provided project artwork used to generate launcher, Fastlane, README, and repository preview images.

If code or binaries from GPL-compatible projects are added later, record:

- project name
- upstream URL
- exact version or commit
- license
- local modifications
- corresponding source location

Do not add Rufus, UEFI:NTFS, FreeDOS, wimlib, libusb, 7-Zip/JBinding, or EtchDroid-derived code without updating this file and the build/source distribution process.

Optional payload supply-chain scripts have been added and local payload outputs have been built/staged. F-Droid metadata stages the audited payload set during its build:

- UEFI:NTFS: pinned upstream source build path in `scripts/payloads/build-uefi-ntfs.sh`
- FreeDOS: official FreeDOS 1.4 LiteUSB archive used as verified package/source input; kernel, FreeCOM, SYS, boot sector, and final FAT16 image are staged by `scripts/payloads/build-freedos-from-source.sh`
- wimlib: pinned upstream Android NDK build path in `scripts/payloads/build-wimlib-android.sh`
- 7-Zip-JBinding: pinned upstream Android build staging path in `scripts/payloads/build-sevenzipjbinding-android.sh`; RAR/unRAR native sources are removed from the CMake input before building.

See `PAYLOAD_SUPPLY_CHAIN.md` and `payloads/payloads.lock` before changing payload staging.
