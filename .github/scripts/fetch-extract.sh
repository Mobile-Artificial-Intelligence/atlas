#!/usr/bin/env bash
# Download a Geofabrik extract, tolerating its real-world failure modes:
#   - dropped connections mid-body on the multi-GB extracts — retried and
#     RESUMED (--continue-at -) instead of restarting 11 GB from zero;
#   - incident-window HTML error pages (a 200 with a body full of HTML) —
#     --fail cannot catch those, so the pbf structure does. An OSM pbf
#     starts with a 4-byte big-endian BlobHeader length (00 00 00 xx),
#     then the BlobHeader's first field: 0a 09 "OSMHeader". An error page
#     starts with '<' (3c). (The first byte alone is 00 for every valid
#     pbf — a first-byte 0a check rejects the very thing it validates.)
set -euo pipefail

url=$1
out=$2
MIN_BYTES=100000

mkdir -p "$(dirname "$out")"
curl --fail --location --retry 8 --retry-delay 10 --retry-all-errors \
     --continue-at - --output "$out" "$url"

# Skip the 4-byte length prefix; the next 11 bytes must be the OSMHeader
# field tag (0a), its length (09), and the literal "OSMHeader".
head_hex=$(od -An -tx1 -N15 "$out" | tr -d ' \n')
if [ "${head_hex:8}" != "0a094f534d486561646572" ]; then
  echo "::error::$out is not an OSM pbf (starts $(head -c 15 "$out" | od -An -tx1 | tr -d ' \n')) — Geofabrik error page? $(head -c 200 "$out" | tr '\n' ' ')"
  exit 1
fi
size=$(wc -c < "$out")
if [ "$size" -lt "$MIN_BYTES" ]; then
  echo "::error::$out is only $size bytes (floor $MIN_BYTES) — Geofabrik error page?"
  exit 1
fi
echo "$out: $size bytes, valid OSM pbf header"