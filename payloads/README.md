# Rufid Payload Pipeline

Rufid does not commit generated binary payloads. Payloads are produced from pinned upstream inputs and staged under `payloads/out/`.

Expected staged outputs:

- `payloads/out/assets/payloads/uefi/uefi-ntfs.img`
- `payloads/out/assets/payloads/dos/freedos.img`
- `payloads/out/assets/payloads/dos/freedos.7z`
- `payloads/out/source-provenance/freedos/FREEDOS_SOURCE_AUDIT.txt`
- `payloads/out/source-provenance/freedos/packages-source-manifest.tsv`
- `payloads/out/jniLibs/<abi>/libwimutils.so`
- `payloads/out/jniLibs/<abi>/librufidwim.so`
- `payloads/out/jniLibs/<abi>/librufidmkntfs.so` (NTFS-3G mkntfs PIE)
- `payloads/out/jniLibs/<abi>/librufidntfsstream.so` (Rufid stream tool + static libntfs-3g)
- `payloads/out/jniLibs/<abi>/lib7-Zip-JBinding.so`

## Build Host

Inside the Arch WSL build host:

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
# Requires ANDROID_NDK_HOME (any NDK with LLVM toolchain; F-Droid uses metadata ndk).
# Fresh ntfs-3g git needs host libgcrypt20-dev + libgpg-error-dev for AM_PATH_LIBGCRYPT during autogen.
./scripts/payloads/build-ntfs3g-android.sh
./scripts/payloads/build-sevenzipjbinding-android.sh
```

Package staged payloads locally:

```bash
gradle --no-daemon :app:assembleDebug
```

Payload packaging is enabled by default. Use `-Prufid.includePayloads=false` only for source-only developer smoke checks.

`build-freedos.sh` remains as a legacy developer audit helper for the official FreeDOS LiteUSB image. It is not the F-Droid staging path.

`build-freedos-from-source.sh` is the F-Droid path. It uses the official LiteUSB archive only to recover the FreeDOS package set and corresponding source archives, expands the nested `SOURCES.ZIP` files, builds the FreeDOS kernel, FreeCOM, SYS, and FAT boot sector with pinned OpenWatcom v2 tooling, and assembles the bootable FAT16 image from those source-built artifacts.

## F-Droid Source-Staged Hashes

- `uefi-ntfs.img`: `72683fa1250eeea772d3399277b434d4e55ba8dd0dc926e52d817e701fc2eb9e`
- `freedos.img`: `88ea18d67f25214428179530a1c2aa8709a3a02cb7ef26912909781620f02f3d`
- `freedos.7z`: `b51b5f4b462f895e3eb23bc906a18d42cdca9ee76de163ebdee6ef9f8c724c84`
- `libwimutils.so` and `librufidwim.so`: generated per ABI by `scripts/payloads/build-wimlib-android.sh`; hash sidecars are written beside each staged library.
- `arm64-v8a/lib7-Zip-JBinding.so`: `2280b9be611546af17fd65dea4e0ac144edaf73ed39d7c5007f536103bd0d406`
- `armeabi-v7a/lib7-Zip-JBinding.so`: `4655970f797364b3f68c20e579a1815eaf6ea1d4a8220d3376fdf3d43a8ece7a`
- `x86/lib7-Zip-JBinding.so`: `d754541ed462c7d30ae8e557ee0a61e53094ab9fbd8e14531ff58e10ac7b992d`
- `x86_64/lib7-Zip-JBinding.so`: `853db8ac8d1d3e008bd985b35b889fa55f0e7533464ce48dcadb1899425fc528`

The current FreeDOS image and archive are assembled from source-built FreeDOS artifacts. The official FreeDOS 1.4 LiteUSB distribution archive is used as the verified package/source input, with the upstream input hash recorded in `payloads/payloads.lock`.

Current FreeDOS package-source audit:

- package ZIPs audited: `65`
- package ZIPs with `SOURCE/` entries: `65`
- nested `SOURCES.ZIP` archives found: `62`
- final image manifest: `payloads/out/source-provenance/freedos/FREEDOS_SOURCE_BUILD.txt`

The 7-Zip-JBinding Android build removes RAR/unRAR native source entries from the CMake input before compiling.
