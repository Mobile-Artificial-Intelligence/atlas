#!/usr/bin/env python3
"""Sanity-check a built atlas PMTiles archive.

Validates the PMTiles v3 header (magic/version, zooms 0-14) and prints the
archive summary to the log and $GITHUB_STEP_SUMMARY.

With --address-at "LON,LAT", additionally walks the tile directory to that
z14 tile and asserts the merged OpenAddresses `address` layer is inside it
(a layer named `address` carrying `number`/`street` property keys, and no
bulk properties — those are dropped in the merge's clip pass).

The header/directory/Hilbert decoding mirrors the app's own parsers in
lib/pmtiles (pmtiles-header.kt, pmtiles-directory.kt, hilbert-tile-id.kt);
this script must stay in sync with them.
"""
import argparse
import gzip
import json
import math
import os
import struct
import sys

HEADER_SIZE = 127


def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def decode_directory(raw):
    """The PMTiles v3 directory: entry count, delta-encoded tile ids, run
    lengths, lengths, then offsets as value+1 (0 = contiguous with the
    previous entry). Mirrors PmtilesDirectory.decode."""
    count, pos = read_varint(raw, 0)
    tile_ids = []
    last = 0
    for _ in range(count):
        delta, pos = read_varint(raw, pos)
        last += delta
        tile_ids.append(last)
    run_lengths = []
    for _ in range(count):
        value, pos = read_varint(raw, pos)
        run_lengths.append(value)
    lengths = []
    for _ in range(count):
        value, pos = read_varint(raw, pos)
        lengths.append(value)
    entries = []
    offset = 0
    for i in range(count):
        raw_offset, pos = read_varint(raw, pos)
        if raw_offset == 0 and i > 0:
            offset = entries[i - 1][2] + entries[i - 1][3]
        else:
            offset = raw_offset - 1
        entries.append((tile_ids[i], run_lengths[i], offset, lengths[i]))
    return entries


def find_entry(entries, tile_id):
    """The entry covering tile_id, or the leaf pointer to descend into
    (run length 0); None when the id falls into a gap."""
    candidate = None
    for entry in entries:
        if entry[0] > tile_id:
            break
        candidate = entry
    if candidate is None:
        return None
    if candidate[1] == 0:  # leaf pointer
        return candidate
    if tile_id < candidate[0] + candidate[1]:
        return candidate
    return None


def xy2d(x_in, y_in, n):
    """Standard Hilbert curve index in the PMTiles id order — mirrors
    HilbertTileId.xy2d (z1 (0,0)->(0,1)->(1,1)->(1,0))."""
    x, y, d, s = x_in, y_in, 0, n // 2
    while s > 0:
        rx = 1 if (x & s) != 0 else 0
        ry = 1 if (y & s) != 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x = n - 1 - x
                y = n - 1 - y
            x, y = y, x
        s //= 2
    return d


def z14_tile_id(lon, lat):
    """The PMTiles tile id of the z14 Web-Mercator tile containing the
    given point. Verified against HilbertTileId's spec vectors."""
    zoom = 14
    n = 1 << zoom
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    acc = (4 ** zoom - 1) // 3
    return acc + xy2d(x, y, n)


def read_z14_tile(handle, tile_id, root_offset, root_length, leaf_offset, data_offset):
    """The decompressed MVT bytes of [tile_id], descending leaves."""
    handle.seek(root_offset)
    entries = decode_directory(gzip.decompress(handle.read(root_length)))
    for _ in range(8):  # leaf-depth bound; sane archives nest 1-2 deep
        entry = find_entry(entries, tile_id)
        if entry is None:
            return None
        if entry[1] != 0:  # a real tile: offsets are tile-data-section relative
            handle.seek(data_offset + entry[2])
            return gzip.decompress(handle.read(entry[3]))
        handle.seek(leaf_offset + entry[2])
        entries = decode_directory(gzip.decompress(handle.read(entry[3])))
    raise AssertionError("tile directory leaf depth exceeded")


def check_header(path):
    with open(path, "rb") as f:
        d = f.read(HEADER_SIZE)
    assert d[:7] == b"PMTiles" and d[7] == 3, f"bad magic/version: {d[:8]!r}"
    assert d[100] == 0 and d[101] == 14, f"zooms must be 0-14, got {d[100]}-{d[101]}"
    entries = struct.unpack_from("<Q", d, 80)[0]
    size = os.path.getsize(path)
    return d, entries, size


def check_address_layer(path, lon, lat):
    """The z14 tile at the point must carry the merged address layer."""
    tile_id = z14_tile_id(lon, lat)
    with open(path, "rb") as f:
        d = f.read(HEADER_SIZE)
        root_offset, root_length = struct.unpack_from("<QQ", d, 8)
        leaf_offset = struct.unpack_from("<Q", d, 40)[0]
        data_offset = struct.unpack_from("<Q", d, 56)[0]
        tile = read_z14_tile(
            f, tile_id, root_offset, root_length, leaf_offset, data_offset,
        )
    assert tile is not None, f"no z14 tile at {lon},{lat} — merge lost the base tile"
    # Wire-level patterns: layer name (layer field 1), property keys (field 3).
    assert b"\x0a\x07address" in tile, "no `address` layer in the z14 tile"
    assert b"\x1a\x06number" in tile, "no `number` property key"
    assert b"\x1a\x06street" in tile, "no `street` property key"
    assert b"\x1a\x08postcode" not in tile, "bulk properties leaked past the clip pass"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="the .pmtiles archive to check")
    parser.add_argument(
        "--address-at",
        help='"LON,LAT" — also probe that z14 tile for the address layer',
    )
    args = parser.parse_args()

    _, entries, size = check_header(args.path)
    line = f"PMTiles v3, zooms 0-14, {entries} tiles, {size / 2**30:.2f} GiB"
    if args.address_at:
        lon_text, lat_text = args.address_at.split(",")
        check_address_layer(args.path, float(lon_text), float(lat_text))
        line += ", address layer present"

    print(f"{args.path}: {line}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as s:
            s.write(f"### {os.path.basename(args.path)}\n\n{line}\n\n")


if __name__ == "__main__":
    sys.exit(main())