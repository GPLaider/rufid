#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PAYLOAD_ROOT="$PROJECT_ROOT/payloads"
LOCK_FILE="$PAYLOAD_ROOT/payloads.lock"

if [[ ! -f "$LOCK_FILE" ]]; then
  echo "Missing lock file: $LOCK_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$LOCK_FILE"

mkdir -p "$PAYLOAD_ROOT/sources" "$PAYLOAD_ROOT/downloads" "$PAYLOAD_ROOT/build" "$PAYLOAD_ROOT/out"

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

clone_pinned() {
  local repo="$1"
  local ref="$2"
  local dir="$3"
  if [[ ! -d "$dir/.git" ]]; then
    git clone "$repo" "$dir"
  fi
  git -C "$dir" fetch --tags --force origin
  git -C "$dir" checkout --detach "$ref"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256_file "$file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $file" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 1
  fi
}

write_sha256_sidecar() {
  local file="$1"
  sha256_file "$file" > "$file.sha256"
}
