#!/usr/bin/env python3
from __future__ import annotations

import struct
import sys
from pathlib import Path


FIXED_FAT_DATE = ((1980 - 1980) << 9) | (1 << 5) | 1
FIXED_FAT_TIME = 0


def u16(data: bytearray, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytearray, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def set_u16(data: bytearray, offset: int, value: int) -> None:
    struct.pack_into("<H", data, offset, value)


def normalize_entry(data: bytearray, offset: int) -> tuple[bool, int]:
    first = data[offset]
    if first == 0x00:
        return False, 0
    if first == 0xE5:
        return True, 0

    attrs = data[offset + 11]
    if attrs == 0x0F:
        return True, 0

    data[offset + 13] = 0
    set_u16(data, offset + 14, FIXED_FAT_TIME)
    set_u16(data, offset + 16, FIXED_FAT_DATE)
    set_u16(data, offset + 18, FIXED_FAT_DATE)
    set_u16(data, offset + 22, FIXED_FAT_TIME)
    set_u16(data, offset + 24, FIXED_FAT_DATE)

    name = data[offset : offset + 11]
    is_dot = name in (b".          ", b"..         ")
    is_dir = bool(attrs & 0x10)
    cluster = (u16(data, offset + 20) << 16) | u16(data, offset + 26)
    if is_dir and not is_dot and cluster >= 2:
        return True, cluster
    return True, 0


def fat16_chain(data: bytearray, fat_offset: int, start_cluster: int) -> list[int]:
    chain: list[int] = []
    seen: set[int] = set()
    cluster = start_cluster
    while 2 <= cluster < 0xFFF8 and cluster not in seen:
        seen.add(cluster)
        chain.append(cluster)
        cluster = u16(data, fat_offset + cluster * 2)
    return chain


def normalize_directory(data: bytearray, offset: int, length: int) -> list[int]:
    subdirs: list[int] = []
    for entry_offset in range(offset, offset + length, 32):
        should_continue, cluster = normalize_entry(data, entry_offset)
        if cluster:
            subdirs.append(cluster)
        if not should_continue:
            break
    return subdirs


def normalize(path: Path) -> None:
    data = bytearray(path.read_bytes())

    bytes_per_sector = u16(data, 11)
    sectors_per_cluster = data[13]
    reserved_sectors = u16(data, 14)
    fat_count = data[16]
    root_entry_count = u16(data, 17)
    sectors_per_fat = u16(data, 22)

    root_dir_sectors = ((root_entry_count * 32) + bytes_per_sector - 1) // bytes_per_sector
    fat_offset = reserved_sectors * bytes_per_sector
    root_dir_offset = (reserved_sectors + fat_count * sectors_per_fat) * bytes_per_sector
    data_offset = (reserved_sectors + fat_count * sectors_per_fat + root_dir_sectors) * bytes_per_sector
    cluster_size = sectors_per_cluster * bytes_per_sector

    pending = normalize_directory(data, root_dir_offset, root_entry_count * 32)
    seen: set[int] = set()
    while pending:
        cluster = pending.pop()
        if cluster in seen:
            continue
        seen.add(cluster)
        for cluster_id in fat16_chain(data, fat_offset, cluster):
            directory_offset = data_offset + (cluster_id - 2) * cluster_size
            pending.extend(normalize_directory(data, directory_offset, cluster_size))

    path.write_bytes(data)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: normalize-fat-timestamps.py <fat-image>")
    normalize(Path(sys.argv[1]))


if __name__ == "__main__":
    main()
