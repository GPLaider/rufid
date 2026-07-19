# Payload Supply Chain

Rufid does not commit generated payload binaries. Release and F-Droid builds generate payloads into `payloads/out/` from pinned upstream inputs.

## Policy

- Do not use payload binaries extracted from any opaque APK.
- Do not commit generated binaries to the source repository.
- Pin every upstream by commit or verified archive hash.
- Package APK payloads from `payloads/out/`.
- Keep F-Droid builds source-first: build payloads from source in the F-Droid build when technically feasible.
- Treat verified official binary distributions as temporary audit inputs, not the preferred F-Droid answer, when a source build path exists.
- See [FDROID_SOURCE_BUILD_POLICY.md](FDROID_SOURCE_BUILD_POLICY.md).

## Current Pins

| Payload | Upstream | Pin | Output |
| --- | --- | --- | --- |
| UEFI:NTFS | `https://github.com/pbatard/uefi-ntfs.git` | `42de94dce5a0be68aab4e410494bb9d986dbcac4` / `v2.8` | `assets/payloads/uefi/uefi-ntfs.img` |
| FreeDOS source-built image | `https://www.ibiblio.org/pub/micro/pc-stuff/freedos/files/distributions/1.4/FD14-LiteUSB.zip` package/source input | input SHA-256 `857dcd2ebf9d3d094320154db5fb5b830acba6fb98f981a95a0ca7ab3350338b`; package source audit `65/65`; nested sources `62`; output SHA-256 `88ea18d67f25214428179530a1c2aa8709a3a02cb7ef26912909781620f02f3d` | `assets/payloads/dos/freedos.img` |
| FreeDOS source-built archive | same as above | output SHA-256 `b51b5f4b462f895e3eb23bc906a18d42cdca9ee76de163ebdee6ef9f8c724c84` | `assets/payloads/dos/freedos.7z` |
| OpenWatcom v2 toolchain | `https://github.com/open-watcom/open-watcom-v2.git` | `ec15f83f62ac025b0be76d34d608f18a6c59da20` / `2026-06-01-Build` | source-built build toolchain for strict FreeDOS build |
| wimlib + Rufid JNI bridge | `https://github.com/ebiggers/wimlib.git` plus `app/src/main/cpp/rufid_wim_jni.c` | `77e0e2b4896e462a7ec21f59d2092ee4df2d94f4` / `v1.14.5` | `jniLibs/<abi>/libwimutils.so`, `jniLibs/<abi>/librufidwim.so` |
| NTFS-3G mkntfs + Rufid stream | `https://github.com/tuxera/ntfs-3g.git` plus `app/src/main/cpp/rufid_ntfs_stream.c` | `d327833ec1d5eb1358b6f2c37139f10a3460944d` / `2026.7.7` | `jniLibs/<abi>/librufidmkntfs.so`, `jniLibs/<abi>/librufidntfsstream.so` (host autogen needs `libgcrypt20-dev` + `libgpg-error-dev` for `AM_PATH_LIBGCRYPT`; configure uses `--disable-crypto`) |
| 7-Zip-JBinding | `https://github.com/borisbrodski/sevenzipjbinding.git` and Android fork | `85d4923741aa85a5c90a6f69c1207c172a49ffa4`, Android `875f38aac441f41e6eb693177e020e97971dca97` | `jniLibs/<abi>/lib7-Zip-JBinding.so` |
| Rufid rescue Linux profile | `https://buildroot.org/downloads/buildroot-2026.05.tar.xz` | SHA-256 `9d2f3af10fcac763a61ff6e41894a033f9ecf9267ba13dd0912eedcd3be2b22a` | `assets/payloads/linux/rufid-rescue-linux.img` |

The authoritative machine-readable pins are in `payloads/payloads.lock`.

## Local Build

```bash
./scripts/wsl/arch-rufid-buildhost.sh
./scripts/wsl/install-android-ndk-r29.sh
./scripts/wsl/install-android-sdk-linux.sh

export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
export ANDROID_API=24

./scripts/payloads/fetch-sources.sh
./scripts/payloads/build-freedos-from-source.sh
./scripts/payloads/build-uefi-ntfs.sh
./scripts/payloads/build-wimlib-android.sh
./scripts/payloads/build-sevenzipjbinding-android.sh
gradle --no-daemon :app:assembleDebug
```

The local build host used Android NDK r29 (`29.0.14206865`) and Android SDK platform/build-tools 35. wimlib is patched during the scripted build for Android Bionic gaps around `glob()` and `futimes`/`lutimes`; 7-Zip-JBinding is patched to build against SDK 35 and the installed NDK r29.

UEFI:NTFS image generation is normalized for reproducibility by pinning FAT metadata and directory-entry timestamps after image creation.

The FreeDOS audit step extracts package ZIPs from the official LiteUSB image, verifies that every package ZIP contains corresponding `SOURCE/` entries, and stages the audit outside the APK at `payloads/out/source-provenance/freedos/`. The current audit checks `65` package ZIPs, all with `SOURCE/` entries, including `62` nested `SOURCES.ZIP` archives.

The strict FreeDOS source path is `scripts/payloads/build-freedos-from-source.sh`. It expands the nested package source archives, builds the FreeDOS kernel, FreeCOM, SYS, and FAT boot sector with pinned OpenWatcom v2 tooling, and assembles a minimal FAT16 boot image from those source-built artifacts. `FD14LITE.img` is used as package/source input only, not as the final payload copied into the APK.

The app exposes a `Payload status` screen so a tester can verify whether a given APK contains the staged payload set.

## Rescue Linux Profile

`scripts/payloads/build-rescue-linux-buildroot.sh` is a pinned Buildroot profile for a tiny x86_64 rescue Linux image. It is not included in the current F-Droid staging script because the app currently handles Linux through official mirror/download entries, and bundling a Linux image should be reviewed separately for boot mode, size, and maintenance cost.

## F-Droid

The prepared F-Droid metadata stages payloads during the `build:` phase, then runs Gradle with payload packaging enabled. This follows F-Droid's metadata model where generated artifacts belong in the build phase.

Current F-Droid payload set:

- included: FreeDOS source-built FAT16 boot image
- included: FreeDOS source-built archive/provenance/package-source audit
- included: UEFI:NTFS
- included: wimlib Android libraries and Rufid JNI bridge
- included: NTFS-3G `mkntfs` and Rufid NTFS stream tool
- included: 7-Zip-JBinding Android libraries

FreeDOS is built from the package sources carried by the official FreeDOS 1.4 LiteUSB archive, verified by SHA-256. Rufid records the exact upstream archive, audits the package-level corresponding source entries carried by the release media, and does not use APK-extracted FreeDOS payloads or copy `FD14LITE.img` as the final packaged image.

7-Zip-JBinding is built with RAR/unRAR native source entries removed from the Android CMake input before compilation.
