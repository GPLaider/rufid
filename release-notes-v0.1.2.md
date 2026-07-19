# Rufid 0.1.2

USB write compatibility hotfix.

## Changes

- Treats unsupported final SCSI SYNCHRONIZE CACHE responses as non-fatal after image data has been written.
- Covers Illegal Request responses including invalid command, invalid command field, and invalid parameter-list reports.
- Keeps actual USB data WRITE failures, capacity errors, medium errors, and hardware errors fatal.
- Adds clearer local error reports that distinguish data WRITE failures from final cache-sync failures.

## Scope

No payload, permission, ad, analytics, billing, Firebase, or remote crash SDK changes.
