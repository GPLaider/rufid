#!/usr/bin/env bash
# Generate a small source-built WIM for androidTest only (never packaged into production assets).
# Requires: wimlib-imagex (wimtools) on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="${ROOT}/app/src/androidTest/assets/wim"
WORK="${TMPDIR:-/tmp}/rufid-androidtest-wim-$$"
mkdir -p "${OUT_DIR}" "${WORK}/src/bin"

dd if=/dev/urandom of="${WORK}/src/bin/a.bin" bs=1M count=3 status=none
dd if=/dev/urandom of="${WORK}/src/bin/b.bin" bs=1M count=3 status=none
printf 'Rufid androidTest WIM fixture\n' > "${WORK}/src/README.txt"

wimlib-imagex capture "${WORK}/src" "${WORK}/split-fixture.wim" "RufidAndroidTest" --compress=none
cp "${WORK}/split-fixture.wim" "${OUT_DIR}/split-fixture.wim"
sha256sum "${WORK}/split-fixture.wim" | awk '{print $1}' > "${OUT_DIR}/split-fixture.wim.sha256"

# Host smoke: multi-part SWM with 2 MiB part size (wimsplit unit = megabytes).
mkdir -p "${WORK}/swm"
wimsplit "${WORK}/split-fixture.wim" "${WORK}/swm/install.swm" 2
PARTS="$(find "${WORK}/swm" -maxdepth 1 -type f -name 'install*.swm' | wc -l | tr -d ' ')"
if [[ "${PARTS}" -lt 2 ]]; then
  echo "expected at least 2 SWM parts, got ${PARTS}" >&2
  ls -la "${WORK}/swm" >&2
  exit 1
fi

cat > "${OUT_DIR}/README.txt" <<EOF
AndroidTest-only WIM fixture for native split instrumentation.
Regenerate: scripts/payloads/generate-androidtest-wim-fixture.sh
Host tool used at generation: wimlib-imagex / wimsplit from PATH.
Do not copy into app/src/main assets or production APK payloads.
EOF

echo "Wrote ${OUT_DIR}/split-fixture.wim (${PARTS} host SWM parts with 2 MiB part size)"
rm -rf "${WORK}"
