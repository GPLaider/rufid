#!/usr/bin/env python3
"""Assemble a minimal source-built FreeDOS FAT16 boot image."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


FAT16_BPB_END = 0x3E
FAT_VOLUME_ID = "52554644"
DEFAULT_SOURCE_DATE_EPOCH = "1718841600"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def patch_fat16_boot_sector(image: Path, freedos_boot: Path) -> None:
    boot = bytearray(freedos_boot.read_bytes())
    if len(boot) != 512 or boot[510:512] != b"\x55\xaa":
        raise ValueError(f"FreeDOS boot sector is not a 512-byte boot sector: {freedos_boot}")

    with image.open("r+b") as fh:
        current = bytearray(fh.read(512))
        if len(current) != 512 or current[510:512] != b"\x55\xaa":
            raise ValueError(f"FAT image has no boot signature: {image}")

        merged = bytearray(512)
        merged[0:3] = boot[0:3]
        merged[3:FAT16_BPB_END] = current[3:FAT16_BPB_END]
        merged[FAT16_BPB_END:510] = boot[FAT16_BPB_END:510]
        merged[510:512] = b"\x55\xaa"
        # USB BIOS paths normally boot as hard disk 0x80, even for a superfloppy image.
        merged[0x24] = 0x80

        fh.seek(0)
        fh.write(merged)


def write_text_file(path: Path, text: str) -> None:
    path.write_text(text, encoding="ascii", newline="\n")


def normalize_mtime(path: Path, epoch: int) -> None:
    os.utime(path, (epoch, epoch))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--boot-sector", required=True, type=Path)
    parser.add_argument("--kernel", required=True, type=Path)
    parser.add_argument("--command", required=True, type=Path)
    parser.add_argument("--sys", type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    args = parser.parse_args()

    for path in (args.boot_sector, args.kernel, args.command):
        if not path.is_file():
            raise FileNotFoundError(path)

    args.image.parent.mkdir(parents=True, exist_ok=True)
    if args.image.exists():
        args.image.unlink()

    source_date_epoch = int(os.environ.get("SOURCE_DATE_EPOCH", DEFAULT_SOURCE_DATE_EPOCH))

    run(["mkfs.fat", "-C", "-F", "16", "-i", FAT_VOLUME_ID, "-n", "FREEDOS", str(args.image), "32768"])
    patch_fat16_boot_sector(args.image, args.boot_sector)

    with tempfile.TemporaryDirectory(prefix="rufid-freedos-image-") as staging_dir:
        staging = Path(staging_dir)
        kernel_copy = staging / "KERNEL.SYS"
        command_copy = staging / "COMMAND.COM"
        shutil.copyfile(args.kernel, kernel_copy)
        shutil.copyfile(args.command, command_copy)
        normalize_mtime(kernel_copy, source_date_epoch)
        normalize_mtime(command_copy, source_date_epoch)

        write_text_file(staging / "FDCONFIG.SYS", "SHELL=COMMAND.COM /P\n")
        write_text_file(staging / "AUTOEXEC.BAT", "@ECHO OFF\nVER\n")
        normalize_mtime(staging / "FDCONFIG.SYS", source_date_epoch)
        normalize_mtime(staging / "AUTOEXEC.BAT", source_date_epoch)

        copy_paths = [kernel_copy, command_copy, staging / "FDCONFIG.SYS", staging / "AUTOEXEC.BAT"]
        if args.sys and args.sys.is_file():
            sys_copy = staging / "SYS.COM"
            shutil.copyfile(args.sys, sys_copy)
            normalize_mtime(sys_copy, source_date_epoch)
            copy_paths.append(sys_copy)

        for path in copy_paths:
            run(["mcopy", "-o", "-i", str(args.image), str(path), "::/"])

    run(["mdir", "-i", str(args.image), "::/"])

    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    with args.manifest.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("FreeDOS source-built image manifest\n")
        fh.write("Image type: partitionless FAT16 boot image\n")
        fh.write(f"Boot sector: {args.boot_sector} {sha256_file(args.boot_sector)}\n")
        fh.write(f"Kernel: {args.kernel} {sha256_file(args.kernel)}\n")
        fh.write(f"Command interpreter: {args.command} {sha256_file(args.command)}\n")
        if args.sys and args.sys.is_file():
            fh.write(f"SYS: {args.sys} {sha256_file(args.sys)}\n")
        fh.write(f"Image: {args.image} {sha256_file(args.image)}\n")

    print(f"Assembled source-built FreeDOS image: {args.image}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
