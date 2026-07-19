# Android USB BOT compatibility

Some UAS-capable NVMe bridges expose UAS on one alternate setting and USB
Bulk-Only Transport (BOT) on another. Android may bind the kernel UAS driver,
while an app still needs to select and initialize the BOT setting explicitly.

## Open sequence

1. Select an interface with class `0x08`, subclass `0x06`, protocol `0x50`, and
   one bulk IN plus one bulk OUT endpoint.
2. Open the device and `claimInterface()` for that interface. **Hard failure** if
   claim returns false.
3. Call `setInterface()` for the selected BOT alternate setting and **inspect the
   return value**:
   - `alternateSetting != 0`: **hard failure** if `setInterface` returns false
     (non-default alt must be selected).
   - `alternateSetting == 0`: **best-effort** if `setInterface` returns false
     after a successful claim of the same interface (default BOT alt already
     claimed). Still must not ignore the boolean without this policy branch.
4. Send BOT reset: `bmRequestType=0x21`, `bRequest=0xff`, `wIndex=interface.id`.
   Zero-length `controlTransfer`: **only negative results fail**; report step and
   `interfaceId`.
5. Clear endpoint halt on both endpoints: `bmRequestType=0x02`,
   `bRequest=0x01`, `wValue=0`, `wIndex=endpoint.address`. Same zero-length rule;
   report step and endpoint address on failure.
6. Run `TEST UNIT READY`, then `READ CAPACITY`.
7. On any initialization failure, release the claimed interface and close the
   `UsbDeviceConnection`.

## Transfer rules

- Require exact 31-byte CBW and 13-byte CSW transfers.
- Validate the CSW signature, tag, **unsigned 32-bit residue**, and status.
- Status `PASSED` with **non-zero residue** is **not** success; fail with
  command name, residue, and CBW `dataLength`.
- On a stall or phase error, perform BOT reset recovery and clear both halts
  (control results checked as above).
- CSW bulk IN failure: clear IN halt and **re-receive the same CSW once**. This
  is completion recovery for the current command, not a new SCSI WRITE.
- **Do not re-issue a SCSI WRITE** after an ambiguous CSW loss: DATA OUT may
  already have completed. Surface unknown completion and require media verify
  before any higher-level retry.
- Flush with `SYNCHRONIZE CACHE(10/16)` before releasing the interface.

## Rufid mapping

`BulkOnlyTransport` uses synchronous `bulkTransfer`, validates CBW/CSW (including
unsigned residue), checks zero-length control results for MASS_STORAGE_RESET and
CLEAR_HALT, re-receives CSW once after IN clear-halt, and does **not** re-issue
WRITE commands.

`UsbScsiBlockDevice.init()` claims hard-fail, applies the `setInterface` policy
above, runs BOT reset + clear-halt, then capacity. Write path chunks transfers
and maps failures to unknown-completion errors without WRITE replay.

Ventoid's real-device investigation and upstream libaums report:
https://github.com/magnusja/libaums/issues/438
