#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

need curl
need unzip
need 7z
need mcopy
need mdir
need python3
need make
need mkfs.fat
need git

zip_file="$PAYLOAD_ROOT/downloads/FD14-LiteUSB.zip"
reference_img="$PAYLOAD_ROOT/build/$FREEDOS_LITEUSB_INNER"
package_audit_root="$PAYLOAD_ROOT/build/freedos-package-audit"
source_audit_root="$PAYLOAD_ROOT/out/source-provenance/freedos"
source_build_root="$PAYLOAD_ROOT/build/freedos-source-build"
blocked_report="$source_audit_root/FREEDOS_SOURCE_BUILD_BLOCKED.txt"

if [[ ! -f "$zip_file" ]]; then
  curl -L --fail --output "$zip_file" "$FREEDOS_LITEUSB_URL"
fi
verify_sha256 "$zip_file" "$FREEDOS_LITEUSB_SHA256"

rm -f "$reference_img"
unzip -p "$zip_file" "$FREEDOS_LITEUSB_INNER" > "$reference_img"

partition_offset="$(
  python3 - "$reference_img" <<'PY'
import sys

with open(sys.argv[1], "rb") as fh:
    mbr = fh.read(512)
if len(mbr) != 512 or mbr[510:512] != b"\x55\xaa":
    raise SystemExit("FreeDOS LiteUSB image has no MBR boot signature")
entry = mbr[446:462]
lba_start = int.from_bytes(entry[8:12], "little")
if lba_start <= 0:
    raise SystemExit("FreeDOS LiteUSB image has no first partition LBA")
print(lba_start * 512)
PY
)"

rm -rf "$package_audit_root" "$source_audit_root" "$source_build_root"
mkdir -p "$package_audit_root"
for package_dir in BASE NET TOOLS APPS ARCHIVER DRIVERS PKG-INFO; do
  if mdir -i "$reference_img@@$partition_offset" "::/PACKAGES/$package_dir" >/dev/null 2>&1; then
    mkdir -p "$package_audit_root/$package_dir"
    mcopy -s -o -i "$reference_img@@$partition_offset" "::/PACKAGES/$package_dir/*" "$package_audit_root/$package_dir/"
  fi
done

python3 "$SCRIPT_DIR/audit-freedos-package-sources.py" \
  "$package_audit_root" \
  "$source_audit_root" \
  "$FREEDOS_LITEUSB_URL"

python3 "$SCRIPT_DIR/prepare-freedos-source-build.py" \
  "$source_audit_root" \
  "$source_build_root"

add_openwatcom_to_path() {
  local root="${RUFID_OPENWATCOM_HOME:-$PAYLOAD_ROOT/sources/open-watcom-v2}"
  for candidate in \
    "$root/binl64" \
    "$root/binl" \
    "$root/bin" \
    "$root/rel/binl64" \
    "$root/rel/binl" \
    "$root/rel/bin" \
    "$PAYLOAD_ROOT/out/source-provenance/toolchains/openwatcom/bin"; do
    if [[ -d "$candidate" ]]; then
      PATH="$candidate:$PATH"
    fi
  done
  if [[ -z "${WATCOM:-}" ]]; then
    export WATCOM="$root"
  fi
  if [[ -z "${OWROOT:-}" ]]; then
    export OWROOT="$root"
  fi
  if [[ -z "${WLINK:-}" && -f "$root/build/bwlink.lnk" ]]; then
    export WLINK="@$root/build/bwlink.lnk"
  fi
  export PATH
}

have_openwatcom() {
  command -v wmake >/dev/null 2>&1 &&
    command -v wcc >/dev/null 2>&1 &&
    command -v wcl >/dev/null 2>&1 &&
    command -v wlink >/dev/null 2>&1
}

add_openwatcom_to_path
if ! have_openwatcom; then
  bash "$SCRIPT_DIR/build-openwatcom-toolchain.sh"
  if [[ -f "$PAYLOAD_ROOT/out/source-provenance/toolchains/openwatcom/env.sh" ]]; then
    # shellcheck disable=SC1090
    source "$PAYLOAD_ROOT/out/source-provenance/toolchains/openwatcom/env.sh"
  fi
fi

missing_tools=()
for tool in nasm wmake wcc wcl wlink; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    missing_tools+=("$tool")
  fi
done

