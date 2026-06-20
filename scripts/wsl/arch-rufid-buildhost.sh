#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "Run this script as root, or install sudo first." >&2
  exit 1
fi

if ! command -v pacman >/dev/null 2>&1; then
  echo "This bootstrap expects Arch Linux with pacman." >&2
  exit 1
fi

if grep -qi microsoft /proc/version 2>/dev/null && ! grep -q '^DisableSandbox' /etc/pacman.conf; then
  echo "Disabling pacman Landlock sandbox for WSL compatibility..."
  sed -i 's/^#DisableSandbox/DisableSandbox/' /etc/pacman.conf
fi

echo "Initializing Arch package trust and updating base packages..."
pacman-key --init
pacman-key --populate archlinux
pacman -Sy --needed --noconfirm archlinux-keyring
pacman -Syu --needed --noconfirm

echo "Installing Rufid payload/build dependencies..."
pacman -S --needed --noconfirm \
  base-devel \
  bc \
  bison \
  git \
  curl \
  cpio \
  flex \
  wget \
  ca-certificates \
  unzip \
  zip \
  p7zip \
  mtools \
  dosfstools \
  nasm \
  rsync \
  python \
  python-pillow \
  jdk17-openjdk \
  gradle \
  cmake \
  ninja \
  clang \
  llvm \
  lld \
  mingw-w64-gcc \
  make \
  autoconf \
  automake \
  libtool \
  pkgconf \
  patch \
  file \
  which \
  android-tools \
  shellcheck

if command -v archlinux-java >/dev/null 2>&1; then
  archlinux-java set java-17-openjdk || true
fi

cat <<'MSG'

Arch build host base is ready.

For Android native payloads, install an Android NDK and export it before running payload builds:
  ./scripts/wsl/install-android-ndk-r29.sh
  export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
  export ANDROID_API=24

From the Rufid project root:
  ./scripts/payloads/fetch-sources.sh
  ./scripts/payloads/build-freedos-from-source.sh
  ./scripts/payloads/build-uefi-ntfs.sh
  ./scripts/payloads/build-wimlib-android.sh
  ./scripts/payloads/build-sevenzipjbinding-android.sh

For the F-Droid-style payload staging path:
  ./scripts/fdroid/stage-source-payloads.sh

MSG
