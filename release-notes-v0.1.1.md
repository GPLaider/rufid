# Rufid 0.1.1

Hotfix release for USB recovery volume labels.

## Changes

- Fixed recovery volume-label derivation from USB 3.x device names.
- Prevented labels such as `SANDISK 32G` from being created from `SanDisk 3.2Gen1`.
- Kept USB capacity reporting separate from volume-label sanitization.

## Scope

No payload, build-system, permission, ad, analytics, billing, Firebase, or remote crash SDK changes.
