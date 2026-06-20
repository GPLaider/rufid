#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need curl
need tar
need make
need find
need cp

archive="$PAYLOAD_ROOT/downloads/buildroot-$BUILDROOT_VERSION.tar.xz"
src="$PAYLOAD_ROOT/sources/buildroot-$BUILDROOT_VERSION"
build_dir="$PAYLOAD_ROOT/build/buildroot-rufid-rescue-x86_64"
out_dir="$PAYLOAD_ROOT/out/assets/payloads/linux"
out_file="$out_dir/rufid-rescue-linux.img"

if [[ ! -f "$archive" ]]; then
  curl -L --fail --output "$archive" "$BUILDROOT_URL"
fi
verify_sha256 "$archive" "$BUILDROOT_SHA256"

if [[ ! -d "$src" ]]; then
  mkdir -p "$PAYLOAD_ROOT/sources"
  tar -C "$PAYLOAD_ROOT/sources" -xf "$archive"
fi

rm -rf "$build_dir"
mkdir -p "$build_dir"

if make -C "$src" O="$build_dir" -s list-defconfigs | grep -q '^  pc_x86_64_bios_defconfig$'; then
  make -C "$src" O="$build_dir" pc_x86_64_bios_defconfig
else
  make -C "$src" O="$build_dir" qemu_x86_64_defconfig
fi

cat >> "$build_dir/.config" <<'EOF'
BR2_TARGET_GENERIC_HOSTNAME="rufid-rescue"
BR2_TARGET_GENERIC_ISSUE="Rufid rescue Linux"
BR2_ROOTFS_DEVICE_CREATION_DYNAMIC_EUDEV=y
BR2_TARGET_ROOTFS_EXT2=y
BR2_TARGET_ROOTFS_EXT2_4=y
BR2_TARGET_ROOTFS_EXT2_SIZE="128M"
BR2_PACKAGE_BUSYBOX_SHOW_OTHERS=y
BR2_PACKAGE_E2FSPROGS=y
BR2_PACKAGE_E2FSPROGS_RESIZE2FS=y
BR2_PACKAGE_DOSFSTOOLS=y
BR2_PACKAGE_UTIL_LINUX=y
BR2_PACKAGE_UTIL_LINUX_FDISK=y
BR2_PACKAGE_UTIL_LINUX_LSBLK=y
EOF

make -C "$src" O="$build_dir" olddefconfig
make -C "$src" O="$build_dir"

mkdir -p "$out_dir"
candidate="$(find "$build_dir/images" -maxdepth 1 -type f \( -name 'sdcard.img' -o -name 'disk.img' -o -name 'rootfs.ext2' \) | sort | head -n 1)"
if [[ -z "$candidate" ]]; then
  echo "Buildroot completed but no supported image was found under $build_dir/images" >&2
  exit 1
fi

cp "$candidate" "$out_file"
write_sha256_sidecar "$out_file"
cat > "$out_file.source.txt" <<EOF
Source: $BUILDROOT_URL
Source SHA-256: $BUILDROOT_SHA256
Buildroot version: $BUILDROOT_VERSION
Build directory: payloads/build/buildroot-rufid-rescue-x86_64
Output: payloads/linux/rufid-rescue-linux.img
EOF

echo "Built $out_file"
