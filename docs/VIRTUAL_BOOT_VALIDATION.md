# Virtual Windows boot validation

Rufid 0.2.0 was validated through the Android production Windows backend using a regular-file block device inside an Android Studio x86_64 Pixel AVD. The host did not create substitute disk images.

## Input

- Windows ISO size: `8,177,616,896` bytes
- ISO SHA-256: `9f39a222ad4a96bd5bbb18afe7b5eed583dd18622b225dbab478c363c4019642`
- Files/directories: `1,064` / `103`
- `sources/install.wim`: `7,252,676,087` bytes
- `install.wim` SHA-256: `9dd32fdecdee4208d73d04713d7cea525050cbb8dddc51ab62880c57bddc21d3`

## AVD-produced images

| Mode | Bytes | SHA-256 | QEMU/OVMF result |
| --- | ---: | --- | --- |
| FAT32 | 12,884,901,888 | `111d3c31ec6a3b061e9de7bd948386526408b4b4c85463c6fdbee2271184834a` | Windows Setup |
| NTFS MBR | 12,884,901,888 | `64109c92f07f5eccd23087ae67d2425f515a1d64f10b271cafc76d1ef34ad943` | UEFI:NTFS, Windows Setup |
| NTFS GPT | 12,884,901,888 | `c9b5282a38fd9b7fff6e0f57d29288cc62d46d1368611cd0b2050d28134ca737` | UEFI:NTFS, Windows Setup |

FAT32 replaced `install.wim` with two verified files:

- `install.swm`: `3,363,524,985` bytes, SHA-256 `4d0c9c4726c04fc796bd26db7f372355df1a196cfb09c8e5e340768220c155c0`
- `install2.swm`: `3,853,460,828` bytes, SHA-256 `99da2585782546a4765b1be8f97c6948ea97e55fc62557a9d49c7fc77416ea11`

The NTFS images matched all `1,064` source files by path, size, and SHA-256. GPT primary/backup headers, partition arrays, boundaries, and CRCs were read back independently.

## Secure Boot control

Microsoft-key OVMF variables reported Secure Boot enabled. A known unsigned EFI payload ran with Secure Boot disabled and was rejected with `Access Denied` / `Security Violation` when enforcement was enabled. Under the same Secure Boot configuration, the signed UEFI:NTFS and Windows boot chain reached Windows Setup.

## Boundary

This validation proves the Android production backend, generated filesystem layouts, QEMU USB mass-storage presentation, OVMF boot path, and virtual Secure Boot enforcement. It does not prove physical Android USB Host/BOT behavior, flash-drive compatibility, power stability, or compatibility with real PC firmware.
