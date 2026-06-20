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

zip_file="$PAYLOAD_ROOT/downloads/FD14-LiteUSB.zip"
img_file="$PAYLOAD_ROOT/build/$FREEDOS_LITEUSB_INNER"
out_file="$PAYLOAD_ROOT/out/assets/payloads/dos/freedos.7z"
raw_out_file="$PAYLOAD_ROOT/out/assets/payloads/dos/freedos.img"
package_audit_root="$PAYLOAD_ROOT/build/freedos-package-audit"
source_audit_root="$PAYLOAD_ROOT/out/source-provenance/freedos"

if [[ ! -f "$zip_file" ]]; then
  curl -L --fail --output "$zip_file" "$FREEDOS_LITEUSB_URL"
fi
verify_sha256 "$zip_file" "$FREEDOS_LITEUSB_SHA256"

rm -f "$img_file"
unzip -p "$zip_file" "$FREEDOS_LITEUSB_INNER" > "$img_file"
TZ=UTC touch -t 198001010000.00 "$img_file"

partition_offset="$(
  python3 - "$img_file" <<'PY'
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

rm -rf "$package_audit_root" "$source_audit_root"
mkdir -p "$package_audit_root"
for package_dir in BASE NET TOOLS APPS ARCHIVER DRIVERS PKG-INFO; do
  if mdir -i "$img_file@@$partition_offset" "::/PACKAGES/$package_dir" >/dev/null 2>&1; then
    mkdir -p "$package_audit_root/$package_dir"
    mcopy -s -o -i "$img_file@@$partition_offset" "::/PACKAGES/$package_dir/*" "$package_audit_root/$package_dir/"
  fi
done

python3 "$SCRIPT_DIR/audit-freedos-package-sources.py" \
  "$package_audit_root" \
  "$source_audit_root" \
  "$FREEDOS_LITEUSB_URL"

mkdir -p "$(dirname "$out_file")"
rm -f "$out_file" "$raw_out_file"
cp "$img_file" "$raw_out_file"
write_sha256_sidecar "$raw_out_file"
wc -c < "$raw_out_file" | tr -d ' ' > "$raw_out_file.size"
cat > "$raw_out_file.source.txt" <<EOF
Source: $FREEDOS_LITEUSB_URL
Source SHA-256: $FREEDOS_LITEUSB_SHA256
Inner image: $FREEDOS_LITEUSB_INNER
Package source audit: source-provenance/freedos/FREEDOS_SOURCE_AUDIT.txt
Output: payloads/dos/freedos.img
EOF

7z a -t7z -mx=9 "$out_file" "$img_file" >/dev/null
write_sha256_sidecar "$out_file"

cat > "$out_file.source.txt" <<EOF
Source: $FREEDOS_LITEUSB_URL
Source SHA-256: $FREEDOS_LITEUSB_SHA256
Inner image: $FREEDOS_LITEUSB_INNER
Package source audit: source-provenance/freedos/FREEDOS_SOURCE_AUDIT.txt
Output: payloads/dos/freedos.7z
EOF

echo "Built $raw_out_file"
echo "Built $out_file"
