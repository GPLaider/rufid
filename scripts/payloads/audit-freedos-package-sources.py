#!/usr/bin/env python3
"""Audit FreeDOS package ZIPs for corresponding source entries."""

from __future__ import annotations

import hashlib
import shutil
import sys
import zipfile
from pathlib import Path, PurePosixPath


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_extract_member(zip_file: zipfile.ZipFile, info: zipfile.ZipInfo, dest: Path) -> None:
    pure = PurePosixPath(info.filename)
    if pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"unsafe zip path: {info.filename}")
    target = dest.joinpath(*pure.parts)
    if info.is_dir():
        target.mkdir(parents=True, exist_ok=True)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with zip_file.open(info) as src, target.open("wb") as out:
        shutil.copyfileobj(src, out)


def main() -> int:
    if len(sys.argv) != 4:
        print(
            "usage: audit-freedos-package-sources.py <package-root> <output-dir> <liteusb-url>",
            file=sys.stderr,
        )
        return 2

    package_root = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    liteusb_url = sys.argv[3]

    package_zips = sorted(package_root.rglob("*.zip"))
    if not package_zips:
        print(f"No FreeDOS package ZIPs found under {package_root}", file=sys.stderr)
        return 1

    source_tree = output_dir / "source-tree"
    if output_dir.exists():
        shutil.rmtree(output_dir)
    source_tree.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[str, str, int, int, int]] = []
    missing: list[str] = []
    source_archives = 0

    for package_zip in package_zips:
        rel = package_zip.relative_to(package_root).as_posix()
        with zipfile.ZipFile(package_zip) as zf:
            infos = zf.infolist()
            source_infos = [
                info
                for info in infos
                if info.filename.upper().startswith("SOURCE/") and not info.is_dir()
            ]
            nested_source_archives = [
                info for info in source_infos if info.filename.upper().endswith("SOURCES.ZIP")
            ]
            if not source_infos:
                missing.append(rel)
            package_dest = source_tree / rel.replace("/", "__").removesuffix(".zip")
            for info in source_infos:
                safe_extract_member(zf, info, package_dest)

        source_archives += len(nested_source_archives)
        rows.append(
            (
                rel,
                sha256_file(package_zip),
                package_zip.stat().st_size,
                len(source_infos),
                len(nested_source_archives),
            )
        )

    manifest = output_dir / "packages-source-manifest.tsv"
    with manifest.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("package\tsha256\tsize_bytes\tsource_files\tnested_source_archives\n")
        for rel, digest, size, source_files, nested in rows:
            fh.write(f"{rel}\t{digest}\t{size}\t{source_files}\t{nested}\n")

    summary = output_dir / "FREEDOS_SOURCE_AUDIT.txt"
    with summary.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("FreeDOS LiteUSB source audit\n")
        fh.write(f"Source distribution: {liteusb_url}\n")
        fh.write(f"Package ZIPs audited: {len(rows)}\n")
        fh.write(f"Package ZIPs with SOURCE/ entries: {len(rows) - len(missing)}\n")
        fh.write(f"Nested SOURCES.ZIP archives found: {source_archives}\n")
        fh.write("Staged source tree: source-tree/\n")
        fh.write("Manifest: packages-source-manifest.tsv\n")
        if missing:
            fh.write("Missing SOURCE/ entries:\n")
            for rel in missing:
                fh.write(f"- {rel}\n")
        else:
            fh.write("Missing SOURCE/ entries: none\n")

    if missing:
        print("FreeDOS packages without SOURCE/ entries:", file=sys.stderr)
        for rel in missing:
            print(f"  {rel}", file=sys.stderr)
        return 1

    print(f"Audited {len(rows)} FreeDOS package ZIPs; all include SOURCE/ entries.")
    print(f"Wrote {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
