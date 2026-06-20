#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need git

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android/sdk}}"
if [[ ! -d "$sdk_root/platforms/android-35" || ! -x "$sdk_root/build-tools/35.0.0/aapt" ]]; then
  echo "Android Linux SDK platform/build-tools missing under $sdk_root." >&2
  echo "Run scripts/wsl/install-android-sdk-linux.sh first, or set ANDROID_HOME." >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME must point to an installed Android NDK." >&2
  exit 1
fi

ndk_source_properties="$ANDROID_NDK_HOME/source.properties"
if [[ ! -f "$ndk_source_properties" ]]; then
  echo "Missing NDK source.properties under ANDROID_NDK_HOME: $ANDROID_NDK_HOME" >&2
  exit 1
fi
ndk_version="$(sed -n 's/^Pkg.Revision = //p' "$ndk_source_properties" | head -n 1)"
if [[ -z "$ndk_version" ]]; then
  echo "Could not determine Android NDK version from $ndk_source_properties" >&2
  exit 1
fi

src="$PAYLOAD_ROOT/sources/7-Zip-JBinding-4Android"
if [[ ! -d "$src/.git" ]]; then
  "$SCRIPT_DIR/fetch-sources.sh"
fi

git -C "$src" checkout --detach "$SEVENZIP_JBINDING_ANDROID_REF"

cat > "$src/local.properties" <<EOF
sdk.dir=$sdk_root
ndk.dir=$ANDROID_NDK_HOME
EOF

gradle_file="$src/sevenzipjbinding/build.gradle"
sed -i 's/compileSdkVersion 36/compileSdkVersion 35/' "$gradle_file"
sed -i 's/targetSdkVersion 36/targetSdkVersion 35/' "$gradle_file"
if grep -q 'ndkVersion "' "$gradle_file"; then
  sed -i "s/ndkVersion \".*\"/ndkVersion \"$ndk_version\"/" "$gradle_file"
else
  tmp_gradle="$gradle_file.tmp"
  awk -v ndk_version="$ndk_version" '
    /^android \{/ && !inserted {
      print
      print "    ndkVersion \"" ndk_version "\""
      inserted = 1
      next
    }
    { print }
  ' "$gradle_file" > "$tmp_gradle"
  mv "$tmp_gradle" "$gradle_file"
fi

cmake_file="$src/sevenzipjbinding/CMakeLists.txt"
perl -0pi -e '
  s/^\s*\$\{SEVEN_ZIP_SRC\}\/CPP\/7zip\/Archive\/Rar\/Rar(?:5)?Handler\.cpp\r?\n//mg;
  s/^\s*\$\{SEVEN_ZIP_SRC\}\/CPP\/7zip\/Compress\/Rar(?:1Decoder|2Decoder|3Decoder|3Vm|5Decoder|CodecsRegister)\.cpp\r?\n//mg;
  s/^\s*\$\{SEVEN_ZIP_SRC\}\/CPP\/7zip\/Crypto\/Rar(?:20Crypto|5Aes|Aes)\.cpp\r?\n//mg;
' "$cmake_file"
if grep -Eq '/(Archive/Rar|Compress/Rar|Crypto/Rar)' "$cmake_file"; then
  echo "7-Zip-JBinding CMakeLists.txt still references RAR/unRAR native sources." >&2
  exit 1
fi

export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"

if [[ -x "$src/gradlew" ]]; then
  (cd "$src" && ./gradlew :sevenzipjbinding:assembleRelease)
elif [[ -f "$src/build.gradle" || -f "$src/build.gradle.kts" ]]; then
  (cd "$src" && gradle :sevenzipjbinding:assembleRelease)
else
  echo "No Gradle build found for $src. Inspect upstream build files and update this script." >&2
  exit 1
fi

found=0
while IFS= read -r -d '' lib; do
  case "$lib" in
    */arm64-v8a/*) abi=arm64-v8a ;;
    */armeabi-v7a/*) abi=armeabi-v7a ;;
    */x86/*) abi=x86 ;;
    */x86_64/*) abi=x86_64 ;;
    *) continue ;;
  esac
  out_dir="$PAYLOAD_ROOT/out/jniLibs/$abi"
  mkdir -p "$out_dir"
  cp "$lib" "$out_dir/lib7-Zip-JBinding.so"
  write_sha256_sidecar "$out_dir/lib7-Zip-JBinding.so"
  found=1
done < <(find "$src/sevenzipjbinding/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib" \
  -type f \( -name 'lib7-Zip-JBinding.so' -o -name 'libsevenzipjbinding.so' \) -print0)

if [[ "$found" != "1" ]]; then
  echo "Build completed but no Android 7-Zip-JBinding native libraries were found." >&2
  exit 1
fi

cat > "$PAYLOAD_ROOT/out/jniLibs/sevenzipjbinding.source.txt" <<EOF
Source: $SEVENZIP_JBINDING_ANDROID_REPO
Ref: $SEVENZIP_JBINDING_ANDROID_REF
Upstream Java/native source reference: $SEVENZIP_JBINDING_REPO @ $SEVENZIP_JBINDING_REF
RAR/unRAR native sources: excluded from CMake before build
Output: jniLibs/<abi>/lib7-Zip-JBinding.so
EOF

echo "Built/staged Android 7-Zip-JBinding libraries under $PAYLOAD_ROOT/out/jniLibs"
