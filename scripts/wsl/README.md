# Arch WSL Build Host

This folder keeps the Rufid build host setup outside the app runtime. Generated payload binaries are not committed; release profiles stage them from pinned upstream inputs before Gradle packages them.

## Current setup

ArchWSL is installed through Scoop:

```powershell
scoop bucket add extras
scoop install archwsl
```

If `Arch.exe` fails with `0x8007019e`, WSL is not enabled on Windows yet.

If `Arch.exe` fails with `0x80070002`, the Scoop install likely ran before WSL
was enabled and removed `rootfs.tar.gz` after a failed registration. Reinstall
ArchWSL or extract `rootfs.tar.gz` from Scoop's ArchWSL cache.

## Enable WSL

Run this from an elevated PowerShell and reboot when it finishes:

```powershell
.\scripts\wsl\enable-wsl-features.ps1
```

After reboot:

```powershell
wsl --set-default-version 2
Arch.exe
```

If import or launch reports that the WSL 2 kernel component is missing, install
the Microsoft WSL 2 kernel update MSI, then convert the distro:

```powershell
msiexec /i "$env:USERPROFILE\Downloads\wsl_update_x64.msi"
wsl --set-version Arch 2
```

Until that kernel is installed, Arch can be registered as WSL 1:

```powershell
wsl --import Arch "$env:USERPROFILE\scoop\persist\archwsl\wsl" "$env:USERPROFILE\scoop\persist\archwsl\data\rootfs.tar.gz" --version 1
```

## Bootstrap Arch

Inside Arch WSL:

```bash
cd /mnt/c/path/to/rufid
./scripts/wsl/arch-rufid-buildhost.sh
```

Then install an Android NDK and set `ANDROID_NDK_HOME` before building Android native payloads.

The pinned helper for the current Android NDK stable channel is:

```bash
cd /mnt/c/path/to/rufid
./scripts/wsl/install-android-ndk-r29.sh
export ANDROID_NDK_HOME=/opt/android/android-ndk-r29
export ANDROID_API=24
```

For Android Gradle projects that need Linux SDK build tools:

```bash
./scripts/wsl/install-android-sdk-linux.sh
export ANDROID_HOME=/opt/android/sdk
export ANDROID_SDK_ROOT=/opt/android/sdk
```
