#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT"

if [[ -z "${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK must point to the F-Droid-provided Android NDK." >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  export ANDROID_NDK_HOME="$ANDROID_NDK"
fi

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_SDK:-}}}" ]]; then
  echo "ANDROID_HOME, ANDROID_SDK_ROOT, or ANDROID_SDK must point to the Android SDK." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_API="${ANDROID_API:-24}"

rm -rf payloads/out payloads/build
mkdir -p payloads/out

bash ./scripts/payloads/fetch-sources.sh
bash ./scripts/payloads/build-freedos-from-source.sh
bash ./scripts/payloads/build-uefi-ntfs.sh
bash ./scripts/payloads/build-wimlib-android.sh
bash ./scripts/payloads/build-ntfs3g-android.sh
bash ./scripts/payloads/build-sevenzipjbinding-android.sh

cat > payloads/out/FDROID_SOURCE_PAYLOADS.txt <<'EOF'
F-Droid payload staging:
- included: FreeDOS source-built minimal FAT16 image
- included: UEFI:NTFS
- included: wimlib
- included: NTFS-3G mkntfs + Rufid stream (source-built, no FUSE)
- included: 7-Zip-JBinding

FreeDOS uses the official FreeDOS 1.4 LiteUSB archive only as the verified
package/source input. The staged payload image is assembled from source-built
FreeDOS kernel, FreeCOM, SYS, and boot sector artifacts using a pinned
source-built OpenWatcom toolchain.

NTFS-3G git has no generated configure; F-Droid sudo must provide
libgcrypt20-dev and libgpg-error-dev so autoreconf can expand AM_PATH_LIBGCRYPT.
Build uses ANDROID_NDK_HOME from the F-Droid environment (metadata ndk field).
EOF

echo "Staged F-Droid payloads under payloads/out"
