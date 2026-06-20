#requires -RunAsAdministrator
$ErrorActionPreference = "Stop"

Write-Host "Enabling Windows features required by WSL 2..."
Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Windows-Subsystem-Linux -NoRestart
Enable-WindowsOptionalFeature -Online -FeatureName VirtualMachinePlatform -NoRestart

Write-Host ""
Write-Host "WSL Windows features are staged."
Write-Host "Reboot Windows, then run:"
Write-Host "  wsl --set-default-version 2"
Write-Host "  Arch.exe"
Write-Host ""
Write-Host "After Arch starts, run the Rufid build-host bootstrap from inside WSL:"
Write-Host "  cd /mnt/c/path/to/rufid"
Write-Host "  ./scripts/wsl/arch-rufid-buildhost.sh"
