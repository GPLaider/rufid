#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_SDK_ROOT:-/opt/android/sdk}"
download_dir="${ANDROID_SDK_DOWNLOAD_DIR:-/var/cache/rufid}"
tools_rev="${ANDROID_CMDLINE_TOOLS_REV:-14742923}"
tools_url="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-${tools_rev}_latest.zip}"
tools_sha1="${ANDROID_CMDLINE_TOOLS_SHA1:-48833c34b761c10cb20bcd16582129395d121b27}"
zip_file="$download_dir/commandlinetools-linux-${tools_rev}_latest.zip"

if [[ "$sdk_root" == /opt/* && "${EUID}" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "Run as root or set ANDROID_SDK_ROOT to a writable directory." >&2
  exit 1
fi

for tool in curl unzip sha1sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool: $tool" >&2
    exit 1
  fi
done

mkdir -p "$download_dir" "$sdk_root/cmdline-tools"
if [[ ! -f "$zip_file" ]]; then
  curl -L --fail --output "$zip_file" "$tools_url"
fi

echo "$tools_sha1  $zip_file" | sha1sum -c -

rm -rf "$sdk_root/cmdline-tools/latest" "$sdk_root/cmdline-tools/cmdline-tools"
unzip -q "$zip_file" -d "$sdk_root/cmdline-tools"
mv "$sdk_root/cmdline-tools/cmdline-tools" "$sdk_root/cmdline-tools/latest"

set +o pipefail
yes | "$sdk_root/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk_root" --licenses >/tmp/rufid-sdk-licenses.log
set -o pipefail
"$sdk_root/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk_root" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0"

profile_file="/etc/profile.d/rufid-android-sdk.sh"
if [[ -w "$(dirname "$profile_file")" ]]; then
  cat > "$profile_file" <<EOF
export ANDROID_HOME=$sdk_root
export ANDROID_SDK_ROOT=$sdk_root
export PATH=\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH
EOF
fi

cat <<EOF
Installed Android SDK:
  $sdk_root

Use:
  export ANDROID_HOME=$sdk_root
  export ANDROID_SDK_ROOT=$sdk_root
EOF
