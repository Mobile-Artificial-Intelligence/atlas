#!/usr/bin/env python3
"""Copy the header's geography block (zooms, bbox, center) from BASE into
MERGED, in place.

tile-join unions its inputs' extents and can widen the merged archive's
bbox to the whole world (the OpenAddresses archive's own header is
global). That bbox is not cosmetic: the app bounds its tile sweeps with
it (search-indexer's z14 address sweep enumerates the bbox's tile GRID,
probing one directory lookup per cell), so a world bbox turns an
Australia-sized sweep into 268M lookups over the whole planet. The base
archive's Planetiler-written geography is the one the app expects.

The PMTiles v3 header is fixed-size and self-contained — offsets 100-126
hold min_zoom, max_zoom, min/max lon/lat (e7 int32), center_zoom,
center_lon/lat (e7 int32) — so a byte copy cannot disturb the tile
directories or metadata that follow.

Usage: patch-pmtiles-bbox.py BASE MERGED
"""
import os
import struct
import sys

HEADER_SIZE = 127
GEOGRAPHY_OFFSET = 100  # min_zoom .. center_lat, inclusive (27 bytes)


def main():
    if len(sys.argv) != 3:
        sys.exit(f"usage: {os.path.basename(sys.argv[0])} BASE MERGED")
    base_path, merged_path = sys.argv[1], sys.argv[2]

    def read_header(path):
        with open(path, "rb") as f:
            d = f.read(HEADER_SIZE)
        if len(d) < HEADER_SIZE or d[:7] != b"PMTiles" or d[7] != 3:
            sys.exit(f"{path}: not a PMTiles v3 archive")
        return d

    base = read_header(base_path)
    merged = read_header(merged_path)
    if base[100] != merged[100] or base[101] != merged[101]:
        sys.exit(
            f"zoom mismatch: base {base[100]}-{base[101]} vs merged "
            f"{merged[100]}-{merged[101]} — refusing to patch"
        )

    def describe(d):
        min_lon, min_lat, max_lon, max_lat = struct.unpack_from("<iiii", d, 102)
        center_lon, center_lat = struct.unpack_from("<ii", d, 119)
        return (
            f"bbox [{min_lon / 1e7:.6f},{min_lat / 1e7:.6f},"
            f"{max_lon / 1e7:.6f},{max_lat / 1e7:.6f}] "
            f"center z{d[118]} {center_lon / 1e7:.6f},{center_lat / 1e7:.6f}"
        )

    print(f"merged bbox before patch: {describe(merged)}")
    with open(merged_path, "r+b") as f:
        f.seek(GEOGRAPHY_OFFSET)
        f.write(base[GEOGRAPHY_OFFSET:HEADER_SIZE])
    patched = read_header(merged_path)
    if patched[GEOGRAPHY_OFFSET:HEADER_SIZE] != base[GEOGRAPHY_OFFSET:HEADER_SIZE]:
        sys.exit("patch did not stick")
    print(f"patched from base:        {describe(patched)}")


if __name__ == "__main__":
    main()