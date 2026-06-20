#!/usr/bin/env bash
set -euo pipefail

ndk_tag="${ANDROID_NDK_TAG:-r29}"
ndk_dir_name="android-ndk-$ndk_tag"
ndk_url="${ANDROID_NDK_URL:-https://dl.google.com/android/repository/android-ndk-r29-linux.zip}"
ndk_sha1="${ANDROID_NDK_SHA1:-87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b}"
install_root="${ANDROID_NDK_INSTALL_ROOT:-/opt/android}"
download_dir="${ANDROID_NDK_DOWNLOAD_DIR:-/var/cache/rufid}"
zip_file="$download_dir/$ndk_dir_name-linux.zip"

if [[ "$install_root" == /opt/* && "${EUID}" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "Run as root or set ANDROID_NDK_INSTALL_ROOT to a writable directory." >&2
  exit 1
fi

for tool in curl unzip sha1sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool: $tool" >&2
    exit 1
  fi
done

mkdir -p "$download_dir" "$install_root"
if [[ ! -f "$zip_file" ]]; then
  curl -L --fail --output "$zip_file" "$ndk_url"
fi

echo "$ndk_sha1  $zip_file" | sha1sum -c -

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
unzip -q "$zip_file" -d "$tmp_dir"

rm -rf "${install_root:?}/${ndk_dir_name:?}"
mv "$tmp_dir/$ndk_dir_name" "$install_root/$ndk_dir_name"

profile_file="/etc/profile.d/rufid-android-ndk.sh"
if [[ -w "$(dirname "$profile_file")" ]]; then
  cat > "$profile_file" <<EOF
export ANDROID_NDK_HOME=$install_root/$ndk_dir_name
export ANDROID_API=\${ANDROID_API:-24}
EOF
fi

cat <<EOF
Installed Android NDK:
  $install_root/$ndk_dir_name

Use:
  export ANDROID_NDK_HOME=$install_root/$ndk_dir_name
  export ANDROID_API=\${ANDROID_API:-24}
EOF
