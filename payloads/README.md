# Rufid Payload Pipeline

Rufid does not commit generated binary payloads. Payloads are produced from pinned upstream sources or official source-available distributions and staged under `payloads/out/`.

Expected staged outputs:

- `payloads/out/assets/payloads/uefi/uefi-ntfs.img`
- `payloads/out/assets/payloads/dos/freedos.img`
- `payloads/out/assets/payloads/dos/freedos.7z`
- `payloads/out/source-provenance/freedos/FREEDOS_SOURCE_AUDIT.txt`
- `payloads/out/source-provenance/freedos/packages-source-manifest.tsv`
- `payloads/out/jniLibs/<abi>/libwimutils.so`
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
./scripts/payloads/build-freedos.sh
./scripts/payloads/build-uefi-ntfs.sh
./scripts/payloads/build-wimlib-android.sh
./scripts/payloads/build-sevenzipjbinding-android.sh
```

Package staged payloads locally:

```bash
../../work/tools/gradle-8.9/bin/gradle :app:assembleDebug
```

Payload packaging is enabled by default. Use `-Prufid.includePayloads=false` only for source-only developer smoke checks.

`build-freedos.sh` verifies the official FreeDOS LiteUSB archive hash, extracts the image, audits the package ZIPs inside the image for corresponding `SOURCE/` entries, and stages the audit outside the APK under `payloads/out/source-provenance/freedos/`.

## F-Droid Source-Staged Hashes

- `uefi-ntfs.img`: `90ef88628ab417801472b1f563aab939afafb74e495f0787511068634f87713e`
- `freedos.img`: `f539d456b792594bc3ca59d4e0f4c23d4f1fee73370c1390b2da245400718d36`
- `freedos.7z`: `cf31fd2c2d4c775c505c312271e48aaa0620dec6a25b5a45785904a049f9228e`
- `arm64-v8a/libwimutils.so`: `17b42b507a277c7f077ba6abb86c100438ca35778e2ccb3c926407f6bacf3759`
- `armeabi-v7a/libwimutils.so`: `cd0c1fb014f79aef4d56790d48d862d180b05e14013d8df297e785aa1dde7275`
- `x86/libwimutils.so`: `da4bbe1e02dba10ee2cea57c6ad151b8443b88149ac17366ccff7c09d7a74433`
- `x86_64/libwimutils.so`: `41b73da667012739a7e2bc99ee7843ce7f85c5412b89ddca58d06c82ab1aaddb`
- `arm64-v8a/lib7-Zip-JBinding.so`: `2280b9be611546af17fd65dea4e0ac144edaf73ed39d7c5007f536103bd0d406`
- `armeabi-v7a/lib7-Zip-JBinding.so`: `4655970f797364b3f68c20e579a1815eaf6ea1d4a8220d3376fdf3d43a8ece7a`
- `x86/lib7-Zip-JBinding.so`: `d754541ed462c7d30ae8e557ee0a61e53094ab9fbd8e14531ff58e10ac7b992d`
- `x86_64/lib7-Zip-JBinding.so`: `853db8ac8d1d3e008bd985b35b889fa55f0e7533464ce48dcadb1899425fc528`

The FreeDOS image and archive are staged from the official FreeDOS 1.4 LiteUSB distribution archive with the upstream input hash recorded in `payloads/payloads.lock`.

Current FreeDOS package-source audit:

- package ZIPs audited: `65`
- package ZIPs with `SOURCE/` entries: `65`
- nested `SOURCES.ZIP` archives found: `62`

The 7-Zip-JBinding Android build removes RAR/unRAR native source entries from the CMake input before compiling.
