#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need git

clone_pinned "$UEFI_NTFS_REPO" "$UEFI_NTFS_REF" "$PAYLOAD_ROOT/sources/uefi-ntfs"
git -C "$PAYLOAD_ROOT/sources/uefi-ntfs" submodule update --init --recursive

clone_pinned "$WIMLIB_REPO" "$WIMLIB_REF" "$PAYLOAD_ROOT/sources/wimlib"
clone_pinned "$SEVENZIP_JBINDING_REPO" "$SEVENZIP_JBINDING_REF" "$PAYLOAD_ROOT/sources/sevenzipjbinding"
clone_pinned "$SEVENZIP_JBINDING_ANDROID_REPO" "$SEVENZIP_JBINDING_ANDROID_REF" "$PAYLOAD_ROOT/sources/7-Zip-JBinding-4Android"

cat > "$PAYLOAD_ROOT/sources/SOURCE_LOCK_SUMMARY.txt" <<EOF
UEFI_NTFS_REF=$UEFI_NTFS_REF
WIMLIB_REF=$WIMLIB_REF
SEVENZIP_JBINDING_REF=$SEVENZIP_JBINDING_REF
SEVENZIP_JBINDING_ANDROID_REF=$SEVENZIP_JBINDING_ANDROID_REF
EOF

echo "Fetched pinned payload sources under $PAYLOAD_ROOT/sources"
