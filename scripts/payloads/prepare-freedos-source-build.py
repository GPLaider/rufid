#!/usr/bin/env python3
"""Prepare FreeDOS package source archives for a strict source build."""

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


def safe_extract(zip_file: zipfile.ZipFile, dest: Path) -> int:
    count = 0
    for info in zip_file.infolist():
        pure = PurePosixPath(info.filename)
        if pure.is_absolute() or ".." in pure.parts:
            raise ValueError(f"unsafe zip path: {info.filename}")
        target = dest.joinpath(*pure.parts)
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with zip_file.open(info) as src, target.open("wb") as out:
            shutil.copyfileobj(src, out)
        count += 1
    return count


def main() -> int:
    if len(sys.argv) != 3:
        print(
            "usage: prepare-freedos-source-build.py <source-audit-dir> <source-build-dir>",
            file=sys.stderr,
        )
        return 2

    audit_dir = Path(sys.argv[1])
    build_dir = Path(sys.argv[2])
    source_tree = audit_dir / "source-tree"
    if not source_tree.is_dir():
        print(f"missing FreeDOS source audit tree: {source_tree}", file=sys.stderr)
        return 1

    if build_dir.exists():
        shutil.rmtree(build_dir)
    components_dir = build_dir / "components"
    components_dir.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[str, str, int, str, int]] = []
    for source_zip in sorted(source_tree.rglob("SOURCES.ZIP")):
        rel = source_zip.relative_to(source_tree)
        component = rel.parts[0]
        nested_parts = rel.parts[2:-1] if len(rel.parts) > 3 else rel.parts[:-1]
        leaf = "_".join(part.lower() for part in nested_parts) or component.lower()
        dest = components_dir / component / leaf
        dest.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(source_zip) as zf:
            extracted = safe_extract(zf, dest)
        rows.append(
            (
                component,
                rel.as_posix(),
                source_zip.stat().st_size,
                sha256_file(source_zip),
                extracted,
            )
        )

    if not rows:
        print(f"no nested FreeDOS SOURCES.ZIP archives under {source_tree}", file=sys.stderr)
        return 1

    manifest = build_dir / "nested-source-manifest.tsv"
    with manifest.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("component\tsource_zip\tsize_bytes\tsha256\textracted_files\n")
        for component, rel, size, digest, extracted in rows:
            fh.write(f"{component}\t{rel}\t{size}\t{digest}\t{extracted}\n")

    plan = build_dir / "FREEDOS_SOURCE_BUILD_PLAN.txt"
    with plan.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("FreeDOS strict source build plan\n")
        fh.write(f"Nested source archives expanded: {len(rows)}\n")
        fh.write("Required 16-bit DOS toolchain: OpenWatcom or ia16-elf-gcc plus NASM.\n")
        fh.write("Initial target components: kernel, FreeCOM, SYS boot installer, FDISK, FORMAT.\n")
        fh.write("Image rule: final freedos.img must be assembled from built artifacts, not copied from FD14LITE.img.\n")
        fh.write("Manifest: nested-source-manifest.tsv\n")

    print(f"Prepared {len(rows)} FreeDOS nested source archives under {components_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
