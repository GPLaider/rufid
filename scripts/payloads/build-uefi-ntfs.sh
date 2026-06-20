#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need git
need make
need dd
need mkfs.vfat
need mcopy
need mmd
need touch

src="$PAYLOAD_ROOT/sources/uefi-ntfs"
out_file="$PAYLOAD_ROOT/out/assets/payloads/uefi/uefi-ntfs.img"
image_size_kib="${RUFID_UEFI_NTFS_IMAGE_KIB:-16384}"

if [[ ! -d "$src/.git" ]]; then
  "$SCRIPT_DIR/fetch-sources.sh"
fi

git -C "$src" checkout --detach "$UEFI_NTFS_REF"
git -C "$src" submodule update --init --recursive
make -C "$src"

bootloader="$(find "$src" -type f \( -iname 'uefi-ntfs*.efi' -o -iname 'bootx64.efi' -o -iname 'boot.efi' \) | head -n 1)"
if [[ -z "$bootloader" ]]; then
  echo "Could not find a built UEFI:NTFS EFI binary under $src" >&2
  exit 1
fi

mkdir -p "$(dirname "$out_file")"
rm -f "$out_file"
dd if=/dev/zero of="$out_file" bs=1024 count="$image_size_kib" status=none

mkfs_args=(-F 16 -n UEFI_NTFS -i 52554649)
if mkfs.vfat --help 2>&1 | grep -q -- '--invariant'; then
  mkfs_args+=(--invariant)
fi
mkfs.vfat "${mkfs_args[@]}" "$out_file" >/dev/null

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
stable_bootloader="$tmp_dir/bootx64.efi"
cp "$bootloader" "$stable_bootloader"
touch -d '@315532800' "$stable_bootloader"

mmd -i "$out_file" ::/efi ::/efi/boot
mcopy -m -i "$out_file" "$stable_bootloader" ::/efi/boot/bootx64.efi

if [[ -n "${RUFID_EFI_DRIVER_DIR:-}" ]]; then
  find "$RUFID_EFI_DRIVER_DIR" -maxdepth 1 -type f -iname '*.efi' -print0 |
    while IFS= read -r -d '' driver; do
      stable_driver="$tmp_dir/$(basename "$driver")"
      cp "$driver" "$stable_driver"
      touch -d '@315532800' "$stable_driver"
      mcopy -m -i "$out_file" "$stable_driver" "::/$(basename "$driver")"
    done
fi

python_bin="${PYTHON:-}"
if [[ -z "$python_bin" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    python_bin=python3
  else
    python_bin=python
  fi
fi
"$python_bin" "$SCRIPT_DIR/normalize-fat-timestamps.py" "$out_file"

write_sha256_sidecar "$out_file"
cat > "$out_file.source.txt" <<EOF
Source: $UEFI_NTFS_REPO
Ref: $UEFI_NTFS_REF
Built EFI: $bootloader
Optional driver dir: ${RUFID_EFI_DRIVER_DIR:-not provided}
Output: payloads/uefi/uefi-ntfs.img
EOF

echo "Built $out_file"