if (( ${#missing_tools[@]} > 0 )); then
  mkdir -p "$source_audit_root"
  {
    echo "FreeDOS strict source build blocked"
    echo "Missing tools: ${missing_tools[*]}"
    echo "Required path: source-build OpenWatcom or ia16-elf-gcc plus NASM."
    echo "Reference image use: source extraction only; not accepted as final F-Droid FreeDOS payload."
  } > "$blocked_report"
  echo "FreeDOS strict source build blocked: missing ${missing_tools[*]}" >&2
  echo "See $blocked_report" >&2
  exit 1
fi

toolchain_report_dir="$PAYLOAD_ROOT/out/source-provenance/toolchains/openwatcom"
mkdir -p "$toolchain_report_dir"
{
  echo "OpenWatcom toolchain used for FreeDOS source build"
  echo "Repository: $OPENWATCOM_REPO"
  echo "Ref: $OPENWATCOM_REF"
  echo "Tag: $OPENWATCOM_TAG"
  echo "WATCOM: ${WATCOM:-unset}"
  echo "OWROOT: ${OWROOT:-unset}"
  echo "wmake: $(command -v wmake)"
  echo "wcc: $(command -v wcc)"
  echo "wcl: $(command -v wcl)"
  echo "wlink: $(command -v wlink)"
} > "$toolchain_report_dir/OPENWATCOM_TOOLCHAIN_USED.txt"

kernel_src="$source_build_root/components/BASE__kernel/kernel"
freecom_src="$source_build_root/components/BASE__freecom/freecom"
out_file="$PAYLOAD_ROOT/out/assets/payloads/dos/freedos.7z"
raw_out_file="$PAYLOAD_ROOT/out/assets/payloads/dos/freedos.img"
image_manifest="$source_audit_root/FREEDOS_SOURCE_BUILD.txt"

if [[ ! -f "$kernel_src/makefile" || ! -f "$freecom_src/makefile" ]]; then
  mkdir -p "$source_audit_root"
  {
    echo "FreeDOS strict source build blocked"
    echo "Expanded source layout did not contain expected kernel/freecom makefiles."
    echo "Kernel source: $kernel_src"
    echo "FreeCOM source: $freecom_src"
  } > "$blocked_report"
  echo "FreeDOS strict source build blocked: unexpected source layout" >&2
  exit 1
fi

(
  cd "$kernel_src"
  export BUILDENV=linux
  export COMPILER=owlinux
  export NASM=nasm
  export WATCOM="${WATCOM:-${RUFID_OPENWATCOM_HOME:-}}"
  make XUPX= all
)

(
  cd "$freecom_src"
  export BUILDENV=linux
  export COMPILER=owlinux
  export WATCOM="${WATCOM:-${RUFID_OPENWATCOM_HOME:-}}"
  bash ./build.sh wc
)

kernel_bin=""
for candidate in "$kernel_src/bin/kernel.sys" "$kernel_src/bin/KERNEL.SYS" "$kernel_src/bin/kwc8632.sys" "$kernel_src/bin/kwc8616.sys"; do
  if [[ -f "$candidate" ]]; then
    kernel_bin="$candidate"
    break
  fi
done

command_bin=""
for candidate in "$freecom_src/command.com" "$freecom_src/COMMAND.COM" "$freecom_src/com.com" "$freecom_src/COM.COM"; do
  if [[ -f "$candidate" ]]; then
    command_bin="$candidate"
    break
  fi
done

sys_bin=""
for candidate in "$kernel_src/bin/sys.com" "$kernel_src/sys/sys.com" "$kernel_src/sys/sys.com"; do
  if [[ -f "$candidate" ]]; then
    sys_bin="$candidate"
    break
  fi
done

boot_sector="$kernel_src/boot/fat16com.bin"
if [[ -z "$kernel_bin" || -z "$command_bin" || ! -f "$boot_sector" ]]; then
  mkdir -p "$source_audit_root"
  {
    echo "FreeDOS strict source build blocked"
    echo "Missing source-built artifacts after component build."
    echo "Kernel candidate: ${kernel_bin:-missing}"
    echo "Command candidate: ${command_bin:-missing}"
    echo "Boot sector: $boot_sector"
    echo "SYS candidate: ${sys_bin:-missing}"
  } > "$blocked_report"
  echo "FreeDOS strict source build blocked: missing built artifacts" >&2
  exit 1
fi

mkdir -p "$(dirname "$raw_out_file")"
rm -f "$raw_out_file" "$out_file"
assemble_args=(
  --image "$raw_out_file"
  --boot-sector "$boot_sector"
  --kernel "$kernel_bin"
  --command "$command_bin"
  --manifest "$image_manifest"
)
if [[ -n "$sys_bin" ]]; then
  assemble_args+=(--sys "$sys_bin")
fi
python3 "$SCRIPT_DIR/assemble-freedos-image.py" "${assemble_args[@]}"

write_sha256_sidecar "$raw_out_file"
wc -c < "$raw_out_file" | tr -d ' ' > "$raw_out_file.size"

7z a -t7z -mx=9 "$out_file" "$raw_out_file" >/dev/null
write_sha256_sidecar "$out_file"

cat > "$raw_out_file.source.txt" <<EOF
Source: FreeDOS package sources from $FREEDOS_LITEUSB_URL
Source archive SHA-256: $FREEDOS_LITEUSB_SHA256
Toolchain: OpenWatcom from $OPENWATCOM_REPO at $OPENWATCOM_REF
Build manifest: source-provenance/freedos/FREEDOS_SOURCE_BUILD.txt
Output: payloads/dos/freedos.img
EOF

cat > "$out_file.source.txt" <<EOF
Source: FreeDOS package sources from $FREEDOS_LITEUSB_URL
Source archive SHA-256: $FREEDOS_LITEUSB_SHA256
Toolchain: OpenWatcom from $OPENWATCOM_REPO at $OPENWATCOM_REF
Build manifest: source-provenance/freedos/FREEDOS_SOURCE_BUILD.txt
Output: payloads/dos/freedos.7z
EOF

echo "Built source-derived $raw_out_file"
echo "Built source-derived $out_file"
