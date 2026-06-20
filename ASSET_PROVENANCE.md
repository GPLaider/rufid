# Asset Provenance

This file tracks non-code assets used by Rufid.

## Project Artwork

- Source file: `artwork/source/rufid-usb-android.png`
- Origin: user-provided project artwork for Rufid.
- Processing: deterministic resize, center crop, and padding only.
- Generator: `scripts/assets/generate_rufid_assets.py`
- Generated outputs:
  - Android launcher icons under `app/src/main/res/mipmap-*`
  - adaptive icon XML resources
  - `fastlane/metadata/android/en-US/images/icon.png`
  - `fastlane/metadata/android/en-US/images/featureGraphic.png`
  - `artwork/generated/readme-logo.png`
  - repository/social preview images under `artwork/generated/`

No redraw, style transfer, or background removal is used for the checked-in generated artwork.

## Screenshots

Screenshots under `docs/assets/` and `fastlane/metadata/android/en-US/images/phoneScreenshots/` were captured from a Samsung Z Flip `SM-F766N` test device on 2026-06-20 using wireless ADB.

Captured screens:

- `rufid-main.png`
- `rufid-url-mode.png`
- `rufid-official-isos.png`
- `rufid-freedos.png`
- `rufid-write-tools.png`
- `rufid-tools-menu.png`
- `rufid-payload-status.png`

The screenshots show the Rufid app UI and Android system chrome. They are not copied from Rufus, Ventoy, EtchDroid, any third-party Android app, or any third-party store listing.

## Payload Assets

Payloads are not committed as generated binaries in the source repository. Release and F-Droid builds stage them under `payloads/out/` from pinned source builds or verified upstream distribution inputs.

See [PAYLOAD_SUPPLY_CHAIN.md](PAYLOAD_SUPPLY_CHAIN.md) for FreeDOS, UEFI:NTFS, wimlib, 7-Zip-JBinding, and rescue Linux profile provenance.

## Third-Party Names

Rufus, Ventoy, EtchDroid, FreeDOS, UEFI:NTFS, wimlib, and 7-Zip-JBinding are named only for compatibility, provenance, comparison, or clean-room boundary documentation. Rufid is not affiliated with those projects unless explicitly stated by upstream.
